package com.viture.nightsky.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import java.util.Locale

data class PanoramaImage(
    val bitmap: Bitmap,
    val label: String
)

data class PanoramaFolderState(
    val folderName: String,
    val currentIndex: Int,
    val imageCount: Int,
    val currentName: String
) {
    val displayLabel: String
        get() = "$currentName (${currentIndex + 1}/$imageCount)"
}

class PanoramaRepository(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun selectFolder(folderUri: Uri): PanoramaFolderState? {
        preferences.edit()
            .putString(KEY_FOLDER_URI, folderUri.toString())
            .putInt(KEY_FOLDER_INDEX, 0)
            .apply()

        val state = selectedFolderState()
        if (state == null) {
            preferences.edit().remove(KEY_FOLDER_URI).remove(KEY_FOLDER_INDEX).apply()
        }
        return state
    }

    fun moveFolderSelection(delta: Int): PanoramaFolderState? {
        val images = selectedFolderImages()
        if (images.isEmpty()) {
            return null
        }

        val currentIndex = preferences.getInt(KEY_FOLDER_INDEX, 0)
        val nextIndex = wrapIndex(currentIndex + delta, images.size)
        preferences.edit().putInt(KEY_FOLDER_INDEX, nextIndex).apply()
        return buildFolderState(images, nextIndex)
    }

    fun selectedFolderState(): PanoramaFolderState? {
        val images = selectedFolderImages()
        if (images.isEmpty()) {
            return null
        }

        val index = preferences.getInt(KEY_FOLDER_INDEX, 0).coerceIn(0, images.lastIndex)
        if (index != preferences.getInt(KEY_FOLDER_INDEX, 0)) {
            preferences.edit().putInt(KEY_FOLDER_INDEX, index).apply()
        }
        return buildFolderState(images, index)
    }

    fun loadPanorama(maxTextureSize: Int): PanoramaImage? {
        loadSelectedFolderPanorama(maxTextureSize)?.let {
            return it
        }

        buildFileCandidates().firstNotNullOfOrNull { candidate ->
            decodeFile(candidate, maxTextureSize)?.let { bitmap ->
                PanoramaImage(bitmap, humanizeFileStem(candidate.nameWithoutExtension))
            }
        }?.let {
            return it
        }

        buildAssetCandidates().firstNotNullOfOrNull { assetPath ->
            decodeAsset(assetPath, maxTextureSize)?.let { bitmap ->
                PanoramaImage(bitmap, humanizeFileStem(assetPath.substringAfterLast('/').substringBeforeLast('.')))
            }
        }?.let {
            return it
        }

        return null
    }

    private fun loadSelectedFolderPanorama(maxTextureSize: Int): PanoramaImage? {
        val images = selectedFolderImages()
        if (images.isEmpty()) {
            return null
        }

        val index = preferences.getInt(KEY_FOLDER_INDEX, 0).coerceIn(0, images.lastIndex)
        val document = images[index]
        val bitmap = decodeDocument(document, maxTextureSize) ?: return null
        val label = buildFolderState(images, index)?.displayLabel
            ?: humanizeFileStem(document.name.orEmpty().substringBeforeLast('.'))
        return PanoramaImage(bitmap, label)
    }

    private fun selectedFolderImages(): List<DocumentFile> {
        val folderUri = preferences.getString(KEY_FOLDER_URI, null)?.let(Uri::parse) ?: return emptyList()
        val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return emptyList()
        if (!folder.isDirectory) {
            return emptyList()
        }

        return folder.listFiles()
            .asSequence()
            .filter { document -> document.isFile && isSupportedImage(document) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { document -> document.name.orEmpty() })
            .toList()
    }

    private fun buildFolderState(images: List<DocumentFile>, index: Int): PanoramaFolderState? {
        if (images.isEmpty()) {
            return null
        }

        val folderUri = preferences.getString(KEY_FOLDER_URI, null)?.let(Uri::parse) ?: return null
        val folderName = DocumentFile.fromTreeUri(context, folderUri)?.name ?: "Selected Folder"
        val safeIndex = index.coerceIn(0, images.lastIndex)
        val documentName = images[safeIndex].name ?: "Panorama"
        return PanoramaFolderState(
            folderName = folderName,
            currentIndex = safeIndex,
            imageCount = images.size,
            currentName = humanizeFileStem(documentName.substringBeforeLast('.'))
        )
    }

    private fun isSupportedImage(document: DocumentFile): Boolean {
        val mimeType = document.type.orEmpty().lowercase(Locale.US)
        if (mimeType.startsWith("image/")) {
            return true
        }

        val name = document.name.orEmpty().lowercase(Locale.US)
        return SUPPORTED_EXTENSIONS.any { extension -> name.endsWith(extension) }
    }

    private fun buildFileCandidates(): List<File> {
        val names = listOf(
            "current_panorama.jpg",
            "current_panorama.jpeg",
            "current_panorama.png",
            "current_panorama.webp"
        )

        val externalRoot = context.getExternalFilesDir(null)
        val internalRoot = context.filesDir
        val results = ArrayList<File>(names.size * 2)
        for (name in names) {
            if (externalRoot != null) {
                results += File(externalRoot, "panoramas/$name")
            }
            results += File(internalRoot, "panoramas/$name")
        }
        return results
    }

    private fun buildAssetCandidates(): List<String> {
        return listOf(
            "panoramas/current_panorama.jpg",
            "panoramas/current_panorama.jpeg",
            "panoramas/current_panorama.png",
            "panoramas/current_panorama.webp"
        )
    }

    private fun decodeDocument(document: DocumentFile, maxTextureSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        openDocument(document)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        } ?: return null

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxTextureSize)
        }

        val bitmap = openDocument(document)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: return null

        return scaleDownIfNeeded(bitmap, maxTextureSize)
    }

    private fun decodeFile(file: File, maxTextureSize: Int): Bitmap? {
        if (!file.isFile) {
            return null
        }

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxTextureSize)
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return null
        return scaleDownIfNeeded(bitmap, maxTextureSize)
    }

    private fun decodeAsset(assetPath: String, maxTextureSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        openAsset(assetPath)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        } ?: return null

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxTextureSize)
        }

        val bitmap = openAsset(assetPath)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: return null

        return scaleDownIfNeeded(bitmap, maxTextureSize)
    }

    private fun openDocument(document: DocumentFile): InputStream? {
        return try {
            context.contentResolver.openInputStream(document.uri)
        } catch (_: Exception) {
            null
        }
    }

    private fun openAsset(assetPath: String): InputStream? {
        return try {
            context.assets.open(assetPath)
        } catch (_: Exception) {
            null
        }
    }

    private fun computeInSampleSize(width: Int, height: Int, maxTextureSize: Int): Int {
        var sampleSize = 1
        while ((width / sampleSize) > maxTextureSize || (height / sampleSize) > maxTextureSize) {
            sampleSize *= 2
        }
        return sampleSize.coerceAtLeast(1)
    }

    private fun scaleDownIfNeeded(bitmap: Bitmap, maxTextureSize: Int): Bitmap {
        if (bitmap.width <= maxTextureSize && bitmap.height <= maxTextureSize) {
            return bitmap
        }

        val scale = minOf(
            maxTextureSize.toFloat() / bitmap.width.toFloat(),
            maxTextureSize.toFloat() / bitmap.height.toFloat()
        )
        val scaledWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
        if (scaled !== bitmap) {
            bitmap.recycle()
        }
        return scaled
    }

    private fun humanizeFileStem(stem: String): String {
        return stem.replace('_', ' ')
            .replace('-', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.lowercase(Locale.US).replaceFirstChar { char ->
                    if (char.isLowerCase()) {
                        char.titlecase(Locale.US)
                    } else {
                        char.toString()
                    }
                }
            }
    }

    private fun wrapIndex(index: Int, size: Int): Int {
        if (size <= 0) {
            return 0
        }

        val remainder = index % size
        return if (remainder >= 0) remainder else remainder + size
    }

    private companion object {
        private const val PREFS_NAME = "panorama_repository"
        private const val KEY_FOLDER_URI = "folder_uri"
        private const val KEY_FOLDER_INDEX = "folder_index"
        private val SUPPORTED_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".webp")
    }
}
