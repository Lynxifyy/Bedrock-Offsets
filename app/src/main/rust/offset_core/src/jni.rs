//! The complete JNI offset set and its coverage.
//!
//! Three independent sources name the same natives, and none of them is
//! complete on its own:
//!
//! * **Exported symbols** — every `Java_*` entry in `.dynsym`, plus the
//!   lifecycle exports `JNI_OnLoad` and `JNI_OnUnload`. These are exact: the
//!   linker wrote the address.
//! * **`RegisterNatives` tables** — methods the library registers at run time.
//!   They carry a signature and often a class, and they are not exported.
//! * **Signature patterns** — the hand-written patterns. They reach functions
//!   that are neither exported nor registered, but a pattern can be stale.
//!
//! The report is their union, so nothing a source found is dropped, and every
//! pattern gets a status of its own. A pattern that found nothing while its
//! symbol exists is reported as stale instead of silently disappearing.

use serde::Serialize;

use crate::jninatives::NativeTable;
use crate::scanner::{PatternHit, ScanOutcome};
use crate::symbols::SymbolTable;
use crate::verify::VerificationStatus;

/// Where an offset came from.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum JniSource {
    /// An exported `Java_*` dynamic symbol.
    Symbol,
    /// `JNI_OnLoad`, `JNI_OnUnload` or another `JNI_*` export.
    EntryPoint,
    /// A `JNINativeMethod` table entry.
    RegisterNatives,
    /// A signature pattern matched in the code section.
    Pattern,
}

/// How much the address can be trusted.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum JniConfidence {
    /// The address came from the linker or from a table the loader fills.
    High,
    /// A pattern matched and the prologue decoded.
    Medium,
    /// A pattern matched but nothing confirmed it.
    Low,
}

/// One JNI offset in the union.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct JniOffset {
    /// Method name: the symbol name, the table name, or the pattern name.
    pub java_name: String,
    /// JNI signature, when a `RegisterNatives` table supplied one.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub signature: Option<String>,
    /// Owning class, when a table's descriptor was found.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub class: Option<String>,
    /// Virtual address of the implementation.
    pub address: u64,
    /// Address rendered as `0x`-prefixed hex.
    pub address_hex: String,
    /// Which source produced this entry.
    pub source: JniSource,
    /// How much the address can be trusted.
    pub confidence: JniConfidence,
}

/// What one signature pattern did.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum PatternOutcome {
    /// Exactly one hit, and nothing contradicted it.
    Matched,
    /// More than one hit; every candidate is in the union, but the pattern
    /// cannot name one address.
    Ambiguous,
    /// No hit, and no symbol of that name either.
    NotFound,
    /// No hit or more than one, but the symbol table names the function: the
    /// symbol was used and the pattern needs updating.
    SymbolOnly,
}

/// Status of one signature pattern.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct PatternStatus {
    /// Pattern name.
    pub name: String,
    /// What the pattern did.
    pub status: PatternOutcome,
    /// Number of hits the pattern produced.
    pub hits: usize,
}

/// Counts a client needs to judge the JNI half of an archive.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
pub struct JniCoverage {
    /// Exported `Java_*` symbols found.
    pub exports: usize,
    /// `JNI_*` lifecycle exports found.
    pub entry_points: usize,
    /// Natives found in `RegisterNatives` tables.
    pub registered: usize,
    /// Patterns that were looked for.
    pub patterns: usize,
    /// Patterns with exactly one hit.
    pub patterns_matched: usize,
    /// Patterns with more than one hit.
    pub patterns_ambiguous: usize,
    /// Patterns with no hit and no symbol.
    pub patterns_not_found: usize,
    /// Patterns whose function the symbol table names, so the pattern is stale.
    pub patterns_stale: Vec<String>,
    /// Entries in the union.
    pub union: usize,
    /// Whether any source hit its own cap.
    pub truncated: bool,
}

/// The union, the per-pattern status and the coverage counts.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
pub struct JniReport {
    /// Every JNI offset, ordered by name then address.
    pub offsets: Vec<JniOffset>,
    /// One status per pattern that was looked for.
    pub patterns: Vec<PatternStatus>,
    /// Counts over every source.
    pub coverage: JniCoverage,
}

/// Builds the union from every source.
///
/// `expected` is the list of pattern names the app asked for, so a pattern that
/// produced nothing is still reported.
pub fn build_report(
    outcome: &ScanOutcome,
    symbols: &SymbolTable,
    natives: &NativeTable,
    expected: &[String],
) -> JniReport {
    let mut report = JniReport::default();
    let mut seen: Vec<(String, u64)> = Vec::new();

    let mut push = |report: &mut JniReport, offset: JniOffset| {
        let key = (offset.java_name.clone(), offset.address);
        if seen.contains(&key) {
            return;
        }
        seen.push(key);
        report.offsets.push(offset);
    };

    for native in &natives.entries {
        push(
            &mut report,
            JniOffset {
                java_name: native.java_name.clone(),
                signature: Some(native.signature.clone()),
                class: native.class.clone(),
                address: native.address,
                address_hex: native.address_hex.clone(),
                source: JniSource::RegisterNatives,
                confidence: JniConfidence::High,
            },
        );
    }

    for symbol in &symbols.jni {
        push(
            &mut report,
            JniOffset {
                java_name: symbol.name.clone(),
                signature: None,
                class: None,
                address: symbol.address,
                address_hex: symbol.address_hex.clone(),
                source: JniSource::Symbol,
                confidence: JniConfidence::High,
            },
        );
    }

    for entry in &symbols.entry_points {
        push(
            &mut report,
            JniOffset {
                java_name: entry.name.clone(),
                signature: None,
                class: None,
                address: entry.address,
                address_hex: entry.address_hex.clone(),
                source: JniSource::EntryPoint,
                confidence: JniConfidence::High,
            },
        );
    }

    for hit in &outcome.hits {
        push(
            &mut report,
            JniOffset {
                java_name: hit.name.clone(),
                signature: None,
                class: None,
                address: hit.address,
                address_hex: hit.address_hex.clone(),
                source: JniSource::Pattern,
                confidence: pattern_confidence(hit),
            },
        );
    }

    report.patterns = pattern_statuses(outcome, symbols, expected);
    report.coverage = coverage_of(outcome, symbols, natives, &report);
    report.offsets.sort_by(|left, right| {
        left.java_name
            .cmp(&right.java_name)
            .then(left.address.cmp(&right.address))
    });
    report
}

/// How much a pattern hit can be trusted, from its symbol cross-check.
fn pattern_confidence(hit: &PatternHit) -> JniConfidence {
    match hit.verification_status {
        VerificationStatus::Verified => JniConfidence::High,
        VerificationStatus::Unverified => {
            if hit.prologue_kind.is_some() {
                JniConfidence::Medium
            } else {
                JniConfidence::Low
            }
        }
        // A conflict means the pattern and the symbol disagree; neither address
        // is trusted on the strength of this pattern.
        VerificationStatus::Conflict => JniConfidence::Low,
    }
}

/// One status per expected pattern, in the order the app supplied them.
fn pattern_statuses(
    outcome: &ScanOutcome,
    symbols: &SymbolTable,
    expected: &[String],
) -> Vec<PatternStatus> {
    let mut statuses = Vec::with_capacity(expected.len());
    for name in expected {
        let hits = outcome.hits.iter().filter(|hit| hit.name == *name).count();
        let has_symbol = symbols
            .jni
            .iter()
            .chain(symbols.entry_points.iter())
            .any(|symbol| symbol.name == *name);
        let status = match (hits, has_symbol) {
            (1, _) => PatternOutcome::Matched,
            (0, true) | (2.., true) => PatternOutcome::SymbolOnly,
            (0, false) => PatternOutcome::NotFound,
            _ => PatternOutcome::Ambiguous,
        };
        statuses.push(PatternStatus {
            name: name.clone(),
            status,
            hits,
        });
    }
    statuses
}

/// Counts every source into a [`JniCoverage`].
fn coverage_of(
    outcome: &ScanOutcome,
    symbols: &SymbolTable,
    natives: &NativeTable,
    report: &JniReport,
) -> JniCoverage {
    let matched = report
        .patterns
        .iter()
        .filter(|pattern| pattern.status == PatternOutcome::Matched)
        .count();
    let ambiguous = report
        .patterns
        .iter()
        .filter(|pattern| pattern.status == PatternOutcome::Ambiguous)
        .count();
    let not_found = report
        .patterns
        .iter()
        .filter(|pattern| pattern.status == PatternOutcome::NotFound)
        .count();
    let stale = report
        .patterns
        .iter()
        .filter(|pattern| pattern.status == PatternOutcome::SymbolOnly)
        .map(|pattern| pattern.name.clone())
        .collect();

    JniCoverage {
        exports: symbols.jni.len(),
        entry_points: symbols.entry_points.len(),
        registered: natives.entries.len(),
        patterns: report.patterns.len(),
        patterns_matched: matched,
        patterns_ambiguous: ambiguous,
        patterns_not_found: not_found,
        patterns_stale: stale,
        union: report.offsets.len(),
        truncated: outcome.truncated || symbols.truncated || natives.truncated,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::jninatives::RegisteredNative;
    use crate::symbols::ExportedSymbol;

    fn symbol(name: &str, address: u64) -> ExportedSymbol {
        ExportedSymbol {
            name: name.to_string(),
            address,
            address_hex: format!("0x{address:x}"),
            size: 4,
            binding: 1,
        }
    }

    fn hit(name: &str, address: u64, status: VerificationStatus) -> PatternHit {
        PatternHit {
            name: name.to_string(),
            section_offset: address,
            file_offset: address,
            address,
            address_hex: format!("0x{address:x}"),
            prologue_kind: None,
            prologue_instruction: None,
            verification_status: status,
        }
    }

    fn outcome(hits: Vec<PatternHit>) -> ScanOutcome {
        ScanOutcome {
            section: ".text".to_string(),
            scanned_bytes: 0,
            chunks: 0,
            workers: 0,
            hits,
            discarded: 0,
            verified: true,
            truncated: false,
            verification: None,
        }
    }

    fn symbols() -> SymbolTable {
        SymbolTable {
            total: 3,
            defined: 3,
            jni: vec![symbol("Java_a_B_c", 0x1000), symbol("Java_a_B_d", 0x2000)],
            entry_points: vec![symbol("JNI_OnLoad", 0x3000)],
            truncated: false,
        }
    }

    fn natives() -> NativeTable {
        NativeTable {
            entries: vec![RegisteredNative {
                java_name: "registeredNative".to_string(),
                signature: "(I)V".to_string(),
                address: 0x4000,
                address_hex: "0x4000".to_string(),
                class: Some("com/mojang/Foo".to_string()),
            }],
            tables: 1,
            truncated: false,
        }
    }

    #[test]
    fn every_source_contributes_to_the_union() {
        let report = build_report(
            &outcome(vec![hit(
                "pattern_one",
                0x5000,
                VerificationStatus::Verified,
            )]),
            &symbols(),
            &natives(),
            &["pattern_one".to_string()],
        );
        // 1 registered + 2 exports + 1 entry point + 1 pattern
        assert_eq!(report.offsets.len(), 5);
        assert_eq!(report.coverage.union, 5);
        assert_eq!(report.coverage.registered, 1);
        assert_eq!(report.coverage.exports, 2);
        assert_eq!(report.coverage.entry_points, 1);
    }

    #[test]
    fn a_duplicate_name_and_address_is_kept_once() {
        let report = build_report(
            &outcome(vec![hit(
                "Java_a_B_c",
                0x1000,
                VerificationStatus::Verified,
            )]),
            &symbols(),
            &NativeTable::default(),
            &[],
        );
        // The symbol wins: same name, same address.
        assert_eq!(report.offsets.len(), 3);
        let entry = report
            .offsets
            .iter()
            .find(|offset| offset.java_name == "Java_a_B_c")
            .expect("entry");
        assert_eq!(entry.source, JniSource::Symbol);
    }

    #[test]
    fn a_pattern_without_hits_but_with_a_symbol_is_stale() {
        let report = build_report(
            &outcome(vec![]),
            &symbols(),
            &NativeTable::default(),
            &["Java_a_B_c".to_string(), "pattern_missing".to_string()],
        );
        assert_eq!(report.patterns[0].status, PatternOutcome::SymbolOnly);
        assert_eq!(report.patterns[1].status, PatternOutcome::NotFound);
        assert_eq!(report.coverage.patterns_stale, vec!["Java_a_B_c"]);
        assert_eq!(report.coverage.patterns_not_found, 1);
    }

    #[test]
    fn two_hits_without_a_symbol_are_ambiguous() {
        let report = build_report(
            &outcome(vec![
                hit("pattern_two", 0x10, VerificationStatus::Unverified),
                hit("pattern_two", 0x20, VerificationStatus::Unverified),
            ]),
            &SymbolTable::default(),
            &NativeTable::default(),
            &["pattern_two".to_string()],
        );
        assert_eq!(report.patterns[0].status, PatternOutcome::Ambiguous);
        assert_eq!(report.patterns[0].hits, 2);
        assert_eq!(report.coverage.patterns_ambiguous, 1);
        // Both candidates stay in the union: nothing is dropped.
        assert_eq!(report.offsets.len(), 2);
    }

    #[test]
    fn a_conflicting_hit_is_low_confidence_but_still_present() {
        let report = build_report(
            &outcome(vec![hit(
                "pattern_three",
                0x7000,
                VerificationStatus::Conflict,
            )]),
            &symbols(),
            &NativeTable::default(),
            &["pattern_three".to_string()],
        );
        let entry = report
            .offsets
            .iter()
            .find(|offset| offset.java_name == "pattern_three")
            .expect("entry");
        assert_eq!(entry.confidence, JniConfidence::Low);
        assert_eq!(entry.source, JniSource::Pattern);
    }

    #[test]
    fn the_union_is_sorted_by_name_then_address() {
        let report = build_report(
            &outcome(vec![
                hit("zzz", 0x10, VerificationStatus::Unverified),
                hit("aaa", 0x20, VerificationStatus::Unverified),
            ]),
            &symbols(),
            &natives(),
            &[],
        );
        let names: Vec<&str> = report
            .offsets
            .iter()
            .map(|offset| offset.java_name.as_str())
            .collect();
        let mut sorted = names.clone();
        sorted.sort_unstable();
        assert_eq!(names, sorted);
    }

    #[test]
    fn a_truncated_source_is_reported() {
        let mut table = natives();
        table.truncated = true;
        let report = build_report(&outcome(vec![]), &symbols(), &table, &[]);
        assert!(report.coverage.truncated);
    }
}
