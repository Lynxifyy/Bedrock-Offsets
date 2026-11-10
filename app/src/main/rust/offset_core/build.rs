//! Build-time guard for the native engine.
//!
//! Android builds go through cargo-ndk, which cross-compiles the crate for the
//! device ABI and links against the NDK sysroot. Gradle additionally enables a
//! small CMake shim (see app/src/main/cpp/CMakeLists.txt) that imports the
//! resulting `liboffset_core.so`, so a *host* build of this crate would fail
//! with a linker error against the missing Android C++ runtime.
//!
//! Rather than guess at an environment, this guard fails with an actionable
//! message and points at the supported command.

fn main() {
    println!("cargo:rerun-if-changed=build.rs");

    let target = std::env::var("TARGET").unwrap_or_default();
    let is_android = target.contains("android");

    if is_android {
        // Android logging is provided by the NDK; nothing else to wire up.
        println!("cargo:rustc-link-lib=log");
        return;
    }

    // Host builds are supported for `cargo test` and `cargo clippy`, which do
    // not link the cdylib. Only the Android packaging path needs the NDK, and
    // it always goes through cargo-ndk.
    if std::env::var("CARGO_NDK_TARGET").is_ok() {
        return;
    }

    println!(
        "cargo:warning=offset_core is being built for host target '{target}'. \
         Android packaging requires: scripts/build_rust.py (cargo ndk -t arm64-v8a build --release)"
    );
}
