cmake_minimum_required(VERSION 3.22)
project(voicechat_android LANGUAGES C)
set(CMAKE_C_STANDARD 99)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
get_filename_component(ROOT "${CMAKE_CURRENT_LIST_DIR}/../.." ABSOLUTE)
set(SOURCES "${ROOT}/.build-tools/voicechat-android")
set(RNNOISE "${CMAKE_CURRENT_BINARY_DIR}/rnnoise")
set(SPEEX "${SOURCES}/speexdsp")
file(COPY "${SOURCES}/rnnoise/src" "${SOURCES}/rnnoise/include" DESTINATION "${RNNOISE}")
# Reset patched files on every configure; file(COPY) alone may keep newer outputs.
foreach(source denoise.c parse_lpcnet_weights.c)
    configure_file("${SOURCES}/rnnoise/src/${source}" "${RNNOISE}/src/${source}" COPYONLY)
endforeach()
include("${CMAKE_CURRENT_LIST_DIR}/harden-rnnoise.cmake")

# Match rnnoise4j 2.1.3: model weights are supplied by the original mod JAR.
file(READ "${SOURCES}/model/src/rnnoise_data_little.c" MODEL_DATA)
string(REGEX REPLACE "=[ \t\r\n]*{[ \t\r\n\-0-9.,e]*};" "= {};" MODEL_STUB "${MODEL_DATA}")
file(WRITE "${RNNOISE}/src/rnnoise_data.c" "${MODEL_STUB}")
configure_file("${SOURCES}/model/src/rnnoise_data_little.h"
    "${RNNOISE}/src/rnnoise_data.h" COPYONLY)
file(GLOB RNNOISE_SRC CONFIGURE_DEPENDS "${RNNOISE}/src/*.c")
add_library(rnnoise STATIC ${RNNOISE_SRC})
target_include_directories(rnnoise PUBLIC "${CMAKE_CURRENT_BINARY_DIR}" "${RNNOISE}" "${RNNOISE}/include" "${RNNOISE}/src")
target_compile_definitions(rnnoise PRIVATE USE_WEIGHTS_FILE)

configure_file("${SOURCES}/speex4j/native/cmake/speex/speexdsp_config_types.h"
    "${CMAKE_CURRENT_BINARY_DIR}/speex/speexdsp_config_types.h" COPYONLY)
file(GLOB SPEEX_SRC CONFIGURE_DEPENDS "${SPEEX}/libspeexdsp/*.c")
add_library(speex STATIC ${SPEEX_SRC})
target_include_directories(speex PUBLIC "${CMAKE_CURRENT_BINARY_DIR}/speex" "${SPEEX}/include" "${SPEEX}/libspeexdsp")
target_compile_definitions(speex PRIVATE EXPORT= FLOATING_POINT USE_SMALLFT DISABLE_WARNINGS)

add_library(rnnoise4j SHARED "${CMAKE_CURRENT_LIST_DIR}/android-rnnoise.c" "${SOURCES}/rnnoise4j/native/src/exceptions.c")
target_include_directories(rnnoise4j PRIVATE "${SOURCES}/rnnoise4j/native/include")
target_link_libraries(rnnoise4j PRIVATE rnnoise m)
add_library(speex4j SHARED "${SOURCES}/speex4j/native/src/agc.c" "${SOURCES}/speex4j/native/src/exceptions.c")
target_include_directories(speex4j PRIVATE "${SOURCES}/speex4j/native/include")
target_link_libraries(speex4j PRIVATE speex m)
include("${CMAKE_CURRENT_LIST_DIR}/opus-android.cmake")

foreach(target rnnoise4j speex4j opus4j)
    target_link_options(${target} PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--no-undefined")
endforeach()
