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
use crate::memory::peak_resident_bytes;
use crate::patterns::PatternSet;
use crate::progress::JniProgressSink;
use crate::prologue::PrologueVerifier;
use crate::rtti::resolve_vtables;
use crate::scanner::{
    scan_section_with_progress, verify_prologues_with_progress, ProgressSink, ScanControl,
    ScanOutcome,
};
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

/// Resolves the RTTI vtables of a library.
///
/// # Returns
///
/// A JSON object describing how the vtables were found, how many `type_info`
/// pointers were seen, how many relocations were read, and every resolved
/// vtable keyed by class name.
///
/// # Errors
///
/// Throws `EngineFailure` when the library cannot be mapped or parsed.
#[no_mangle]
pub extern "system" fn Java_com_chimerateam_offsetextractor_data_native_NativeLib_resolveVtablesNative(
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

    let outcome = catch_panics("resolving vtables", move || {
        resolve_vtables_json(&library_path, &library_hash)
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

/// Scans the `.text` section of a library for the supplied patterns.
///
/// # Returns
///
/// A JSON object with the section, its size, the chunk and worker counts, the
/// peak resident set reached, and every hit.
///
/// # Errors
///
/// Throws `EngineFailure` when the library cannot be mapped, has no usable
/// `.text` section, a pattern is malformed, or the scan was cancelled.
#[no_mangle]
pub extern "system" fn Java_com_chimerateam_offsetextractor_data_native_NativeLib_scanTextNative(
    mut env: JNIEnv,
    _this: JObject,
    path: JString,
    sha256: JString,
    patterns_json: JString,
    callback: JObject,
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
    let specs = match read_jstring(&mut env, &patterns_json, "patterns") {
        Some(value) => value,
        None => return std::ptr::null_mut(),
    };

    // A null callback is allowed: the scan then runs without progress reports.
    let sink = if callback.is_null() {
        None
    } else {
        match JniProgressSink::new(&mut env, &callback) {
            Ok(sink) => Some(sink),
            Err(detail) => {
                throw_engine_failure(&mut env, "invalid_descriptor", &detail);
                return std::ptr::null_mut();
            }
        }
    };

    let outcome = catch_panics("scanning text", move || {
        let sink: Option<&dyn ProgressSink> =
            sink.as_ref().map(|sink| sink.as_ref() as &dyn ProgressSink);
        scan_text_json_with_progress(&library_path, &library_hash, &specs, sink)
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

/// The payload the scan entry point serialises.
#[derive(Debug, Serialize)]
struct ScanPayload {
    /// Everything the scan produced.
    #[serde(flatten)]
    outcome: ScanOutcome,
    /// Peak resident set of the process after the scan, in bytes.
    peak_resident_bytes: Option<u64>,
}

/// Maps `path`, scans `.text` for `patterns_json`, and serialises the result.
///
/// `patterns_json` is a JSON array of `{"name": ..., "pattern": ...}` objects.
///
/// # Errors
///
/// Propagates any mapping, section, pattern or cancellation failure.
pub fn scan_text_json(path: &str, sha256: &str, patterns_json: &str) -> CoreResult<String> {
    scan_text_json_with_progress(path, sha256, patterns_json, None)
}

/// Maps `path`, scans `.text`, and serialises the result, reporting progress.
///
/// # Errors
///
/// Same as [`scan_text_json`].
pub fn scan_text_json_with_progress(
    path: &str,
    sha256: &str,
    patterns_json: &str,
    sink: Option<&dyn ProgressSink>,
) -> CoreResult<String> {
    let specs = parse_pattern_specs(patterns_json)?;
    let library = MappedLibrary::open(std::path::Path::new(path), sha256.to_string())?;
    let map = build_section_map(library.bytes())?;
    let set = PatternSet::new(&specs)?;

    let control = ScanControl::new();
    let outcome = scan_section_with_progress(&map, library.bytes(), &set, &control, sink)?;

    // A byte signature only says "these bytes occur here"; decoding the bytes
    // at each match is what separates a function start from a coincidence.
    let verifier = PrologueVerifier::new()?;
    let mut outcome =
        verify_prologues_with_progress(outcome, &map, library.bytes(), &verifier, &control, sink)?;

    // The library's own symbol table is an independent witness: when a
    // signature's name is also an exported symbol the two addresses can be
    // compared, which turns a plausible offset into a checked one.
    let symbols = crate::symbols::build_symbol_report(library.bytes())?;
    outcome.verification = Some(crate::verify::verify_against_symbols(
        &mut outcome,
        &symbols.symbols,
    ));

    let payload = ScanPayload {
        outcome,
        peak_resident_bytes: peak_resident_bytes(),
    };
    Ok(serde_json::to_string(&payload).unwrap_or_default())
}

/// One entry of the pattern array handed in from Kotlin.
#[derive(Debug, serde::Deserialize)]
struct PatternSpec {
    name: String,
    pattern: String,
}

/// Parses the pattern array supplied by the caller.
///
/// # Errors
///
/// Returns [`CoreError::InvalidPattern`] when the payload is not a JSON array of
/// named patterns.
fn parse_pattern_specs(patterns_json: &str) -> CoreResult<Vec<(String, String)>> {
    let specs: Vec<PatternSpec> = serde_json::from_str(patterns_json).map_err(|error| {
        CoreError::InvalidPattern(format!("cannot parse the pattern list: {error}"))
    })?;
    Ok(specs
        .into_iter()
        .map(|spec| (spec.name, spec.pattern))
        .collect())
}

/// Maps `path` and serialises its RTTI vtables, exposed for host tests.
///
/// # Errors
///
/// Propagates any mapping or vtable-resolution failure.
pub fn resolve_vtables_json(path: &str, sha256: &str) -> CoreResult<String> {
    let library = MappedLibrary::open(std::path::Path::new(path), sha256.to_string())?;
    let map = resolve_vtables(library.bytes())?;
    Ok(serde_json::to_string(&map).unwrap_or_default())
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
