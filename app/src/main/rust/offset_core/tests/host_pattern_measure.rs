//! Host measurement of the shipped signature pack against a real library.
//!
//! The device run reported "68 verified signatures, 0 discarded" out of a pack
//! that holds 91 patterns, and the reason was invisible: the app only logs the
//! totals. This test prints one line per pattern — how many times it matched,
//! whether the library exports a symbol of that name, and whether the two
//! addresses agree — so a stale pattern is a fact rather than a guess.
//!
//! ```text
//! BEDROCK_LIB=/tmp/libminecraftpe.so \
//!   cargo test --release --test host_pattern_measure -- --ignored --nocapture
//! ```

#[test]
#[ignore = "needs a real libminecraftpe.so; set BEDROCK_LIB"]
fn every_shipped_pattern_is_measured() {
    let library = std::env::var("BEDROCK_LIB").expect("BEDROCK_LIB");
    let assets =
        std::env::var("BEDROCK_ASSETS").unwrap_or_else(|_| "app/src/main/assets".to_string());
    let pack = std::fs::read_to_string(format!("{assets}/patterns.json")).expect("patterns.json");
    let document: serde_json::Value = serde_json::from_str(&pack).expect("patterns json");

    let mut specs: Vec<serde_json::Value> = Vec::new();
    for key in ["patterns", "internalPatterns"] {
        if let Some(list) = document[key].as_array() {
            specs.extend(list.iter().cloned());
        }
    }
    println!("shipped patterns: {}", specs.len());

    // The engine takes the flat list the app builds from the asset document.
    let flat: Vec<serde_json::Value> = specs
        .iter()
        .filter(|entry| {
            entry["name"].as_str().is_some_and(|name| !name.is_empty())
                && entry["pattern"]
                    .as_str()
                    .is_some_and(|pattern| !pattern.is_empty())
        })
        .map(|entry| serde_json::json!({ "name": entry["name"], "pattern": entry["pattern"] }))
        .collect();
    let flat = serde_json::Value::Array(flat).to_string();
    let payload =
        offset_core::jni_bridge::scan_text_json(&library, "measure", &flat).expect("scan");
    let outcome: serde_json::Value = serde_json::from_str(&payload).expect("scan json");

    let hits = outcome["hits"].as_array().cloned().unwrap_or_default();
    let coverage = &outcome["jni"]["coverage"];
    println!(
        "hits={} discarded={} verified={} conflicts={} unverified={} jniUnion={} \
         matched={} ambiguous={} notFound={} stale={}",
        hits.len(),
        outcome["discarded"],
        outcome["verification"]["verified"],
        outcome["verification"]["conflicts"],
        outcome["verification"]["unverified"],
        coverage["union"],
        coverage["patternsMatched"],
        coverage["patternsAmbiguous"],
        coverage["patternsNotFound"],
        coverage["patternsStale"]
            .as_array()
            .map(|list| list.len())
            .unwrap_or(0)
    );

    let mut per_pattern: Vec<(String, usize, String)> = Vec::new();
    for pattern in outcome["jni"]["patterns"]
        .as_array()
        .cloned()
        .unwrap_or_default()
    {
        let name = pattern["name"].as_str().unwrap_or("?").to_string();
        let count = pattern["hits"].as_u64().unwrap_or(0) as usize;
        let status = pattern["status"].as_str().unwrap_or("?").to_string();
        per_pattern.push((name, count, status));
    }
    per_pattern.sort_by(|left, right| left.2.cmp(&right.2).then(left.0.cmp(&right.0)));
    let mut by_status: std::collections::BTreeMap<String, usize> = Default::default();
    for (name, count, status) in &per_pattern {
        *by_status.entry(status.clone()).or_default() += 1;
        if status != "matched" {
            println!("  {status:12} {count:3} hits  {name}");
        }
    }
    println!("by status: {by_status:?}");

    // A pattern whose function the library names is stale, not missing: the
    // symbol is the ground truth and the bytes need regenerating.
    let symbols =
        offset_core::jni_bridge::describe_symbols_json(&library, "measure").expect("symbols");
    let symbols: serde_json::Value = serde_json::from_str(&symbols).expect("symbols json");
    let mut named = 0usize;
    let exported: Vec<&str> = symbols["symbols"]["jni"]
        .as_array()
        .map(|list| {
            list.iter()
                .filter_map(|entry| entry["name"].as_str())
                .collect()
        })
        .unwrap_or_default();
    for (name, _, _) in &per_pattern {
        if exported.contains(&name.as_str()) {
            named += 1;
        }
    }
    println!(
        "patterns whose name is an exported symbol: {named} of {} (exports: {})",
        per_pattern.len(),
        exported.len()
    );
}
