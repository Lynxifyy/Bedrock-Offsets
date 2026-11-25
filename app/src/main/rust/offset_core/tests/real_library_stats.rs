//! Host-side metrics for a real `libminecraftpe.so`.
//!
//! The Android app is the normal consumer of this pass, but the same code runs
//! on the host against an extracted library. That is enough to check the
//! vtable, merge and signature fixes on a real 26.60 build without a device.
//!
//! ```text
//! BEDROCK_LIB=/tmp/libminecraftpe.so cargo test --release --test real_library_stats -- --ignored --nocapture
//! ```

use std::collections::HashMap;
use std::io::{BufRead, BufReader};

/// Prints the high-water resident set at every pass, so a regression in memory
/// use can be attributed to the pass that caused it.
struct RssSink;

impl offset_core::scanner::ProgressSink for RssSink {
    fn report(&self, percent: u32, message: &str) {
        let peak = offset_core::memory::peak_resident_bytes().unwrap_or(0) / (1024 * 1024);
        println!("{percent:3}% {message} [peak {peak} MB]");
    }
}

#[test]
#[ignore = "needs a real libminecraftpe.so; set BEDROCK_LIB"]
fn real_library_stats() {
    let Ok(path) = std::env::var("BEDROCK_LIB") else {
        eprintln!("BEDROCK_LIB is not set");
        return;
    };
    let output_dir = std::env::var("BEDROCK_OUT").unwrap_or_else(|_| "/tmp/offset-core-out".into());
    let payload = offset_core::jni_bridge::analyze_internal_json(
        &path,
        "",
        "{}",
        &output_dir,
        Some(&RssSink),
    )
    .expect("internal analysis");
    let payload: serde_json::Value = serde_json::from_str(&payload).expect("payload json");
    println!(
        "payload: {}",
        serde_json::to_string_pretty(&payload).expect("pretty")
    );

    let table = payload["internal"]["path"]
        .as_str()
        .expect("table path")
        .to_string();
    let file = std::fs::File::open(&table).expect("open table");
    let mut zero_slots: Vec<String> = Vec::new();
    let mut zero_slot_classes = 0usize;
    let mut classes = 0usize;
    let mut slots = 0usize;
    let mut sized = 0usize;
    let mut slot_signatures = 0usize;
    let mut slot_unique = 0usize;
    let mut function_signatures = 0usize;
    let mut function_unique = 0usize;
    let mut kinds: HashMap<String, usize> = HashMap::new();
    let mut overrides: HashMap<String, usize> = HashMap::new();
    let mut names: HashMap<String, usize> = HashMap::new();
    let mut data_symbols = 0usize;
    for line in BufReader::new(file).lines() {
        let line = line.expect("read line");
        let record: serde_json::Value = serde_json::from_str(&line).expect("record json");
        match record["type"].as_str() {
            Some("class") => {
                classes += 1;
                let class = &record["class"];
                let name = class["name"].as_str().unwrap_or("").to_string();
                let count = class["slotCount"].as_u64().unwrap_or(0) as usize;
                if count == 0 {
                    zero_slot_classes += 1;
                    if zero_slots.len() < 40 {
                        zero_slots.push(name.clone());
                    }
                }
                let mut class_overrides = 0usize;
                for group in class["vtableGroups"].as_array().into_iter().flatten() {
                    for slot in group["slots"].as_array().into_iter().flatten() {
                        slots += 1;
                        if slot["size"].as_u64().unwrap_or(0) > 0 {
                            sized += 1;
                        }
                        if slot["signature"].is_string() {
                            slot_signatures += 1;
                            if slot["signatureUnique"].as_bool() == Some(true) {
                                slot_unique += 1;
                            }
                        }
                        if slot["overrides"].as_bool() == Some(true) {
                            class_overrides += 1;
                        }
                        let kind = slot["kind"].as_str().unwrap_or("").to_string();
                        *kinds.entry(kind).or_default() += 1;
                    }
                }
                overrides.insert(name, class_overrides);
            }
            Some("symbol") => data_symbols += 1,
            Some("function") => {
                let function = &record["function"];
                if function["signature"].is_string() {
                    function_signatures += 1;
                    if function["signatureUnique"].as_bool() == Some(true) {
                        function_unique += 1;
                    }
                }
                if let Some(name) = function["name"].as_str() {
                    *names.entry(name.to_string()).or_default() += 1;
                }
            }
            _ => {}
        }
    }
    let duplicates: usize = names.values().filter(|count| **count > 1).sum();
    println!(
        "summary: classes={classes} zero_slot_classes={zero_slot_classes} slots={slots} \
         slots_with_size={sized} slots_without_size={} slot_signatures={slot_signatures} \
         slot_unique={slot_unique} function_signatures={function_signatures} \
         function_unique={function_unique} data_symbols={data_symbols} duplicate_names={duplicates}",
        slots.saturating_sub(sized)
    );
    println!("zero slot classes: {zero_slots:?}");
    println!("kinds: {kinds:?}");
    println!(
        "ClientInstance overrides: {}",
        overrides.get("ClientInstance").copied().unwrap_or(0)
    );
    println!(
        "Actor overrides: {}",
        overrides.get("Actor").copied().unwrap_or(0)
    );
}
