cmake_minimum_required(VERSION 3.22.1)

project(viture_native_tracking)

set(VITURE_SDK_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/viture/android/sdk-2.2.1")

add_library(viture_native_tracking SHARED
    viture_native_tracking.cpp
)

add_library(glasses SHARED IMPORTED)
set_target_properties(glasses PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libglasses.so"
)

target_include_directories(viture_native_tracking PRIVATE
    "${VITURE_SDK_ROOT}/include"
)

target_link_libraries(viture_native_tracking
    glasses
    android
    log
)
