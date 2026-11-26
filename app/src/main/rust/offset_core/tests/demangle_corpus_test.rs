//! Replays the `c++filt` ground-truth corpus.
//!
//! `scripts/build_demangle_corpus.py` compiles `scripts/fixtures/demangle_corpus.cpp`
//! and reads every RTTI type name and symbol out of the host `libstdc++`, asks
//! GNU `c++filt` what each one is, and writes the answers to
//! `tests/data/demangle_corpus.json`. This test replays that file, so a
//! regression in the demangler fails the build on a machine with no binutils.
//!
//! The script requires 100 % agreement before it will write the corpus; names
//! `cpp_demangle` 0.6 cannot render (a parameter pack inside a function
//! parameter list) are listed under `knownGaps` and pinned here, so an upstream
//! fix shows up as a failing test that asks for the gap to be retired.

use offset_core::demangle::{demangle_symbol, demangle_type};
use serde::Deserialize;

#[derive(Deserialize)]
struct Entry {
    mangled: String,
    demangled: String,
}

#[derive(Deserialize)]
struct KnownGap {
    mangled: String,
    reason: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Corpus {
    types: Vec<Entry>,
    symbols: Vec<Entry>,
    known_gaps: Vec<KnownGap>,
}

/// The only normalisation: C++ spells `> >` and `>>` the same way.
fn collapse(text: &str) -> String {
    let mut out = text.replace("> >", ">>");
    while out.contains("> >") {
        out = out.replace("> >", ">>");
    }
    out.trim().to_string()
}

fn corpus() -> Corpus {
    let path = concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/tests/data/demangle_corpus.json"
    );
    let text = std::fs::read_to_string(path).expect("the corpus is checked in");
    serde_json::from_str(&text).expect("the corpus parses")
}

#[test]
fn every_type_name_matches_cppfilt() {
    let corpus = corpus();
    assert!(
        corpus.types.len() > 100,
        "the corpus must stay useful, got {} types",
        corpus.types.len()
    );
    let mut failures = Vec::new();
    for entry in &corpus.types {
        match demangle_type(&entry.mangled) {
            Some(name) if collapse(&name.display) == collapse(&entry.demangled) => {}
            Some(name) => failures.push(format!(
                "{}\n  expected {}\n  got      {}",
                entry.mangled, entry.demangled, name.display
            )),
            None => failures.push(format!(
                "{}\n  expected {}\n  got      <none>",
                entry.mangled, entry.demangled
            )),
        }
    }
    assert!(
        failures.is_empty(),
        "{} of {} type names disagree with c++filt:\n{}",
        failures.len(),
        corpus.types.len(),
        failures.join("\n")
    );
}

#[test]
fn every_symbol_matches_cppfilt() {
    let corpus = corpus();
    assert!(
        corpus.symbols.len() > 1000,
        "the corpus must stay useful, got {} symbols",
        corpus.symbols.len()
    );
    let mut failures = Vec::new();
    for entry in &corpus.symbols {
        match demangle_symbol(&entry.mangled) {
            Some(name) if collapse(&name) == collapse(&entry.demangled) => {}
            Some(name) => failures.push(format!(
                "{}\n  expected {}\n  got      {}",
                entry.mangled, entry.demangled, name
            )),
            None => failures.push(format!(
                "{}\n  expected {}\n  got      <none>",
                entry.mangled, entry.demangled
            )),
        }
    }
    assert!(
        failures.is_empty(),
        "{} of {} symbols disagree with c++filt:\n{}",
        failures.len(),
        corpus.symbols.len(),
        failures.join("\n")
    );
}

#[test]
fn the_known_upstream_gaps_are_pinned_and_still_gap() {
    let corpus = corpus();
    assert!(
        corpus.known_gaps.len() < 40,
        "the gap list grew to {}; investigate instead of accepting it",
        corpus.known_gaps.len()
    );
    for gap in &corpus.known_gaps {
        assert!(
            gap.reason.contains("pack"),
            "an unexpected gap class: {}",
            gap.reason
        );
        // If this starts demangling correctly, cpp_demangle was fixed: retire
        // the entry (run the corpus script) rather than leaving a stale list.
        assert!(
            demangle_symbol(&gap.mangled).is_some(),
            "{} no longer demangles at all",
            gap.mangled
        );
    }
}

#[test]
fn the_shapes_that_collapsed_in_the_26_60_export_are_distinct() {
    // Real names from `scripts/fixtures/demangle_corpus.cpp`, produced by g++
    // and verified with c++filt. The old demangler read `14ClientInstance` and
    // stopped at `I`, so every instantiation of a class became one name.
    let lambda = demangle_type("ZN5Level4tickEvEUliE_").expect("a lambda inside Level::tick");
    assert!(
        lambda.display.contains("Level::tick()"),
        "{}",
        lambda.display
    );
    assert!(lambda.display.contains("lambda"), "{}", lambda.display);
    assert_ne!(
        lambda.display,
        demangle_type("N5Level5ActorE").expect("Actor").display
    );

    let int_function = demangle_type("St8functionIFiiEE").expect("function<int(int)>");
    let void_function = demangle_type("St8functionIFvvEE").expect("function<void()>");
    assert_ne!(int_function.display, void_function.display);
    assert!(
        int_function.display.contains("int"),
        "{}",
        int_function.display
    );

    // A non-type template argument holding a function pointer keeps its name.
    let system = demangle_symbol("_ZN12StrictSystemIXadL_ZL6tickMeiEEE4callEi")
        .expect("StrictSystem<&tickMe>::call");
    assert!(system.contains("tickMe"), "{system}");
    assert!(system.contains("StrictSystem<"), "{system}");
}
