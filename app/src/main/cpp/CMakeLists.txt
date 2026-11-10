# Native build description for the Rust engine.
#
# The engine itself is compiled by cargo-ndk (see scripts/build_rust.py) and
# packaged from app/src/main/jniLibs/arm64-v8a/liboffset_core.so. This file
# imports that prebuilt object so future C/C++ shims can link against it without
# recompiling the Rust code.
#
# It is only wired into Gradle when the prebuilt library exists, so a checkout
# without the Rust toolchain still configures and tests cleanly.
cmake_minimum_required(VERSION 3.22.1)

project(offset_core_shim LANGUAGES CXX)

set(RUST_LIB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
set(RUST_LIB "${RUST_LIB_DIR}/liboffset_core.so")

if(NOT EXISTS "${RUST_LIB}")
    message(STATUS
        "liboffset_core.so not found at ${RUST_LIB}; skipping the native shim. "
        "Run scripts/build_rust.py to build it.")
    return()
endif()

# A concrete target is required by the Android Gradle plugin even though the
# interesting code lives in the imported Rust object. The shim re-exports
# nothing yet and exists so the module always has one compiled target.
add_library(offset_core_shim SHARED shim.cpp)

add_library(offset_core SHARED IMPORTED)
set_target_properties(offset_core PROPERTIES IMPORTED_LOCATION "${RUST_LIB}")

target_link_libraries(offset_core_shim PRIVATE offset_core log)
