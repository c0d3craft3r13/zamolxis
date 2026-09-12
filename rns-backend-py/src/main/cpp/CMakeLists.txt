# Native code loaded by Python through ctypes, not by the JVM through JNI.
#
# There is no `System.loadLibrary` for any of this and no JNI header: the only
# caller is CPython inside the `:reticulum` process, which dlopens by soname out
# of the APK's lib/<abi>/ directory. That is why the exported names are plain C
# — a ctypes caller has no way to demangle anything else — and why visibility
# stays default.
#
# Two libraries, kept apart on purpose:
#
#   libmayak_native.so  proves the loader works at all (see mayak_native.c).
#                       Independent of whether the cryptography below compiles,
#                       which is what makes it useful when something breaks.
#   libmayak_mlkem.so   ML-KEM-768, the post-quantum half of the Mayak
#                       protocol. On Android it cannot come from
#                       `cryptography`: Chaquopy's index stops at 42.0.8 and
#                       ML-KEM landed in 47.

cmake_minimum_required(VERSION 3.22)

project(mayak_native C ASM)

get_filename_component(
    MLKEM_NATIVE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../vendor/mlkem-native" ABSOLUTE
)

if(NOT EXISTS "${MLKEM_NATIVE_DIR}/mlkem/mlkem_native.c")
    message(FATAL_ERROR "vendored mlkem-native is missing at ${MLKEM_NATIVE_DIR}")
endif()

# ---------------------------------------------------------------- loader proof

add_library(mayak_native SHARED mayak_native.c)

# -Werror belongs on code this project wrote and nowhere else.
target_compile_options(mayak_native PRIVATE -Wall -Wextra -Werror)

# ------------------------------------------------------------------- ML-KEM-768

# Upstream compiled as its own target so our warning settings are never imposed
# on somebody else's code: a new compiler warning in a future NDK would
# otherwise break the build on a file nobody here is allowed to edit.
#
# One translation unit — mlkem_native.c #includes the rest — which is upstream's
# own "monolithic build" arrangement and lets the compiler make its internal
# functions static.
add_library(mlkem_native_upstream STATIC "${MLKEM_NATIVE_DIR}/mlkem/mlkem_native.c")

set_target_properties(mlkem_native_upstream PROPERTIES POSITION_INDEPENDENT_CODE ON)

target_include_directories(mlkem_native_upstream
    PUBLIC "${MLKEM_NATIVE_DIR}/mlkem"
    PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}"
)

# Both translation units include mlkem_native.h, so both need to be told which
# configuration they are being built under. Disagreeing would mean two different
# parameter sets in one library.
set(MAYAK_MLKEM_CONFIG "MLK_CONFIG_FILE=\"mayak_mlkem_config.h\"")

target_compile_definitions(mlkem_native_upstream PUBLIC ${MAYAK_MLKEM_CONFIG})

add_library(mayak_mlkem SHARED mayak_mlkem.c)

target_compile_options(mayak_mlkem PRIVATE -Wall -Wextra -Werror)

target_include_directories(mayak_mlkem PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")

target_link_libraries(mayak_mlkem PRIVATE mlkem_native_upstream)
