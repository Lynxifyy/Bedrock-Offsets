//! The on-demand inspector entry points: disassembly and address inspection.
//!
//! These are the two JNI calls the reverse-engineering workspace makes when a
//! row is selected. They run on the synthetic fixture so the expected
//! instructions, string and vtable slot are all known addresses.

use std::path::PathBuf;

#[path = "support/mod.rs"]
mod support;

use offset_core::jni_bridge::{disassemble_range_json, inspect_address_json};

struct Scratch {
    root: PathBuf,
}

impl Scratch {
    fn new(label: &str) -> Self {
        let root = std::env::temp_dir().join(format!(
            "offset-core-inspector-{label}-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .expect("clock")
                .as_nanos()
        ));
        std::fs::create_dir_all(&root).expect("scratch dir");
        Self { root }
    }

    fn library(&self) -> PathBuf {
        let path = self.root.join("libfixture.so");
        std::fs::write(&path, support::build_library()).expect("write fixture");
        path
    }
}

impl Drop for Scratch {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.root);
    }
}

#[test]
fn disassembly_decodes_the_planted_prologue() {
    let scratch = Scratch::new("disasm");
    let library = scratch.library();
    let json = disassemble_range_json(
        library.to_str().expect("utf8 path"),
        "fixture-sha",
        support::TEXT_ADDRESS,
        64,
    )
    .expect("disassembly");
    let payload: serde_json::Value = serde_json::from_str(&json).expect("json");

    assert_eq!(
        payload["address"].as_str(),
        Some(format!("0x{:x}", support::TEXT_ADDRESS).as_str())
    );
    let instructions = payload["instructions"].as_array().expect("instructions");
    assert!(!instructions.is_empty(), "no instruction was decoded");
    assert_eq!(instructions[0]["mnemonic"].as_str(), Some("stp"));
    assert!(
        instructions[0]["operands"]
            .as_str()
            .expect("operands")
            .contains("sp"),
        "the frame push must name the stack pointer: {}",
        instructions[0]
    );
}

#[test]
fn disassembly_annotates_the_anchor_reference() {
    let scratch = Scratch::new("disasm-anchor");
    let library = scratch.library();
    let json = disassemble_range_json(
        library.to_str().expect("utf8 path"),
        "fixture-sha",
        support::ANCHOR_FUNCTION,
        64,
    )
    .expect("disassembly");
    let payload: serde_json::Value = serde_json::from_str(&json).expect("json");
    let instructions = payload["instructions"].as_array().expect("instructions");

    let annotated = instructions
        .iter()
        .filter_map(|instruction| instruction["annotation"].as_str())
        .any(|annotation| annotation.starts_with("ref 0x"));
    assert!(
        annotated,
        "the ADRP+ADD pair must be annotated: {instructions:?}"
    );
}

#[test]
fn inspection_reports_the_function_and_its_anchor_string() {
    let scratch = Scratch::new("inspect-string");
    let library = scratch.library();
    let address = support::ANCHOR_FUNCTION;
    let json = inspect_address_json(library.to_str().expect("utf8 path"), "fixture-sha", address)
        .expect("inspection");
    let payload: serde_json::Value = serde_json::from_str(&json).expect("json");

    assert_eq!(
        payload["functionStart"].as_str(),
        Some(format!("0x{address:x}").as_str())
    );
    assert_eq!(
        payload["functionSize"].as_u64(),
        Some(support::FRAME_BYTES as u64)
    );
    let strings = payload["strings"].as_array().expect("strings");
    assert!(
        strings
            .iter()
            .any(|value| value.as_str() == Some(support::ANCHOR_STRING)),
        "the anchor string must be recovered: {strings:?}"
    );
}

#[test]
fn inspection_reports_the_vtable_that_holds_the_function() {
    let scratch = Scratch::new("inspect-vtable");
    let library = scratch.library();
    // Function 0 is slot 0 of the first planted class.
    let json = inspect_address_json(
        library.to_str().expect("utf8 path"),
        "fixture-sha",
        support::FIRST_FUNCTION,
    )
    .expect("inspection");
    let payload: serde_json::Value = serde_json::from_str(&json).expect("json");
    let vtables = payload["vtables"].as_array().expect("vtables");

    let actor = vtables
        .iter()
        .find(|vtable| vtable["class"].as_str() == Some("FixtureActor"))
        .unwrap_or_else(|| panic!("FixtureActor vtable missing: {vtables:?}"));
    let slots: Vec<u64> = actor["slots"]
        .as_array()
        .expect("slots")
        .iter()
        .map(|slot| slot.as_u64().expect("slot index"))
        .collect();
    assert!(slots.contains(&0), "slot 0 must be reported: {slots:?}");
}
