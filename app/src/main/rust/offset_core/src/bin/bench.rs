//! Host benchmark for the internal analysis pipeline.
//!
//! The engine only ever runs on a tablet, where a wrong measurement costs the
//! owner a crashed scan. This binary runs the same stages as the JNI entry point
//! on a desktop, printing wall time and resident memory at every pass so the
//! performance work can be checked against numbers instead of impressions.
//!
//! ```text
//! cargo run --release --bin bench -- /tmp/scale.so
//! cargo run --release --bin bench -- /tmp/scale.so --no-signatures
//! ```
//!
//! The output is one line per stage:
//!
//! ```text
//!   1234 ms   anon  210 MB   file  320 MB   peak  560 MB   80% Signatures: generating
//! ```
//!
//! `anon` is the anonymous resident set (heap, stacks), `file` the page cache
//! the process has mapped, and `peak` the kernel's high-water mark for the whole
//! process.

use std::path::Path;
use std::time::Instant;

use offset_core::elf::MappedLibrary;
use offset_core::internal::{analyze_with_writer, InternalOptions};
use offset_core::rtti::resolve_vtables_with_releaser;
use offset_core::scanner::{ProgressSink, ScanControl};
use offset_core::sections::build_section_map;
use offset_core::shards::NdjsonWriter;
use offset_core::symbols::read_named_symbols;

/// One `/proc/self/status` reading.
struct Usage {
    anon: u64,
    file: u64,
    peak: u64,
}

fn usage() -> Usage {
    let mut usage = Usage {
        anon: 0,
        file: 0,
        peak: 0,
    };
    let Ok(status) = std::fs::read_to_string("/proc/self/status") else {
        return usage;
    };
    for line in status.lines() {
        let mut fields = line.split_whitespace();
        let Some(key) = fields.next() else { continue };
        let Some(value) = fields.next().and_then(|value| value.parse::<u64>().ok()) else {
            continue;
        };
        match key {
            "RssAnon:" => usage.anon = value * 1024,
            "RssFile:" => usage.file = value * 1024,
            "VmHWM:" => usage.peak = value * 1024,
            _ => {}
        }
    }
    usage
}

fn mib(bytes: u64) -> u64 {
    bytes / (1024 * 1024)
}

struct Printer {
    started: Instant,
}

impl ProgressSink for Printer {
    fn report(&self, percent: u32, message: &str) {
        let usage = usage();
        println!(
            "{:>7} ms   anon {:>4} MB   file {:>4} MB   peak {:>4} MB   {:>3}% {}",
            self.started.elapsed().as_millis(),
            mib(usage.anon),
            mib(usage.file),
            mib(usage.peak),
            percent,
            message
        );
    }
}

fn main() {
    let mut args = std::env::args().skip(1);
    let Some(path) = args.next() else {
        eprintln!("usage: bench <library.so> [--no-signatures] [--max-signatures N]");
        std::process::exit(2);
    };
    let mut options = InternalOptions::default();
    let mut max_signatures = options.max_signatures;
    let mut out: Option<std::path::PathBuf> = None;
    while let Some(flag) = args.next() {
        match flag.as_str() {
            "--no-signatures" => options.generate_signatures = false,
            "--max-signatures" => {
                max_signatures = args
                    .next()
                    .and_then(|value| value.parse().ok())
                    .unwrap_or(max_signatures);
            }
            "--out" => out = args.next().map(std::path::PathBuf::from),
            other => {
                eprintln!("unknown flag {other}");
                std::process::exit(2);
            }
        }
    }
    options.max_signatures = max_signatures;

    let started = Instant::now();
    let printer = Printer { started };
    let library = match MappedLibrary::open(Path::new(&path), String::from("bench")) {
        Ok(library) => library,
        Err(error) => {
            eprintln!("cannot open {path}: {error}");
            std::process::exit(1);
        }
    };
    printer.report(0, &format!("mapped {} bytes", library.bytes().len()));

    let map = match build_section_map(library.bytes()) {
        Ok(map) => map,
        Err(error) => {
            eprintln!("cannot read sections: {error}");
            std::process::exit(1);
        }
    };
    printer.report(
        2,
        &format!(
            "sections: text {} bytes at {:#x}",
            map.text.size, map.text.address
        ),
    );

    let vtables = match resolve_vtables_with_releaser(library.bytes(), Some(&library)) {
        Ok(vtables) => vtables,
        Err(error) => {
            eprintln!("cannot resolve vtables: {error}");
            std::process::exit(1);
        }
    };
    printer.report(
        5,
        &format!(
            "vtables: {} groups, {} type_info candidates",
            vtables.all.len(),
            vtables.type_info_candidates
        ),
    );

    let symbols = match read_named_symbols(library.bytes()) {
        Ok(symbols) => symbols,
        Err(error) => {
            eprintln!("cannot read symbols: {error}");
            std::process::exit(1);
        }
    };
    printer.report(
        8,
        &format!(
            "symbols: {} functions, {} data",
            symbols.functions.len(),
            symbols.data.len()
        ),
    );

    let control = ScanControl::new();
    let mut sink = std::io::sink();
    let mut file = out
        .as_ref()
        .map(|path| std::fs::File::create(path).expect("create output"));
    let mut writer = NdjsonWriter::new(match file.as_mut() {
        Some(file) => file as &mut dyn std::io::Write,
        None => &mut sink as &mut dyn std::io::Write,
    });
    let summary = match analyze_with_writer(
        library.bytes(),
        &map,
        &vtables,
        &symbols.functions,
        &symbols.data,
        &options,
        &control,
        Some(&printer),
        Some(&library),
        &mut writer,
    ) {
        Ok(summary) => summary,
        Err(error) => {
            eprintln!("analysis failed: {error}");
            std::process::exit(1);
        }
    };

    let usage = usage();
    println!();
    println!("functions      {}", summary.functions);
    println!("named          {}", summary.named);
    println!("auto-named     {}", summary.auto_named);
    println!("signatures     {}", summary.signatures);
    println!("signatures ok  {}", summary.signatures_unique);
    println!("classes        {}", summary.classes);
    println!("slots          {}", summary.vtable_slots);
    println!("verified       {}", summary.verified);
    println!("ambiguous      {}", summary.ambiguous);
    println!("not found      {}", summary.not_found);
    println!("table bytes    {}", summary.table_bytes);
    println!("wall           {} ms", started.elapsed().as_millis());
    println!(
        "resident       anon {} MB, file {} MB, peak {} MB",
        mib(usage.anon),
        mib(usage.file),
        mib(usage.peak)
    );
}
