//! Host measurement of the path the app actually takes: classify-as-you-write.
//!
//! The device log showed Part 2 finishing and then failing with
//! "The internal offsets file is gone (. 0 bytes)". That message came from the
//! category path, so this test runs the same path against a real library and
//! prints what it produced. It is `#[ignore]`d because it needs a 326 MB input.
//!
//! ```text
//! BEDROCK_LIB=/tmp/libminecraftpe.so \
//! BEDROCK_CATEGORIES=app/src/main/assets/categories.json \
//! BEDROCK_OUT=/tmp/shard-out \
//!   cargo test --release --test host_shard_measure -- --ignored --nocapture
//! ```

use std::path::Path;

struct Quiet;

impl offset_core::scanner::ProgressSink for Quiet {
    fn report(&self, percent: u32, message: &str) {
        let peak = offset_core::memory::peak_resident_bytes().unwrap_or(0) / (1024 * 1024);
        println!("{percent:3}% {message} [peak {peak} MB]");
    }
}

#[test]
#[ignore = "needs a real libminecraftpe.so; set BEDROCK_LIB"]
fn the_category_path_writes_every_row() {
    let library = std::env::var("BEDROCK_LIB").expect("BEDROCK_LIB");
    let categories_path = std::env::var("BEDROCK_CATEGORIES")
        .unwrap_or_else(|_| "app/src/main/assets/categories.json".to_string());
    let output_dir = std::env::var("BEDROCK_OUT").unwrap_or_else(|_| "/tmp/shard-out".into());

    let rules = std::fs::read_to_string(&categories_path).expect("category rules");
    let options = serde_json::json!({
        "includeAllFunctions": true,
        "generateSignatures": true,
        "categories": serde_json::from_str::<serde_json::Value>(&rules).expect("rules json"),
        "sourceHeader": "\"version\":\"26.60\""
    })
    .to_string();

    let payload = offset_core::jni_bridge::analyze_internal_json(
        &library,
        "measure",
        &options,
        &output_dir,
        Some(&Quiet),
    )
    .expect("internal analysis");
    let payload: serde_json::Value = serde_json::from_str(&payload).expect("payload");
    let summary = &payload["internal"];

    let path = summary["path"].as_str().expect("path");
    assert!(!path.is_empty(), "the category path left `path` empty");
    let table_bytes = summary["tableBytes"].as_u64().expect("tableBytes");
    assert!(
        table_bytes > 0,
        "the category path left `tableBytes` at zero"
    );

    let shards = summary["shards"].as_array().expect("shards");
    let mut rows = 0u64;
    let mut classes = 0u64;
    let mut functions = 0u64;
    let mut symbols = 0u64;
    let mut on_disk = 0u64;
    println!("category documents: {}", shards.len());
    let mut per_category: Vec<(String, u64, u64, u64)> = Vec::new();
    for shard in shards {
        let file = shard["path"].as_str().expect("shard path");
        let bytes = std::fs::metadata(file).expect("shard exists").len();
        assert!(bytes > 0, "{file} is empty");
        on_disk += bytes;
        let c = shard["classes"].as_u64().unwrap_or(0);
        let f = shard["functions"].as_u64().unwrap_or(0);
        let s = shard["symbols"].as_u64().unwrap_or(0);
        classes += c;
        functions += f;
        symbols += s;
        rows += c + f + s;
        per_category.push((shard["id"].as_str().unwrap_or("?").to_string(), c, f, s));
    }
    per_category.sort_by_key(|entry| std::cmp::Reverse(entry.1 + entry.2 + entry.3));
    for (id, c, f, s) in &per_category {
        println!("  {id:32} {c:6} classes {f:8} functions {s:6} symbols");
    }

    let reported_rows = summary["rows"].as_u64().expect("rows");
    println!(
        "path={path} tableBytes={table_bytes} onDisk={on_disk} rows={reported_rows} \
         classes={classes} functions={functions} symbols={symbols}"
    );
    assert_eq!(rows, reported_rows, "the documents do not hold every row");
    assert_eq!(classes, summary["classes"].as_u64().expect("classes"));
    assert_eq!(functions, summary["functions"].as_u64().expect("functions"));
    assert_eq!(symbols, summary["dataSymbols"].as_u64().expect("symbols"));
    assert_eq!(on_disk, table_bytes, "tableBytes is not what is on disk");

    let coverage = &summary["coverage"];
    println!(
        "coverage: functions={} fdes={} missing={} slots={} truncated={} \
         withoutFileOffset={}",
        coverage["functions"],
        coverage["fdeFunctions"],
        coverage["fdeMissing"],
        coverage["slots"],
        coverage["truncated"],
        coverage["functionsWithoutFileOffset"]
    );
    assert_eq!(
        coverage["functions"].as_u64().expect("functions"),
        functions,
        "the coverage block disagrees with the rows written"
    );
    assert_eq!(coverage["fdeMissing"].as_u64().expect("missing"), 0);

    assert!(Path::new(&summary["classMapPath"].as_str().unwrap_or("")).is_file());
    let peak = offset_core::memory::peak_resident_bytes().unwrap_or(0) / (1024 * 1024);
    println!("peak resident: {peak} MB");
}
