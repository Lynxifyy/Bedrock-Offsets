//! JNI entry points exposed to Kotlin as `NativeLib`.
//!
//! Every function is wrapped in [`crate::android::catch_panics`] and converts
//! failures into a thrown Java exception or a sentinel value, so a malformed
//! APK can never abort the process.

use jni::objects::{JObject, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use serde::Serialize;
use std::path::PathBuf;

use crate::android::{catch_panics, install};
use crate::elf::MappedLibrary;
use crate::error::{CoreError, CoreResult};
use crate::sections::build_section_map;
use crate::symbols::build_symbol_report;
use crate::zip_extract::extract_so_from_zip;

/// Fully-qualified name of the Java exception raised for engine failures.
const ENGINE_EXCEPTION_CLASS: &str = "com/chimerateam/offsetextractor/data/native/EngineFailure";

/// Payload returned to Kotlin after a successful extraction.
#[derive(Debug, Serialize)]
struct ExtractionPayload<'a> {
    path: &'a str,
    sha256: &'a str,
    size: u64,
}

/// Returns the engine version, used by the JNI bridge test.
#[no_mangle]
pub extern "system" fn Java_com_chimerateam_offsetextractor_data_native_NativeLib_rustTestNative(
    env: JNIEnv,
    _this: JObject,
) -> jstring {
    install();
    let message = format!("offset_core {}", env!("CARGO_PKG_VERSION"));
    match env.new_string(message) {
        Ok(string) => string.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Extracts `lib/arm64-v8a/libminecraftpe.so` from the APK behind `fd`.
///
/// # Returns
///
/// A JSON object with `path`, `sha256` and `size` on success.
///
/// # Errors
///
/// Throws `EngineFailure` with the message `kind: detail` when the archive is
/// unreadable, the descriptor is unusable, or the entry is missing.
#[no_mangle]
pub extern "system" fn Java_com_chimerateam_offsetextractor_data_native_NativeLib_extractNativeLibraryNative(
    mut env: JNIEnv,
    _this: JObject,
    fd: jint,
    dest_dir: JString,
) -> jstring {
    install();

    let destination = match env.get_string(&dest_dir) {
        Ok(value) => PathBuf::from(value.to_string_lossy().into_owned()),
        Err(error) => {
            throw_engine_failure(
                &mut env,
                "invalid descriptor: cannot read destination path",
                &error.to_string(),
            );
            return std::ptr::null_mut();
        }
    };

    let outcome = catch_panics("extracting native library", move || {
        extract_so_from_zip(fd, &destination)
    });

    match outcome {
        Ok(Ok(extracted)) => {
            let payload = ExtractionPayload {
                path: &extracted.path,
                sha256: &extracted.sha256,
                size: extracted.size,
            };
            let json = serde_json::to_string(&payload)
                .unwrap_or_else(|_| String::from("{\"path\":\"\",\"sha256\":\"\",\"size\":0}"));
            match env.new_string(json) {
                Ok(string) => string.into_raw(),
                Err(_) => std::ptr::null_mut(),
            }
        }

        Ok(Err(core_error)) => {
            throw_core_error(&mut env, &core_error);
            std::ptr::null_mut()
        }

        Err(panic_message) => {
            throw_engine_failure(&mut env, "unknown", &panic_message);
            std::ptr::null_mut()
        }
    }
}

/// Maps the extracted native library and reports its ELF metadata.
///
/// The library is mapped with `mmap`; its bytes are never copied into the JVM
/// heap, so an 800 MB shared object costs address space rather than memory.
///
/// # Returns
///
/// A JSON object describing the mapped library.
///
/// # Errors
///
/// Throws `EngineFailure` with the message `kind: detail` when the file is not
/// a 64-bit AArch64 ELF object.
#[no_mangle]
pub extern "system" fn Java_com_chimerateam_offsetextractor_data_native_NativeLib_mapNativeLibraryNative(
    mut env: JNIEnv,
    _this: JObject,
    path: JString,
    sha256: JString,
) -> jstring {
    install();

    let library_path = match read_jstring(&mut env, &path, "library path") {
        Some(value) => value,
        None => return std::ptr::null_mut(),
    };
    let library_hash = match read_jstring(&mut env, &sha256, "library hash") {
        Some(value) => value,
        None => return std::ptr::null_mut(),
    };

    let outcome = catch_panics("mapping native library", move || {
        map_library_json(&library_path, &library_hash)
    });

    match outcome {
        Ok(Ok(json)) => match env.new_string(json) {
            Ok(string) => string.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Ok(Err(core_error)) => {
            throw_core_error(&mut env, &core_error);
            std::ptr::null_mut()
        }
        Err(panic_message) => {
            throw_engine_failure(&mut env, "unknown", &panic_message);
            std::ptr::null_mut()
        }
    }
}

/// Maps the library and reports its section layout.
///
/// The section header table is read from the mapping; no section body is
/// copied, so a large `.rodata` costs no extra memory.
///
/// # Returns
///
/// A JSON object with the image base, the `.text` boundaries and every section.
///
/// # Errors
///
/// Throws `EngineFailure` when the library cannot be mapped, has no section
/// header table, or has no usable `.text` section.
#[no_mangle]
pub extern "system" fn Java_com_chimerateam_offsetextractor_data_native_NativeLib_describeSectionsNative(
    mut env: JNIEnv,
    _this: JObject,
    path: JString,
    sha256: JString,
) -> jstring {
    install();

    let library_path = match read_jstring(&mut env, &path, "library path") {
        Some(value) => value,
        None => return std::ptr::null_mut(),
    };
    let library_hash = match read_jstring(&mut env, &sha256, "library hash") {
        Some(value) => value,
        None => return std::ptr::null_mut(),
    };

    let outcome = catch_panics("describing sections", move || {
        describe_sections_json(&library_path, &library_hash)
    });

    match outcome {
        Ok(Ok(json)) => match env.new_string(json) {
            Ok(string) => string.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Ok(Err(core_error)) => {
            throw_core_error(&mut env, &core_error);
            std::ptr::null_mut()
        }
        Err(panic_message) => {
            throw_engine_failure(&mut env, "unknown", &panic_message);
            std::ptr::null_mut()
        }
    }
}

/// Reports the build identifier and exported symbols of the mapped library.
///
/// # Returns
///
/// A JSON object with `identity` and `symbols` on success.
///
/// # Errors
///
/// Throws `EngineFailure` when the library cannot be mapped, has no usable
/// `.text` section, or has an unreadable symbol table.
#[no_mangle]
pub extern "system" fn Java_com_chimerateam_offsetextractor_data_native_NativeLib_describeSymbolsNative(
    mut env: JNIEnv,
    _this: JObject,
    path: JString,
    sha256: JString,
) -> jstring {
    install();

    let library_path = match read_jstring(&mut env, &path, "library path") {
        Some(value) => value,
        None => return std::ptr::null_mut(),
    };
    let library_hash = match read_jstring(&mut env, &sha256, "library hash") {
        Some(value) => value,
        None => return std::ptr::null_mut(),
    };

    let outcome = catch_panics("reading symbols", move || {
        describe_symbols_json(&library_path, &library_hash)
    });

    match outcome {
        Ok(Ok(json)) => match env.new_string(json) {
            Ok(string) => string.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Ok(Err(core_error)) => {
            throw_core_error(&mut env, &core_error);
            std::ptr::null_mut()
        }
        Err(panic_message) => {
            throw_engine_failure(&mut env, "unknown", &panic_message);
            std::ptr::null_mut()
        }
    }
}

/// Maps `path` and serialises its symbols, exposed for host tests.
///
/// # Errors
///
/// Propagates any mapping, section or symbol failure.
pub fn describe_symbols_json(path: &str, sha256: &str) -> CoreResult<String> {
    let library = MappedLibrary::open(std::path::Path::new(path), sha256.to_string())?;
    let report = build_symbol_report(library.bytes())?;
    Ok(serde_json::to_string(&report).unwrap_or_default())
}

/// Maps `path` and serialises its section layout, exposed for host tests.
///
/// # Errors
///
/// Propagates any mapping or section-parsing failure.
pub fn describe_sections_json(path: &str, sha256: &str) -> CoreResult<String> {
    let library = MappedLibrary::open(std::path::Path::new(path), sha256.to_string())?;
    let map = build_section_map(library.bytes())?;
    Ok(serde_json::to_string(&map).unwrap_or_default())
}

/// Reads a Java string argument, throwing `EngineFailure` when it is unusable.
fn read_jstring(env: &mut JNIEnv, value: &JString, what: &str) -> Option<String> {
    match env.get_string(value) {
        Ok(text) => Some(text.to_string_lossy().into_owned()),
        Err(error) => {
            throw_engine_failure(
                env,
                "invalid_descriptor",
                &format!("cannot read {what}: {error}"),
            );
            None
        }
    }
}

/// Returns the JNI version, telling Android the library is usable.
///
/// # Safety
///
/// Called by the JVM during `System.loadLibrary`.
#[no_mangle]
pub unsafe extern "system" fn JNI_OnLoad(
    _vm: *mut jni::sys::JavaVM,
    _reserved: *mut std::ffi::c_void,
) -> jint {
    install();
    jni::sys::JNI_VERSION_1_6
}

fn throw_core_error(env: &mut JNIEnv, error: &CoreError) {
    let kind = match error {
        CoreError::MissingSoFile(_) => "missing_so",
        CoreError::InvalidZip(_) => "invalid_apk",
        CoreError::InvalidDescriptor(_) => "invalid_descriptor",
        CoreError::Io(message) if message.contains("No space left") => "storage_full",
        CoreError::Io(_) => "io_error",
        CoreError::InvalidElf(_) => "invalid_elf",
        CoreError::UnsupportedArchitecture(_) => "unsupported_arch",
        CoreError::MissingSection(_) => "missing_section",
        CoreError::InvalidPattern(_) => "invalid_pattern",
        CoreError::Cancelled => "cancelled",
    };
    throw_engine_failure(env, kind, &error.to_string());
}

fn throw_engine_failure(env: &mut JNIEnv, kind: &str, detail: &str) {
    let message = format!("{kind}: {detail}");
    if env.throw_new(ENGINE_EXCEPTION_CLASS, message).is_err() {
        // Throwing failed, which itself leaves a pending exception. Clearing it
        // is the only safe move: returning normally with an exception pending
        // is undefined behaviour.
        let _ = env.exception_clear();
    }
}

/// Maps `path` and serialises its metadata, exposed for host tests.
///
/// # Errors
///
/// Propagates any mapping or parsing failure.
pub fn map_library_json(path: &str, sha256: &str) -> CoreResult<String> {
    let library = MappedLibrary::open(std::path::Path::new(path), sha256.to_string())?;
    let metadata = library.metadata()?;
    Ok(serde_json::to_string(&metadata).unwrap_or_default())
}

/// Builds the payload the JNI function serialises, exposed for host tests.
///
/// # Errors
///
/// Propagates any extraction failure.
pub fn extraction_payload_json(fd: libc::c_int, dest_dir: &std::path::Path) -> CoreResult<String> {
    let extracted = extract_so_from_zip(fd, dest_dir)?;
    let payload = ExtractionPayload {
        path: &extracted.path,
        sha256: &extracted.sha256,
        size: extracted.size,
    };
    Ok(serde_json::to_string(&payload).unwrap_or_default())
}
