//! The `.rodata` string-anchor pass, which 26.60 needed and the pack never fed.
//!
//! A string anchor is how the engine names a function that no symbol and no
//! vtable slot names: a unique string in `.rodata`, the `ADRP`+`ADD` pair that
//! computes its address inside `.text`, and the function that contains the
//! pair. It is the only path left for the ECS component types, which are
//! strings in the binary but not RTTI classes (finding C4).
//!
//! Before this test the pass had no test and no data, so a wrong decode would
//! have shown up as "0 anchors, 0 ambiguous, 0 not found" — indistinguishable
//! from a correct run over an empty anchor list.

use std::path::PathBuf;

#[path = "support/mod.rs"]
mod support;

use offset_core::jni_bridge::analyze_internal_json;

struct Scratch {
    root: PathBuf,
}

impl Scratch {
    fn new(label: &str) -> Self {
        let root = std::env::temp_dir().join(format!(
            "offset-core-anchor-{label}-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .expect("clock")
                .as_nanos()
        ));
        std::fs::create_dir_all(&root).expect("scratch dir");
        Self { root }
    }

    fn join(&self, name: &str) -> PathBuf {
        self.root.join(name)
    }
}

impl Drop for Scratch {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.root);
    }
}

fn run(scratch: &Scratch, anchors: serde_json::Value) -> serde_json::Value {
    let library = scratch.join("libfixture.so");
    std::fs::write(&library, support::build_library()).expect("write fixture");
    let options = serde_json::json!({
        "includeAllFunctions": true,
        "generateSignatures": false,
        "stringAnchors": anchors
    })
    .to_string();
    let payload = analyze_internal_json(
        library.to_str().expect("utf8 path"),
        "fixture-sha",
        &options,
        scratch.join("out").to_str().expect("utf8 path"),
        None,
    )
    .expect("internal analysis");
    serde_json::from_str(&payload).expect("payload json")
}

fn function_row(payload: &serde_json::Value, address: u64) -> serde_json::Value {
    let path = payload["internal"]["path"].as_str().expect("path");
    let table = std::fs::read_to_string(path).expect("table");
    for line in table.lines() {
        if !line.starts_with("{\"type\":\"function\"") {
            continue;
        }
        let record: serde_json::Value = serde_json::from_str(line).expect("row");
        if record["function"]["address"].as_str() == Some(&format!("0x{address:x}")) {
            return record["function"].clone();
        }
    }
    panic!("no row for 0x{address:x}");
}

#[test]
fn an_anchor_names_the_function_that_computes_its_address() {
    let scratch = Scratch::new("an_anchor_names_the_function_that_computes_its_address");
    let payload = run(
        &scratch,
        serde_json::json!([
            {"name": "FixtureComponentTable", "string": support::ANCHOR_STRING}
        ]),
    );

    let row = function_row(&payload, support::ANCHOR_FUNCTION);
    assert_eq!(row["name"].as_str(), Some("FixtureComponentTable"), "{row}");
    assert_eq!(row["nameSource"].as_str(), Some("string"));
    let summary = &payload["internal"];
    assert_eq!(summary["ambiguous"].as_u64(), Some(0));
    assert_eq!(summary["notFound"].as_u64(), Some(0));
}

#[test]
fn an_anchor_whose_string_is_absent_is_counted_not_ignored() {
    let scratch = Scratch::new("an_anchor_whose_string_is_absent_is_counted_not_ignored");
    let payload = run(
        &scratch,
        serde_json::json!([
            {"name": "Gone", "string": "NoSuchStringInThisFixture"}
        ]),
    );

    let summary = &payload["internal"];
    assert_eq!(summary["notFound"].as_u64(), Some(1), "{summary}");
    assert_eq!(summary["ambiguous"].as_u64(), Some(0));
}

#[test]
fn a_string_two_functions_compute_is_ambiguous_rather_than_guessed() {
    let scratch = Scratch::new("ambiguous");
    let library = scratch.join("libfixture.so");
    std::fs::write(
        &library,
        support::build_library_with_two_anchor_references(),
    )
    .expect("write fixture");
    let options = serde_json::json!({
        "includeAllFunctions": true,
        "generateSignatures": false,
        "stringAnchors": [{"name": "FixtureComponentTable", "string": support::ANCHOR_STRING}]
    })
    .to_string();
    let payload: serde_json::Value = serde_json::from_str(
        &analyze_internal_json(
            library.to_str().expect("utf8 path"),
            "fixture-sha",
            &options,
            scratch.join("out").to_str().expect("utf8 path"),
            None,
        )
        .expect("internal analysis"),
    )
    .expect("payload json");

    // The summary's `ambiguous` counter is shared with the vtable and pattern
    // passes, so the anchor's contribution is measured against the same fixture
    // with a single reference.
    let single = run(
        &Scratch::new("ambiguous-baseline"),
        serde_json::json!([{"name": "FixtureComponentTable", "string": support::ANCHOR_STRING}]),
    );
    let summary = &payload["internal"];
    let baseline = single["internal"]["ambiguous"].as_u64().expect("baseline");
    assert!(
        summary["ambiguous"].as_u64().expect("ambiguous") > baseline,
        "two references must raise the ambiguity count above {baseline}: {summary}"
    );
    assert_eq!(summary["notFound"].as_u64(), Some(0));
    // Both candidates carry the name *and* are flagged ambiguous, so no reader
    // can mistake one of two for a fact.
    for index in [
        support::ANCHOR_FUNCTION_INDEX,
        support::SECOND_ANCHOR_FUNCTION_INDEX,
    ] {
        let address = support::TEXT_ADDRESS + (index * support::FRAME_BYTES) as u64;
        let row = function_row(&payload, address);
        assert_eq!(row["name"].as_str(), Some("FixtureComponentTable"), "{row}");
        assert_eq!(row["status"].as_str(), Some("ambiguous"), "{row}");
    }
}
