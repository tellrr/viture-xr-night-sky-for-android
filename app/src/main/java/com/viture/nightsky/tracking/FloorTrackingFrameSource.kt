package com.viture.nightsky.tracking

interface FloorTrackingFrameSource {
    fun onGlSurfaceCreated()
    fun updateFromGlFrame()
}
