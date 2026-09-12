# Native code loaded by Python through ctypes, not by the JVM through JNI.
#
# There is no `System.loadLibrary` for this and no JNI header: the only caller is
# CPython inside the `:reticulum` process, which dlopens it by soname out of the
# APK's lib/<abi>/ directory. That is why the exported names are plain C — a
# ctypes caller has no way to demangle anything else.
#
# See mayak_native.c for what this is for and why it is not cryptography yet.

cmake_minimum_required(VERSION 3.22)

project(mayak_native C)

add_library(mayak_native SHARED mayak_native.c)

# -fvisibility=hidden would be right for a JNI library, where the JVM finds
# symbols by a registration table. Here ctypes looks them up by name, so the
# default visibility is the interface.
target_compile_options(mayak_native PRIVATE -Wall -Wextra -Werror)
