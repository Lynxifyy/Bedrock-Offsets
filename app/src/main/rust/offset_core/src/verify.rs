//! Cross-checks scanned offsets against the library's own symbol table.
//!
//! A byte signature and an exported symbol are two independent ways of naming
//! the same address. When a signature's name is also an exported symbol, the
//! two addresses can be compared:
//!
//! * They agree, or differ only by the small constant that separates a symbol
//!   from its signature, so the offset is [`VerificationStatus::Verified`].
//! * They disagree, so the signature has drifted and the offset is
//!   [`VerificationStatus::Conflict`]. Reporting a wrong offset as correct is
//!   the most expensive mistake this tool can make, so a conflict is loud.
//! * The library exports no such symbol, which is the normal case for a
//!   stripped binary, so the offset is [`VerificationStatus::Unverified`].

use serde::Serialize;

use crate::scanner::ScanOutcome;
use crate::symbols::SymbolTable;

/// How well a scanned offset agrees with the library's symbol table.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum VerificationStatus {
    /// The signature and an exported symbol with the same name agree.
    Verified,
    /// An exported symbol with the same name disagrees with the signature.
    Conflict,
    /// No exported symbol carries the signature's name, so nothing could be
    /// compared. This is the expected outcome for a stripped library.
    Unverified,
}

/// Largest address difference still treated as agreement.
///
/// A signature frequently points a few bytes into the function it identifies
/// (after the prologue push, or at a specific instruction), and a symbol may
/// name the function entry rather than the code the pattern was written
/// against. Four bytes covers that drift without accepting a genuinely
/// different location.
pub const SYMBOL_MATCH_TOLERANCE_BYTES: u64 = 4;

/// Counts describing how many offsets were verified, conflicted or neither.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
pub struct VerificationSummary {
    /// Offsets whose signature agreed with a symbol.
    pub verified: usize,
    /// Offsets whose signature disagreed with a symbol.
    pub conflicts: usize,
    /// Offsets with no symbol to compare against.
    pub unverified: usize,
    /// Whether the library exported any symbol at all.
    pub symbols_available: bool,
}

impl VerificationSummary {
    /// Total number of offsets considered.
    pub fn total(&self) -> usize {
        self.verified + self.conflicts + self.unverified
    }

    /// Fraction of offsets that were verified, in the range `0.0..=1.0`.
    ///
    /// Returns `0.0` when nothing was compared, so a stripped library reports
    /// no confidence rather than a misleading full score.
    pub fn confidence(&self) -> f64 {
        let total = self.total();
        if total == 0 {
            return 0.0;
        }
        self.verified as f64 / total as f64
    }
}

/// Verifies every hit in `outcome` against `symbols`.
///
/// The comparison is by name: a hit whose pattern name is also an exported
/// symbol name is compared, everything else is left unverified.
pub fn verify_against_symbols(
    outcome: &mut ScanOutcome,
    symbols: &SymbolTable,
) -> VerificationSummary {
    let mut summary = VerificationSummary {
        verified: 0,
        conflicts: 0,
        unverified: 0,
        symbols_available: !symbols.jni.is_empty(),
    };

    for hit in &mut outcome.hits {
        let Some(symbol) = symbols.jni.iter().find(|symbol| symbol.name == hit.name) else {
            hit.verification_status = VerificationStatus::Unverified;
            summary.unverified += 1;
            continue;
        };

        let difference = hit.address.abs_diff(symbol.address);
        if difference <= SYMBOL_MATCH_TOLERANCE_BYTES {
            hit.verification_status = VerificationStatus::Verified;
            summary.verified += 1;
        } else {
            hit.verification_status = VerificationStatus::Conflict;
            summary.conflicts += 1;
        }
    }

    summary
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::scanner::PatternHit;
    use crate::symbols::ExportedSymbol;

    fn hit(name: &str, address: u64) -> PatternHit {
        PatternHit {
            name: name.to_string(),
            section_offset: address,
            file_offset: address,
            address,
            address_hex: format!("0x{address:x}"),
            prologue_kind: None,
            prologue_instruction: None,
            verification_status: VerificationStatus::Unverified,
        }
    }

    fn symbol(name: &str, address: u64) -> ExportedSymbol {
        ExportedSymbol {
            name: name.to_string(),
            address,
            address_hex: format!("0x{address:x}"),
            size: 0,
            binding: 1,
        }
    }

    fn outcome_of(hits: Vec<PatternHit>) -> ScanOutcome {
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

    fn table_of(symbols: Vec<ExportedSymbol>) -> SymbolTable {
        SymbolTable {
            total: symbols.len(),
            defined: symbols.len(),
            jni: symbols,
            truncated: false,
        }
    }

    #[test]
    fn an_exact_symbol_match_is_verified() {
        let mut outcome = outcome_of(vec![hit("clientInstance", 0x401000)]);
        let table = table_of(vec![symbol("clientInstance", 0x401000)]);

        let summary = verify_against_symbols(&mut outcome, &table);

        assert_eq!(summary.verified, 1);
        assert_eq!(summary.conflicts, 0);
        assert_eq!(
            outcome.hits[0].verification_status,
            VerificationStatus::Verified
        );
    }

    #[test]
    fn a_difference_within_tolerance_is_still_verified() {
        let mut outcome = outcome_of(vec![hit("clientInstance", 0x401004)]);
        let table = table_of(vec![symbol("clientInstance", 0x401000)]);

        let summary = verify_against_symbols(&mut outcome, &table);

        assert_eq!(summary.verified, 1);
        assert_eq!(
            outcome.hits[0].verification_status,
            VerificationStatus::Verified
        );
    }

    #[test]
    fn a_difference_beyond_tolerance_is_a_conflict() {
        let mut outcome = outcome_of(vec![hit("clientInstance", 0x401010)]);
        let table = table_of(vec![symbol("clientInstance", 0x401000)]);

        let summary = verify_against_symbols(&mut outcome, &table);

        assert_eq!(summary.conflicts, 1);
        assert_eq!(
            outcome.hits[0].verification_status,
            VerificationStatus::Conflict
        );
    }

    #[test]
    fn a_signature_without_a_symbol_is_unverified() {
        let mut outcome = outcome_of(vec![hit("localOnly", 0x401000)]);
        let table = table_of(vec![symbol("clientInstance", 0x401000)]);

        let summary = verify_against_symbols(&mut outcome, &table);

        assert_eq!(summary.unverified, 1);
        assert!(summary.symbols_available);
        assert_eq!(
            outcome.hits[0].verification_status,
            VerificationStatus::Unverified
        );
    }

    #[test]
    fn a_stripped_library_reports_no_confidence() {
        let mut outcome = outcome_of(vec![hit("clientInstance", 0x401000)]);
        let table = table_of(Vec::new());

        let summary = verify_against_symbols(&mut outcome, &table);

        assert!(!summary.symbols_available);
        assert_eq!(summary.unverified, 1);
        assert_eq!(summary.confidence(), 0.0);
    }

    #[test]
    fn confidence_is_the_verified_fraction() {
        let summary = VerificationSummary {
            verified: 3,
            conflicts: 1,
            unverified: 0,
            symbols_available: true,
        };

        assert_eq!(summary.total(), 4);
        assert!((summary.confidence() - 0.75).abs() < f64::EPSILON);
    }

    #[test]
    fn an_empty_outcome_has_no_confidence() {
        let summary = VerificationSummary {
            verified: 0,
            conflicts: 0,
            unverified: 0,
            symbols_available: false,
        };

        assert_eq!(summary.confidence(), 0.0);
    }

    #[test]
    fn every_hit_is_classified_even_in_a_mixed_batch() {
        let mut outcome = outcome_of(vec![
            hit("agrees", 0x401000),
            hit("disagrees", 0x401000),
            hit("unknown", 0x401000),
        ]);
        let table = table_of(vec![
            symbol("agrees", 0x401000),
            symbol("disagrees", 0x500000),
        ]);

        let summary = verify_against_symbols(&mut outcome, &table);

        assert_eq!(summary.verified, 1);
        assert_eq!(summary.conflicts, 1);
        assert_eq!(summary.unverified, 1);
        assert_eq!(summary.total(), 3);
    }
}
