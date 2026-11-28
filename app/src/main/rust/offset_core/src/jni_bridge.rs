//! JNI entry points exposed to Kotlin as `NativeLib`.
//!
//! Every function is wrapped in [`crate::android::catch_panics`] and converts
//! failures into a thrown Java exception or a sentinel value, so a malformed
//! APK can never abort the process.

use capstone::arch::arm64::ArchMode;
use capstone::arch::BuildsCapstone;
use capstone::arch::BuildsCapstoneEndian;
use capstone::{Capstone, Endian};
use jni::objects::{JObject, JString};
use jni::sys::{jboolean, jint, jlong, jstring};
use jni::JNIEnv;
use serde::Serialize;
use std::io::{BufWriter, Write};
use std::path::{Path, PathBuf};

use crate::android::{catch_panics, install};
use crate::categories::CategoryRules;
use crate::elf::MappedLibrary;
use crate::error::{CoreError, CoreResult};
use crate::internal::{analyze, analyze_with_writer, InternalOptions, InternalSummary};
use crate::memory::peak_resident_bytes;
use crate::patterns::PatternSet;
use crate::progress::JniProgressSink;
use crate::prologue::PrologueVerifier;
use crate::rtti::resolve_vtables_with_releaser;
use crate::scanner::{
    begin_scan, cancel_active_scan, scan_section_releasing_pages, verify_prologues_with_progress,
    ProgressSink, ScanOutcome,
};
use crate::sections::build_section_map;
use crate::shards::ShardWriter;
use crate::symbols::{build_symbol_report, read_named_symbols};
use crate::zip_extract::{extract_so_from_zip, read_apk_version_name};

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

/// Extracts every internal C++ function and virtual slot into an NDJSON file.
///
/// The library is mapped once for sections, symbols, vtables and the internal
/// passes, which removes the repeated open/mmap cycles that made the vtable
/// pass fail with a stale path. Only counts and the file path cross the JNI
/// boundary: the table itself can be tens of megabytes.
///
/// # Returns
///
/// A JSON object with `internal` (counts plus the NDJSON path) and
/// `peak_resident_bytes`.
///
/// # Errors
///
/// Throws `EngineFailure` when the library cannot be mapped or the options are
/// malformed, and `Cancelled` when the scan is cancelled.
#[no_mangle]
pub extern "system" fn Java_com_chimerateam_offsetextractor_data_native_NativeLib_analyzeInternalNative(
    mut env: JNIEnv,
    _this: JObject,
    path: JString,
    sha256: JString,
    options_json: JString,
    output_dir: JString,
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
    let options = match read_jstring(&mut env, &options_json, "internal options") {
        Some(value) => value,
        None => return std::ptr::null_mut(),
    };
    let destination = match read_jstring(&mut env, &output_dir, "output directory") {
        Some(value) => value,
        None => return std::ptr::null_mut(),
    };

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

    let outcome = catch_panics("analyzing internal offsets", move || {
        let sink: Option<&dyn ProgressSink> =
            sink.as_ref().map(|sink| sink.as_ref() as &dyn ProgressSink);
        analyze_internal_json(&library_path, &library_hash, &options, &destination, sink)
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

/// Reads `versionName` from the APK's binary manifest.
///
/// # Returns
///
/// A JSON object `{"versionName": "..."}`; the value is `null` when the
/// manifest is absent or unreadable.
#[no_mangle]
pub extern "system" fn Java_com_chimerateam_offsetextractor_data_native_NativeLib_readApkVersionNative(
    mut env: JNIEnv,
    _this: JObject,
    fd: jint,
) -> jstring {
    install();
    let outcome = catch_panics("reading the APK version", move || read_apk_version_json(fd));
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

/// Asks the scan currently in flight to stop.
///
/// The scan runs as a blocking call, so cancelling the Kotlin coroutine that
/// called it cannot reach the workers. This entry point is the other half: the
/// UI calls it from another thread and the workers observe the flag at their
/// next cancellation check. It is a no-op when no scan is running.
#[no_mangle]
pub extern "system" fn Java_com_chimerateam_offsetextractor_data_native_NativeLib_cancelScanNative(
    _env: JNIEnv,
    _this: JObject,
) {
    cancel_active_scan();
}

/// Releases the shared mapping of the last opened library.
///
/// The app calls this before deleting an extracted `.so` or clearing the
/// library cache. A mapping kept after the file is unlinked still serves its
/// pages, but a truncation or replacement faults with `SIGBUS`, which no
/// `catch_unwind` can turn into an error.
///
/// # Returns
///
/// `true` when a mapping was released.
#[no_mangle]
pub extern "system" fn Java_com_chimerateam_offsetextractor_data_native_NativeLib_clearMappedCacheNative(
    _env: JNIEnv,
    _this: JObject,
) -> jboolean {
    install();
    u8::from(crate::elf::MappedLibrary::clear_shared_cache())
}

/// The payload the scan entry point serialises.
#[derive(Debug, Serialize)]
struct ScanPayload {
    /// Everything the scan produced.
    #[serde(flatten)]
    outcome: ScanOutcome,
    /// Peak resident set of the process after the scan, in bytes.
    peak_resident_bytes: Option<u64>,
    /// The complete JNI set and its coverage, built from every source.
    jni: crate::jni::JniReport,
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
    let library = MappedLibrary::open_shared(std::path::Path::new(path), sha256.to_string())?;
    let map = build_section_map(library.bytes())?;
    let set = PatternSet::new(&specs)?;

    // Register the pass so a "Cancel" tap on another thread can reach its
    // workers; the guard deregisters it when the scan ends.
    let guard = begin_scan();
    let control = guard.control();
    // The mapping is offered wave by wave so the pages behind the scan can be
    // reclaimed; without it a 342 MB `.text` stays resident for the whole pass.
    let outcome = scan_section_releasing_pages(
        &map,
        library.bytes(),
        &set,
        control,
        sink,
        Some(library.as_ref()),
    )?;

    // The library's own symbol table is an independent witness: when a
    // signature's name is also an exported symbol the two addresses can be
    // compared, which turns a plausible offset into a checked one. It runs
    // before the prologue check because the prologue check discards candidates,
    // and a symbol-confirmed offset must not be thrown away first.
    let mut outcome = outcome;
    let symbols = crate::symbols::build_symbol_report(library.bytes())?;
    let summary = crate::verify::verify_against_symbols(&mut outcome, &symbols.symbols);

    // A byte signature only says "these bytes occur here"; decoding the bytes
    // at each match is what separates a function start from a coincidence. A
    // symbol-confirmed hit is kept even when it is a leaf with no frame.
    let verifier = PrologueVerifier::new()?;
    let mut outcome =
        verify_prologues_with_progress(outcome, &map, library.bytes(), &verifier, control, sink)?;
    outcome.verification = Some(summary);
    // Verification walked `.text` again; those pages are dead weight now too.
    library.advise_pages_droppable();

    // A pattern alone cannot be the source of truth: the exports and the
    // RegisterNatives tables name natives no pattern knows, so the three
    // sources are unioned and every pattern reports its own status.
    let natives = crate::jninatives::find_register_natives(library.bytes(), &map)?;
    let expected: Vec<String> = specs.iter().map(|(name, _)| name.clone()).collect();
    let jni = crate::jni::build_report(&outcome, &symbols.symbols, &natives, &expected);

    let payload = ScanPayload {
        outcome,
        peak_resident_bytes: peak_resident_bytes(),
        jni,
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
    let library = MappedLibrary::open_shared(std::path::Path::new(path), sha256.to_string())?;
    // The mapping is its own page releaser, so the relocation tables are handed
    // back to the kernel as soon as the index has read them.
    let map = resolve_vtables_with_releaser(library.bytes(), Some(library.as_ref()))?;
    // The type-info strings and `.data.rel.ro` slots are not needed after this
    // call; dropping them keeps the next pass from starting with them resident.
    library.advise_pages_droppable();
    Ok(serde_json::to_string(&map).unwrap_or_default())
}

/// Payload returned by the internal analysis entry point.
#[derive(Debug, Serialize)]
struct InternalPayload {
    /// Counts and the NDJSON path.
    internal: InternalSummary,
    /// Peak resident set of the process after the pass.
    peak_resident_bytes: Option<u64>,
}

/// Maps `path` once and runs the internal passes, exposed for host tests.
///
/// The NDJSON table is written to `output_dir/<sha256>.ndjson`. Only the counts
/// and that path are returned, so a table of hundreds of thousands of functions
/// never has to become a single JSON string.
///
/// # Errors
///
/// Propagates any mapping, section, vtable, symbol or I/O failure, and
/// [`CoreError::Cancelled`] when the pass is cancelled.
pub fn analyze_internal_json(
    path: &str,
    sha256: &str,
    options_json: &str,
    output_dir: &str,
    sink: Option<&dyn ProgressSink>,
) -> CoreResult<String> {
    let options: InternalOptions = serde_json::from_str(options_json).map_err(|error| {
        CoreError::InvalidPattern(format!("cannot parse the internal options: {error}"))
    })?;
    let library = MappedLibrary::open_shared(Path::new(path), sha256.to_string())?;
    let threads = crate::limits::configure_threads();
    crate::internal::report(
        sink,
        0,
        &format!("Engine: {threads} worker threads for this device"),
    );
    let map = build_section_map(library.bytes())?;
    let vtables = resolve_vtables_with_releaser(library.bytes(), Some(library.as_ref()))?;
    let symbols = read_named_symbols(library.bytes())?;
    if symbols.functions.len() + symbols.data.len() >= crate::symbols::MAX_NAMED_SYMBOLS {
        // A cap that drops names must say so: the table would otherwise look
        // complete while the tail of the symbol table is missing.
        crate::internal::report(
            sink,
            1,
            &format!(
                "Symbols: capped at {} entries, the rest of the symbol table was dropped",
                crate::symbols::MAX_NAMED_SYMBOLS
            ),
        );
    }

    std::fs::create_dir_all(output_dir)?;

    // Register the pass so a "Cancel" tap on another thread can reach it, the
    // same contract the signature scan uses.
    let guard = begin_scan();
    let control = guard.control();
    let summary = match options.categories.clone() {
        Some(rules_json) => {
            // With rules the engine classifies as it writes: one finished JSON
            // document per category, copied into the archive byte for byte.
            let rules = CategoryRules::parse(&rules_json.to_string())?;
            let dir = Path::new(output_dir).join(format!("categories-{sha256}"));
            let mut writer = ShardWriter::new(rules, &dir, options.source_header.clone())?;
            let mut summary = analyze_with_writer(
                library.bytes(),
                &map,
                &vtables,
                &symbols.functions,
                &symbols.data,
                &options,
                control,
                sink,
                Some(library.as_ref()),
                &mut writer,
            )?;
            let class_map = dir.join("classmap.json");
            writer.write_class_map(&class_map)?;
            summary.shards = writer.parts();
            summary.class_map_path = class_map.to_string_lossy().into_owned();
            summary.categories_applied = true;
            // The category documents are the table in this mode. Reporting an
            // empty `path` and `tableBytes: 0` made Kotlin reject a finished
            // pass as "the internal offsets file is gone (. 0 bytes)", so the
            // summary carries the directory and the real total size.
            summary.path = dir.to_string_lossy().into_owned();
            summary.table_bytes = summary.shards.iter().map(|part| part.bytes).sum();
            if summary.table_bytes == 0 {
                return Err(CoreError::Io(format!(
                    "the category writer finished without writing any bytes to {}",
                    dir.display()
                )));
            }
            summary
        }
        None => {
            let target = Path::new(output_dir).join(format!("{sha256}.ndjson"));
            let file = std::fs::File::create(&target)?;
            let mut writer = BufWriter::new(file);
            let mut summary = analyze(
                library.bytes(),
                &map,
                &vtables,
                &symbols.functions,
                &symbols.data,
                &options,
                control,
                sink,
                Some(library.as_ref()),
                &mut writer,
            )?;
            writer.flush()?;
            // The table is the only copy of the internal offsets, and Kotlin
            // refuses to export an archive without it, so make the bytes
            // durable and report what actually landed on disk.
            writer.get_ref().sync_all()?;
            summary.table_bytes = std::fs::metadata(&target)?.len();
            summary.path = target.to_string_lossy().into_owned();
            summary
        }
    };
    library.advise_pages_droppable();

    let payload = InternalPayload {
        internal: summary,
        peak_resident_bytes: peak_resident_bytes(),
    };
    Ok(serde_json::to_string(&payload).unwrap_or_default())
}

/// Reads `versionName` from the APK behind `fd`, exposed for host tests.
///
/// # Errors
///
/// Propagates a descriptor or archive failure.
pub fn read_apk_version_json(fd: libc::c_int) -> CoreResult<String> {
    let version = read_apk_version_name(fd)?;
    Ok(serde_json::to_string(&serde_json::json!({ "versionName": version })).unwrap_or_default())
}

/// Maps `path` and serialises its symbols, exposed for host tests.
///
/// # Errors
///
/// Propagates any mapping, section or symbol failure.
pub fn describe_symbols_json(path: &str, sha256: &str) -> CoreResult<String> {
    let library = MappedLibrary::open_shared(std::path::Path::new(path), sha256.to_string())?;
    let report = build_symbol_report(library.bytes())?;
    Ok(serde_json::to_string(&report).unwrap_or_default())
}

/// Maps `path` and serialises its section layout, exposed for host tests.
///
/// # Errors
///
/// Propagates any mapping or section-parsing failure.
pub fn describe_sections_json(path: &str, sha256: &str) -> CoreResult<String> {
    let library = MappedLibrary::open_shared(std::path::Path::new(path), sha256.to_string())?;
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
        // A failed allocation is reported as an out-of-memory condition so the
        // UI can show the low-memory message instead of the process dying.
        CoreError::OutOfMemory(_) => "out_of_memory",
    };
    throw_engine_failure(env, kind, &error.to_string());
}

/// Throws the engine's failure class with a `kind: detail` message.
///
/// The JVM's `ThrowNew` resolves `<init>(Ljava/lang/String;)V` on the class
/// named by [`ENGINE_EXCEPTION_CLASS`]; that constructor must exist or the JVM
/// raises a `NoSuchMethodError` instead and the typed failure is lost. The
/// Kotlin class provides it, and `app/proguard-rules.pro` keeps it.
fn throw_engine_failure(env: &mut JNIEnv, kind: &str, detail: &str) {
    let message = format!("{kind}: {detail}");
    if env.throw_new(ENGINE_EXCEPTION_CLASS, message).is_err() {
        // Throwing failed, which itself leaves a pending exception. Clearing it
        // is the only safe move: returning normally with an exception pending
        // is undefined behaviour.
        let _ = env.exception_clear();
    }
}

/// Maximum number of bytes one disassembly request may decode.
pub const MAX_DISASSEMBLY_BYTES: u64 = 512;

/// One decoded instruction, with the reference it builds when it has one.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct DisassembledInstruction {
    address: String,
    bytes: String,
    mnemonic: String,
    operands: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    annotation: Option<String>,
}

/// The payload the disassembly entry point serialises.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct DisassemblyPayload {
    address: String,
    byte_count: u64,
    instructions: Vec<DisassembledInstruction>,
}

/// One vtable that holds the inspected address.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct InspectVtable {
    class: String,
    vtable: String,
    slots: Vec<u32>,
    base_classes: Vec<String>,
}

/// The payload the address-inspection entry point serialises.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct InspectPayload {
    address: String,
    function_start: Option<String>,
    function_size: u64,
    first_call: Option<String>,
    callers: Vec<String>,
    callees: Vec<String>,
    data_refs: Vec<String>,
    strings: Vec<String>,
    vtables: Vec<InspectVtable>,
}

/// Disassembles up to [`MAX_DISASSEMBLY_BYTES`] of `.text` at `address`.
///
/// `BL` targets and the addresses built by `ADRP`/`ADR`+`ADD`/`LDR` are
/// annotated so the inspector can show what each instruction reaches without a
/// second query.
///
/// # Errors
///
/// Returns [`CoreError::InvalidElf`] when the address is outside `.text` or the
/// range cannot be decoded.
pub fn disassemble_range_json(
    path: &str,
    sha256: &str,
    address: u64,
    byte_count: u64,
) -> CoreResult<String> {
    let library = MappedLibrary::open_shared(Path::new(path), sha256.to_string())?;
    let image = library.bytes();
    let map = build_section_map(image)?;
    if !crate::internal::is_executable(&map, address) {
        return Err(CoreError::InvalidElf(format!(
            "0x{address:x} is not inside .text"
        )));
    }
    let byte_count = byte_count.clamp(4, MAX_DISASSEMBLY_BYTES) & !3;
    let file_offset = crate::internal::file_offset_of(&map, address)
        .ok_or_else(|| CoreError::InvalidElf(format!("0x{address:x} has no file offset")))?
        as usize;
    let code = image
        .get(file_offset..file_offset + byte_count as usize)
        .ok_or_else(|| CoreError::InvalidElf("the disassembly range runs past .text".into()))?;

    let capstone = Capstone::new()
        .arm64()
        .mode(ArchMode::Arm)
        .endian(Endian::Little)
        .detail(false)
        .build()
        .map_err(|error| {
            CoreError::InvalidElf(format!("cannot initialise the disassembler: {error}"))
        })?;
    let decoded = capstone
        .disasm_all(code, address)
        .map_err(|error| CoreError::InvalidElf(format!("cannot decode 0x{address:x}: {error}")))?;

    let end = address.saturating_add(byte_count);
    let mut page: [Option<u64>; 32] = [None; 32];
    let mut instructions: Vec<DisassembledInstruction> = Vec::with_capacity(decoded.len());
    for instruction in decoded.iter() {
        let offset = (instruction.address() - address) as usize;
        let Some(bytes) = code.get(offset..offset + 4) else {
            break;
        };
        let word = u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]);
        let annotation = annotate_instruction(word, instruction.address(), address, end, &mut page);
        instructions.push(DisassembledInstruction {
            address: format!("0x{:x}", instruction.address()),
            bytes: bytes
                .iter()
                .map(|byte| format!("{byte:02x}"))
                .collect::<Vec<_>>()
                .join(" "),
            mnemonic: instruction.mnemonic().unwrap_or("").to_string(),
            operands: instruction.op_str().unwrap_or("").to_string(),
            annotation,
        });
    }
    let payload = DisassemblyPayload {
        address: format!("0x{address:x}"),
        byte_count,
        instructions,
    };
    Ok(serde_json::to_string(&payload).unwrap_or_default())
}

/// Builds a human-readable annotation for one instruction.
fn annotate_instruction(
    word: u32,
    pc: u64,
    range_start: u64,
    range_end: u64,
    page: &mut [Option<u64>; 32],
) -> Option<String> {
    let rd = (word & 31) as usize;
    let rn = ((word >> 5) & 31) as usize;
    if word & 0xFC00_0000 == 0x9400_0000 {
        let target = crate::xref::branch_target(pc, word & 0x03FF_FFFF, 26);
        return Some(format!("call 0x{target:x}"));
    }
    if word & 0xFC00_0000 == 0x1400_0000 {
        let target = crate::xref::branch_target(pc, word & 0x03FF_FFFF, 26);
        if target < range_start || target >= range_end {
            return Some(format!("tail 0x{target:x}"));
        }
        return None;
    }
    if word & 0x9F00_0000 == 0x9000_0000 {
        page[rd] = Some(adrp_page(pc, word));
        return None;
    }
    if word & 0x9F00_0000 == 0x1000_0000 {
        let immlo = ((word >> 29) & 0x3) as i64;
        let immhi = ((word >> 5) & 0x7_FFFF) as i64;
        let target = (pc as i64).wrapping_add(sign_extend((immhi << 2) | immlo, 21)) as u64;
        page[rd] = Some(target);
        return Some(format!("ref 0x{target:x}"));
    }
    if word & 0x7F80_0000 == 0x1100_0000 {
        if let Some(base) = page[rn] {
            let target = base.wrapping_add(((word >> 10) & 0xFFF) as u64);
            page[rd] = Some(target);
            return Some(format!("ref 0x{target:x}"));
        }
        page[rd] = None;
        return None;
    }
    if word & 0x3B00_0000 == 0x3900_0000 {
        let scale = 1u64 << ((word >> 30) & 0x3);
        let target = page[rn].map(|base| base.wrapping_add(((word >> 10) & 0xFFF) as u64 * scale));
        if (word >> 22) & 0x3 != 0 {
            page[rd] = None;
        }
        return target.map(|target| format!("ref 0x{target:x}"));
    }
    if word & 0x7FE0_FFE0 == 0x2A00_03E0 {
        let rm = ((word >> 16) & 31) as usize;
        page[rd] = page[rm];
        return None;
    }
    if crate::xref::writes_gpr(word) {
        page[rd] = None;
    }
    None
}

/// Computes the page an `ADRP` names.
fn adrp_page(pc: u64, word: u32) -> u64 {
    let immlo = ((word >> 29) & 0x3) as i64;
    let immhi = ((word >> 5) & 0x7_FFFF) as i64;
    let delta = sign_extend(((immhi << 2) | immlo) << 12, 33);
    ((pc & !0xFFF) as i64).wrapping_add(delta) as u64
}

fn sign_extend(value: i64, bits: u32) -> i64 {
    let shift = 64 - bits;
    (value << shift) >> shift
}

/// Inspects one address: its function, callers, callees, data references,
/// strings and the vtables that hold it.
///
/// This is a user-initiated query, so it may walk the whole library; nothing in
/// the normal scan path calls it.
///
/// # Errors
///
/// Propagates mapping, section and RTTI failures.
pub fn inspect_address_json(path: &str, sha256: &str, address: u64) -> CoreResult<String> {
    let library = MappedLibrary::open_shared(Path::new(path), sha256.to_string())?;
    let image = library.bytes();
    let map = build_section_map(image)?;
    let control = crate::scanner::ScanControl::new();
    let (functions, _, _) = crate::internal::collect_functions(&map, image, &control)?;

    let index = functions.partition_point(|(start, _)| *start <= address);
    let function = index.checked_sub(1).and_then(|index| {
        let (start, size) = functions[index];
        (address < start.saturating_add(size)).then_some((start, size))
    });

    let (callees, data_refs, first_call) = if let Some((start, size)) = function {
        let table = crate::xref::scan_one(&map, image, start, size)?;
        let entry = &table.functions[0];
        (
            table.calls_of(entry).to_vec(),
            table.data_refs_of(entry).to_vec(),
            entry.first_call_site,
        )
    } else {
        (Vec::new(), Vec::new(), None)
    };

    let callers = crate::xref::callers_of(&map, image, &functions, address, &control)?;
    let strings: Vec<String> = data_refs
        .iter()
        .filter_map(|reference| read_c_string(&map, image, *reference))
        .take(64)
        .collect();
    let vtables = inspect_vtables(&map, image, address)?;

    let payload = InspectPayload {
        address: format!("0x{address:x}"),
        function_start: function.map(|(start, _)| format!("0x{start:x}")),
        function_size: function.map(|(_, size)| size).unwrap_or(0),
        first_call: first_call.map(|call| format!("0x{:x}@0x{:x}", call.target, call.site)),
        callers: callers.iter().map(|value| format!("0x{value:x}")).collect(),
        callees: callees
            .iter()
            .take(256)
            .map(|value| format!("0x{value:x}"))
            .collect(),
        data_refs: data_refs
            .iter()
            .take(256)
            .map(|value| format!("0x{value:x}"))
            .collect(),
        strings,
        vtables,
    };
    Ok(serde_json::to_string(&payload).unwrap_or_default())
}

/// Finds the vtables whose slot array contains `address`.
fn inspect_vtables(
    map: &crate::sections::SectionMap,
    image: &[u8],
    address: u64,
) -> CoreResult<Vec<InspectVtable>> {
    const MAX_SLOTS: u32 = 512;
    const VTABLE_PREFIX_BYTES: u64 = 16;
    let relocation = crate::rtti::RelocationIndex::build(image)?;
    let vtables = crate::rtti::resolve_vtables(image)?;
    let bases = crate::internal::resolve_bases(map, image, &relocation, &vtables);

    let mut out: Vec<InspectVtable> = Vec::new();
    for vtable in &vtables.all {
        let direct = vtable.vtable_address == address;
        let mut slots: Vec<u32> = Vec::new();
        let first = vtable.vtable_address.saturating_add(VTABLE_PREFIX_BYTES);
        for slot in 0..MAX_SLOTS {
            let slot_address = first.saturating_add(u64::from(slot) * 8);
            let relocated = relocation.target_of(slot_address);
            let target = relocated.or_else(|| read_u64_at(map, image, slot_address));
            match target {
                None => break,
                Some(0) if relocated.is_none() => break,
                Some(0) => continue,
                Some(value) => {
                    if value == address {
                        slots.push(slot);
                    }
                }
            }
        }
        if direct || !slots.is_empty() {
            let base_classes = bases
                .get(&vtable.mangled)
                .map(|list| list.iter().map(|base| base.name.clone()).collect())
                .unwrap_or_default();
            out.push(InspectVtable {
                class: vtable.name.clone(),
                vtable: format!("0x{:x}", vtable.vtable_address),
                slots,
                base_classes,
            });
        }
    }
    Ok(out)
}

/// Reads an 8-byte little-endian word at a virtual address.
fn read_u64_at(map: &crate::sections::SectionMap, image: &[u8], address: u64) -> Option<u64> {
    let offset = crate::internal::file_offset_of(map, address)? as usize;
    let bytes = image.get(offset..offset + 8)?;
    Some(u64::from_le_bytes(bytes.try_into().ok()?))
}

/// Reads a printable NUL-terminated string at a virtual address.
fn read_c_string(map: &crate::sections::SectionMap, image: &[u8], address: u64) -> Option<String> {
    const MIN_LENGTH: usize = 4;
    const MAX_LENGTH: usize = 128;
    let offset = crate::internal::file_offset_of(map, address)? as usize;
    let bytes = image.get(offset..)?;
    let end = bytes.iter().take(MAX_LENGTH).position(|byte| *byte == 0)?;
    if end < MIN_LENGTH {
        return None;
    }
    let text = &bytes[..end];
    if !text
        .iter()
        .all(|byte| (0x20..=0x7e).contains(byte) || *byte == b'\t')
    {
        return None;
    }
    String::from_utf8(text.to_vec()).ok()
}

/// Disassembles a `.text` range for the inspector.
#[no_mangle]
pub extern "system" fn Java_com_chimerateam_offsetextractor_data_native_NativeLib_disassembleRangeNative(
    mut env: JNIEnv,
    _this: JObject,
    path: JString,
    sha256: JString,
    address: jlong,
    byte_count: jint,
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
    let outcome = catch_panics("disassembling a range", move || {
        disassemble_range_json(
            &library_path,
            &library_hash,
            address as u64,
            byte_count.max(0) as u64,
        )
    });
    json_result(&mut env, outcome)
}

/// Inspects one address for the inspector pane.
#[no_mangle]
pub extern "system" fn Java_com_chimerateam_offsetextractor_data_native_NativeLib_inspectAddressNative(
    mut env: JNIEnv,
    _this: JObject,
    path: JString,
    sha256: JString,
    address: jlong,
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
    let outcome = catch_panics("inspecting an address", move || {
        inspect_address_json(&library_path, &library_hash, address as u64)
    });
    json_result(&mut env, outcome)
}

/// Converts a fallible JSON result into a Java string or a thrown exception.
fn json_result(env: &mut JNIEnv, outcome: Result<CoreResult<String>, String>) -> jstring {
    match outcome {
        Ok(Ok(json)) => match env.new_string(json) {
            Ok(string) => string.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Ok(Err(core_error)) => {
            throw_core_error(env, &core_error);
            std::ptr::null_mut()
        }
        Err(panic_message) => {
            throw_engine_failure(env, "panic", &panic_message);
            std::ptr::null_mut()
        }
    }
}

/// Maps `path` and serialises its metadata, exposed for host tests.
///
/// # Errors
///
/// Propagates any mapping or parsing failure.
pub fn map_library_json(path: &str, sha256: &str) -> CoreResult<String> {
    let library = MappedLibrary::open_shared(std::path::Path::new(path), sha256.to_string())?;
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
