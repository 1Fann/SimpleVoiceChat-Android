# Native codec matching the opus4j 2.1.3 classes already present in both mod JARs.
# Sources are downloaded and SHA256-checked by download-opus.mjs before configure.
if(NOT ANDROID OR NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "opus-android.cmake requires the Android NDK and arm64-v8a")
endif()
if(ANDROID_NATIVE_API_LEVEL LESS 26)
    message(FATAL_ERROR "The voice chat Android build requires API 26 or newer")
endif()
get_filename_component(VOICECHAT_OPUS_ROOT "${CMAKE_CURRENT_LIST_DIR}/../.." ABSOLUTE)
set(VOICECHAT_OPUS_SOURCES "${VOICECHAT_OPUS_ROOT}/.build-tools/voicechat-opus")
foreach(source opus/CMakeLists.txt opus/COPYING opus4j/native/src/encoder.c
               opus4j/native/src/decoder.c opus4j/native/src/exceptions.c sources.json)
    if(NOT EXISTS "${VOICECHAT_OPUS_SOURCES}/${source}")
        message(FATAL_ERROR "Missing ${source}; run node tools/voicechat-android/download-opus.mjs")
    endif()
endforeach()

# Use baseline ARMv8 NEON, which is mandatory for this ABI; no device-specific
# architecture, dot-product extension, or fast-math flags are required.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_DISABLE_INTRINSICS OFF CACHE BOOL "" FORCE)
set(OPUS_MAY_HAVE_NEON ON CACHE BOOL "" FORCE)
set(OPUS_PRESUME_NEON ON CACHE BOOL "" FORCE)
set(OPUS_FIXED_POINT OFF CACHE BOOL "" FORCE)
set(OPUS_FAST_MATH OFF CACHE BOOL "" FORCE)
set(OPUS_HARDENING ON CACHE BOOL "" FORCE)
set(OPUS_DRED OFF CACHE BOOL "" FORCE)
set(OPUS_OSCE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
add_subdirectory("${VOICECHAT_OPUS_SOURCES}/opus" "${CMAKE_CURRENT_BINARY_DIR}/opus" EXCLUDE_FROM_ALL)
set_target_properties(opus PROPERTIES POSITION_INDEPENDENT_CODE ON)

# The NDK supplies its own ABI-correct jni.h; upstream's build downloads OpenJDK
# headers, so compile its three unchanged wrapper sources directly instead.
add_library(opus4j SHARED
    "${VOICECHAT_OPUS_SOURCES}/opus4j/native/src/encoder.c"
    "${VOICECHAT_OPUS_SOURCES}/opus4j/native/src/decoder.c"
    "${VOICECHAT_OPUS_SOURCES}/opus4j/native/src/exceptions.c")
target_include_directories(opus4j PRIVATE
    "${VOICECHAT_OPUS_SOURCES}/opus4j/native/include"
    "${VOICECHAT_OPUS_SOURCES}/opus/include")
# Unlike OpenJDK's header, Android's jni.h does not transitively include stdio.h;
# the upstream exception formatter needs the vsnprintf declaration.
target_compile_options(opus4j PRIVATE -include stdio.h -fvisibility=hidden)
set_target_properties(opus4j PROPERTIES C_STANDARD 99 C_STANDARD_REQUIRED ON)
target_link_libraries(opus4j PRIVATE opus m)
target_link_options(opus4j PRIVATE
    "-Wl,-z,max-page-size=16384" "-Wl,--no-undefined" "-Wl,--exclude-libs,ALL")
