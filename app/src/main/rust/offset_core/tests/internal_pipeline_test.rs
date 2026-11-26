//! End-to-end Part 2 (internal extraction) against a synthetic library.
//!
//! This is the regression test for the field failure that made a finished scan
//! unusable:
//!
//! ```text
//! Internal extraction failed: IOException: The internal offsets file is gone (. 0 bytes)
//! ```
//!
//! The app classifies as it writes, so the engine takes the *category* path and
//! never creates a single NDJSON table. That path used to leave
//! `InternalSummary.path` empty and `tableBytes` at zero, so Kotlin rejected a
//! pass that had in fact written every row. The assertions below are the
//! contract the Kotlin side relies on: a non-empty path, a non-zero size, every
//! category document on disk and parseable, and its rows adding up.

use std::path::{Path, PathBuf};

#[path = "support/mod.rs"]
mod support;

use offset_core::jni_bridge::analyze_internal_json;

/// A scratch directory that removes itself.
struct Scratch {
    root: PathBuf,
}

impl Scratch {
    fn new(label: &str) -> Self {
        let root = std::env::temp_dir().join(format!(
            "offset-core-{label}-{}-{}",
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

fn write_library(scratch: &Scratch) -> PathBuf {
    let path = scratch.join("libfixture.so");
    std::fs::write(&path, support::build_library()).expect("write fixture");
    path
}

const CATEGORY_RULES: &str = r#"{"categories":[
    {"category":"actor_entity","label":"Actors","classes":["^(FixtureActor|FixtureMob)$"]},
    {"category":"other_named","label":"Other","classes":[]}
]}"#;

fn shard_options() -> String {
    serde_json::json!({
        "includeAllFunctions": true,
        "generateSignatures": true,
        "categories": serde_json::from_str::<serde_json::Value>(CATEGORY_RULES).expect("rules"),
        "sourceHeader": "\"version\":\"26.60\""
    })
    .to_string()
}

fn run(library: &Path, options: &str, out: &Path) -> serde_json::Value {
    let payload = analyze_internal_json(
        library.to_str().expect("utf8 path"),
        "fixture-sha",
        options,
        out.to_str().expect("utf8 path"),
        None,
    )
    .expect("internal analysis");
    serde_json::from_str(&payload).expect("payload json")
}

/// Every category document the summary lists must exist, be non-empty, parse as
/// JSON and agree with the counts the summary reports.
fn assert_shards_are_real(summary: &serde_json::Value) {
    let shards = summary["shards"].as_array().expect("shards array");
    assert!(!shards.is_empty(), "the engine wrote no category document");

    let mut total_rows = 0usize;
    for shard in shards {
        let path = shard["path"].as_str().expect("shard path");
        assert!(!path.is_empty(), "a shard reported an empty path");
        let bytes = shard["bytes"].as_u64().expect("shard bytes");
        assert!(bytes > 0, "{path} was reported as zero bytes");
        let on_disk = std::fs::metadata(path).expect("shard exists").len();
        assert_eq!(on_disk, bytes, "{path} size disagrees with the summary");

        let text = std::fs::read_to_string(path).expect("shard readable");
        let document: serde_json::Value = serde_json::from_str(&text).expect("shard is JSON");
        let classes = document["classes"].as_object().map_or(0, |map| map.len());
        let functions = document["functions"].as_array().map_or(0, |array| array.len());
        let symbols = document["symbols"].as_array().map_or(0, |array| array.len());
        assert_eq!(
            document["stats"]["entries"].as_u64().expect("stats"),
            (classes + functions + symbols) as u64,
            "{path} stats disagree with its own rows"
        );
        assert_eq!(
            shard["classes"].as_u64().expect("classes") as usize,
            classes,
            "{path} class count disagrees"
        );
        assert_eq!(
            shard["functions"].as_u64().expect("functions") as usize,
            functions,
            "{path} function count disagrees"
        );
        assert_eq!(
            shard["symbols"].as_u64().expect("symbols") as usize,
            symbols,
            "{path} symbol count disagrees"
        );
        total_rows += classes + functions + symbols;
    }

    let rows = summary["rows"].as_u64().expect("rows") as usize;
    assert_eq!(
        total_rows, rows,
        "the category documents hold {total_rows} rows, the summary reports {rows}"
    );
}

#[test]
fn the_category_path_reports_a_real_path_and_size() {
    let scratch = Scratch::new("shards");
    let library = write_library(&scratch);
    let out = scratch.join("internal");
    let payload = run(&library, &shard_options(), &out);
    let summary = &payload["internal"];

    // The exact field that came back empty on device.
    let path = summary["path"].as_str().expect("path");
    assert!(
        !path.is_empty(),
        "the category path left `path` empty, which Kotlin reads as 'file is gone'"
    );
    assert!(
        Path::new(path).is_dir(),
        "the reported path {path} is not the directory the documents were written to"
    );
    let table_bytes = summary["tableBytes"].as_u64().expect("tableBytes");
    assert!(
        table_bytes > 0,
        "the category path left `tableBytes` at zero"
    );
    assert_eq!(
        table_bytes,
        summary["shards"]
            .as_array()
            .expect("shards")
            .iter()
            .map(|shard| shard["bytes"].as_u64().unwrap_or(0))
            .sum::<u64>(),
        "tableBytes must be the sum of the documents on disk"
    );
    assert_eq!(summary["categoriesApplied"], serde_json::Value::Bool(true));
    assert_shards_are_real(summary);
    assert!(!summary["classMapPath"].as_str().unwrap_or("").is_empty());
}

#[test]
fn the_ndjson_path_still_reports_a_real_path_and_size() {
    let scratch = Scratch::new("ndjson");
    let library = write_library(&scratch);
    let out = scratch.join("internal");
    let payload = run(&library, "{}", &out);
    let summary = &payload["internal"];

    let path = summary["path"].as_str().expect("path");
    assert!(!path.is_empty());
    let bytes = std::fs::metadata(path).expect("table exists").len();
    assert!(bytes > 0);
    assert_eq!(summary["tableBytes"].as_u64().expect("tableBytes"), bytes);
    assert!(summary["shards"].as_array().expect("shards").is_empty());
}

#[test]
fn every_planted_function_and_vtable_slot_is_recovered() {
    let scratch = Scratch::new("coverage");
    let library = write_library(&scratch);
    let out = scratch.join("internal");
    let payload = run(&library, &shard_options(), &out);
    let summary = &payload["internal"];

    // One FDE per planted function, and the table must contain all of them.
    let coverage = &summary["coverage"];
    assert_eq!(
        coverage["fdeFunctions"].as_u64().expect("fde"),
        support::FUNCTIONS as u64
    );
    assert_eq!(coverage["fdeMissing"].as_u64().expect("missing"), 0);
    assert_eq!(coverage["functionsMatchFdes"], serde_json::Value::Bool(true));
    assert_eq!(coverage["functionSource"].as_str(), Some("eh_frame"));

    // Collect the rows the documents hold.
    let mut addresses: Vec<u64> = Vec::new();
    let mut slots: Vec<(String, usize, u64)> = Vec::new();
    for shard in summary["shards"].as_array().expect("shards") {
        let text = std::fs::read_to_string(shard["path"].as_str().expect("path")).expect("read");
        let document: serde_json::Value = serde_json::from_str(&text).expect("json");
        for function in document["functions"].as_array().expect("functions") {
            let address = function["address"].as_str().expect("address");
            addresses.push(u64::from_str_radix(address.trim_start_matches("0x"), 16).expect("hex"));
        }
        if let Some(classes) = document["classes"].as_object() {
            for (name, class) in classes {
                for group in class["vtableGroups"].as_array().expect("groups") {
                    for slot in group["slots"].as_array().expect("slots") {
                        let address = slot["address"].as_str().expect("slot address");
                        slots.push((
                            name.clone(),
                            slot["slot"].as_u64().expect("slot index") as usize,
                            u64::from_str_radix(address.trim_start_matches("0x"), 16).expect("hex"),
                        ));
                    }
                }
            }
        }
    }

    for index in 0..support::FUNCTIONS {
        let planted = support::function_address(index);
        assert!(
            addresses.contains(&planted),
            "function {index} at {planted:#x} is missing from the table"
        );
    }

    // Every planted vtable slot must be present with its real target, at its
    // real slot index.
    for class in 0..support::CLASSES {
        let name = format!("Fixture{}", if class == 0 { "Actor" } else { "Mob" });
        for slot in 0..support::SLOTS_PER_CLASS {
            let expected = support::function_address((class + slot) % support::FUNCTIONS);
            let found = slots
                .iter()
                .find(|(class_name, index, _)| class_name == &name && *index == slot);
            let (_, _, address) = found.unwrap_or_else(|| {
                panic!("{name} slot {slot} is missing; recovered slots: {slots:?}")
            });
            assert_eq!(
                *address, expected,
                "{name} slot {slot} points at {address:#x}, planted {expected:#x}"
            );
        }
    }

    // The exported symbol and the JNI export must be named in the table.
    assert!(
        summary["named"].as_u64().expect("named") >= 2,
        "the exported symbols were not used as names"
    );
    assert_eq!(
        summary["classes"].as_u64().expect("classes"),
        support::CLASSES as u64
    );
}

#[test]
fn a_class_with_more_slots_than_the_cap_says_so() {
    // A cap that silently drops slots is worse than a cap that reports itself.
    let scratch = Scratch::new("cap");
    let library = write_library(&scratch);
    let out = scratch.join("internal");
    let options = serde_json::json!({
        "includeAllFunctions": true,
        "generateSignatures": false,
        "maxSlotsPerClass": 1,
        "categories": serde_json::from_str::<serde_json::Value>(CATEGORY_RULES).expect("rules")
    })
    .to_string();
    let payload = run(&library, &options, &out);
    let summary = &payload["internal"];
    assert_eq!(summary["truncatedSlots"], serde_json::Value::Bool(true));
    assert!(
        summary["truncatedSlotClasses"].as_u64().expect("count") >= 1,
        "the capped classes were not reported"
    );
    assert_eq!(
        summary["coverage"]["truncated"],
        serde_json::Value::Bool(true)
    );
    assert!(
        !summary["truncatedSlotNames"]
            .as_array()
            .expect("names")
            .is_empty()
    );
}
