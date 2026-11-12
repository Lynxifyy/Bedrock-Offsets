//! Tests for the shipped signature pack.
//!
//! The pack is the contract between the Python tooling and the scanner, so two
//! things are checked:
//!
//! * **Always** — the shipped pack parses, every entry is a well-formed JNI
//!   symbol with a usable byte pattern, and no two entries share a pattern. This
//!   runs on every machine, because it needs no library.
//! * **When `MCPE_SO` points at a real `libminecraftpe.so`** — every signature
//!   resolves to exactly one address, every offset survives the prologue check,
//!   and every offset agrees with the library's own symbol table. That is the
//!   property the pack exists to provide, and it can only be measured against a
//!   real library.

use std::path::PathBuf;

use offset_core::elf::MappedLibrary;
use offset_core::patterns::PatternSet;
use offset_core::prologue::PrologueVerifier;
use offset_core::scanner::{scan_section, verify_prologues, ScanControl};
use offset_core::sections::build_section_map;
use offset_core::verify::{verify_against_symbols, VerificationStatus};

/// The pack bundled into the APK, relative to the crate root.
const PACK_PATH: &str = "../../assets/patterns.json";

/// Loads the shipped pack as `(name, pattern)` pairs.
fn shipped_pack() -> Vec<(String, String)> {
    let text = std::fs::read_to_string(PACK_PATH).expect("the shipped pack must be readable");
    let document: serde_json::Value =
        serde_json::from_str(&text).expect("the shipped pack must be valid JSON");
    document["patterns"]
        .as_array()
        .expect("the pack must have a patterns array")
        .iter()
        .map(|entry| {
            (
                entry["name"].as_str().expect("a name").to_string(),
                entry["pattern"].as_str().expect("a pattern").to_string(),
            )
        })
        .collect()
}

/// The library to measure against, when the environment provides one.
fn real_library() -> Option<PathBuf> {
    let path = PathBuf::from(std::env::var("MCPE_SO").ok()?);
    path.is_file().then_some(path)
}

#[test]
fn the_shipped_pack_is_well_formed() {
    let pack = shipped_pack();
    assert!(!pack.is_empty(), "the pack must ship signatures");

    for (name, pattern) in &pack {
        assert!(
            name.starts_with("Java_"),
            "{name} is not a JNI entry point, so it cannot be cross-checked"
        );
        assert!(
            name.bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_'),
            "{name} is not a plain identifier"
        );

        let bytes = pattern.split_whitespace().count();
        assert!(
            (8..=64).contains(&bytes),
            "{name} has {bytes} bytes, outside the 8..=64 window"
        );
        assert!(
            pattern
                .split_whitespace()
                .all(|byte| byte.len() == 2 && byte.chars().all(|c| c.is_ascii_hexdigit())),
            "{name} has a malformed byte: {pattern}"
        );
    }

    // A duplicated pattern would make one entry unreachable, because the
    // scanner reports a match under the first name that compiled it.
    let mut patterns: Vec<&String> = pack.iter().map(|(_, pattern)| pattern).collect();
    patterns.sort();
    let before = patterns.len();
    patterns.dedup();
    assert_eq!(
        before,
        patterns.len(),
        "the pack contains a duplicate pattern"
    );
}

#[test]
fn the_shipped_pack_compiles() {
    let pack = shipped_pack();
    let set = PatternSet::new(&pack).expect("the shipped pack must compile");
    assert_eq!(set.patterns().len(), pack.len());
}

#[test]
fn every_signature_resolves_to_a_verified_offset() {
    let Some(path) = real_library() else {
        eprintln!("MCPE_SO is not set to a real library; skipping the live measurement");
        return;
    };

    let pack = shipped_pack();
    let library = MappedLibrary::open(&path, "real".to_string()).expect("map the library");
    let map = build_section_map(library.bytes()).expect("build the section map");
    let set = PatternSet::new(&pack).expect("compile the pack");

    let outcome = scan_section(&map, library.bytes(), &set, &ScanControl::new()).expect("scan");
    // A unique signature finds one address; anything else means the pack has
    // drifted from the library it was derived from.
    assert_eq!(
        outcome.hits.len(),
        pack.len(),
        "each signature must match exactly once"
    );
    assert!(!outcome.truncated, "a unique pack cannot be capped");

    let symbols = offset_core::symbols::build_symbol_report(library.bytes()).expect("symbols");
    let mut outcome = outcome;
    let summary = verify_against_symbols(&mut outcome, &symbols.symbols);
    let verified = verify_prologues(
        outcome,
        &map,
        library.bytes(),
        &PrologueVerifier::new().expect("capstone"),
        &ScanControl::new(),
    )
    .expect("verify");

    assert_eq!(
        verified.hits.len(),
        pack.len(),
        "no signature may be discarded: the symbol table confirms them all"
    );
    assert_eq!(verified.discarded, 0);
    assert_eq!(
        summary.conflicts, 0,
        "no signature may disagree with its symbol"
    );
    assert!(summary.symbols_available);
    assert_eq!(summary.verified, pack.len());
    assert!((summary.confidence() - 1.0).abs() < f64::EPSILON);
    assert!(verified
        .hits
        .iter()
        .all(|hit| hit.verification_status == VerificationStatus::Verified));
}
