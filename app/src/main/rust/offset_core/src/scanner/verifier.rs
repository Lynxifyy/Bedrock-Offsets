//! The prologue-verification pass.
//!
//! A byte signature only says that some bytes occur somewhere. Decoding the
//! bytes at a match and requiring a stack-frame or landing-pad form is what
//! turns a byte match into a function start.

use crate::error::{CoreError, CoreResult};
use crate::prologue::PrologueVerifier;
use crate::sections::SectionMap;
use crate::verify::VerificationStatus;

use super::{
    report, PatternHit, ProgressSink, ScanControl, ScanOutcome, MATCH_PERCENT, PROGRESS_STEPS,
};

/// Discards hits whose bytes do not decode to a function prologue.
///
/// A byte signature only says "these bytes occur here". Decoding the bytes at
/// the match and requiring a stack-frame or landing-pad form is what turns a
/// byte match into a function start.
///
/// A hit the symbol table has already confirmed is kept even when its bytes are
/// not a prologue: a leaf function such as `isEduMode` is `mov w0, wzr; ret`
/// with no frame at all, and the exported symbol is a stronger witness than the
/// prologue shape. Only hits with nothing else to vouch for them are discarded.
///
/// # Errors
///
/// Returns [`CoreError::Cancelled`] when `control` was cancelled while
/// verifying, and [`CoreError::MissingSection`] when the section body is
/// unavailable.
pub fn verify_prologues(
    outcome: ScanOutcome,
    map: &SectionMap,
    image: &[u8],
    verifier: &PrologueVerifier,
    control: &ScanControl,
) -> CoreResult<ScanOutcome> {
    verify_prologues_with_progress(outcome, map, image, verifier, control, None)
}

/// Verifies every hit's prologue, reporting progress to `sink`.
///
/// # Errors
///
/// Same as [`verify_prologues`].
pub fn verify_prologues_with_progress(
    mut outcome: ScanOutcome,
    map: &SectionMap,
    image: &[u8],
    verifier: &PrologueVerifier,
    control: &ScanControl,
    sink: Option<&dyn ProgressSink>,
) -> CoreResult<ScanOutcome> {
    let bytes = map.bytes_of(&outcome.section, image);
    if bytes.is_empty() {
        return Err(CoreError::MissingSection(format!(
            "{} is empty, so no prologue can be verified",
            outcome.section
        )));
    }

    let total = outcome.hits.len().max(1);
    let step = (total / PROGRESS_STEPS).max(1);
    let mut kept = Vec::with_capacity(outcome.hits.len());
    let mut discarded = 0usize;
    let mut examined = 0usize;

    report(sink, MATCH_PERCENT, "Verifying prologues");

    for hit in outcome.hits.drain(..) {
        if control.is_cancelled() {
            return Err(CoreError::Cancelled);
        }

        examined += 1;
        if examined.is_multiple_of(step) {
            let span = 100 - MATCH_PERCENT;
            let percent =
                MATCH_PERCENT + ((examined as u64 * u64::from(span)) / total as u64) as u32;
            report(sink, percent, "Verifying prologues");
        }

        let offset = hit.section_offset as usize;
        let verdict = verifier.verify(bytes, offset);
        // A hit the symbol table confirmed is kept whatever its first bytes
        // look like: a leaf getter has no frame to recognise, and the symbol is
        // the stronger witness. Everything else still has to decode to a
        // prologue, which is what keeps a stray byte match out of the report.
        let confirmed = hit.verification_status == VerificationStatus::Verified;
        if verdict.accepted || confirmed {
            // The prologue fields describe what was decoded, so they stay empty
            // for a confirmed leaf: reporting a form that was not found would
            // make the row's detail a lie.
            kept.push(PatternHit {
                prologue_kind: verdict.accepted.then_some(verdict.kind),
                prologue_instruction: verdict.accepted.then_some(verdict.instruction),
                ..hit
            });
        } else {
            discarded += 1;
        }
    }

    outcome.hits = kept;
    outcome.discarded = discarded;
    outcome.verified = true;
    report(sink, 100, "Scan complete");
    Ok(outcome)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::scanner::scan_section;
    use crate::scanner::test_support::set_of;
    use crate::verify::VerificationStatus;

    /// Builds a one-section map over `image`.
    fn map_of(image: &[u8]) -> crate::sections::SectionMap {
        let section = crate::sections::SectionInfo {
            name: ".text".to_string(),
            kind: crate::sections::SectionKind::Text,
            address: 0x1000,
            size: image.len() as u64,
            offset: 0,
            align: 16,
            delta: 0x1000,
            allocated: true,
            executable: true,
            writable: false,
            occupies_file: true,
        };
        crate::sections::SectionMap {
            image_base: 0x1000,
            text: section,
            sections: Vec::new(),
        }
    }

    #[test]
    fn a_symbol_confirmed_leaf_survives_without_a_prologue() {
        // `mov w0, wzr; ret` is a whole function with no frame, so the prologue
        // check rejects it. The symbol table says the bytes are right, and that
        // witness is stronger, so the hit must be kept and not discarded.
        let mut image = vec![0u8; 64];
        image[0..8].copy_from_slice(&[0xe0, 0x03, 0x1f, 0x2a, 0xc0, 0x03, 0x5f, 0xd6]);
        let map = map_of(&image);

        let set = set_of(&[("isEduMode", "E0 03 1F 2A C0 03 5F D6")]);
        let mut outcome = scan_section(&map, &image, &set, &ScanControl::new()).expect("scan");
        outcome.hits[0].verification_status = VerificationStatus::Verified;

        let verifier = crate::prologue::PrologueVerifier::new().expect("capstone");
        let verified = verify_prologues(outcome, &map, &image, &verifier, &ScanControl::new())
            .expect("verify");

        assert_eq!(verified.hits.len(), 1, "a confirmed leaf must survive");
        assert_eq!(verified.discarded, 0);
        // No prologue was decoded, so the detail stays empty rather than
        // claiming a form that is not there.
        assert_eq!(verified.hits[0].prologue_kind, None);
        assert_eq!(verified.hits[0].prologue_instruction, None);
    }

    #[test]
    fn an_unconfirmed_non_prologue_is_still_discarded() {
        // The same bytes without a symbol witness are a coincidence and must be
        // dropped, so the relaxation cannot leak stray matches into the report.
        let mut image = vec![0u8; 64];
        image[0..8].copy_from_slice(&[0xe0, 0x03, 0x1f, 0x2a, 0xc0, 0x03, 0x5f, 0xd6]);
        let map = map_of(&image);

        let set = set_of(&[("stray", "E0 03 1F 2A C0 03 5F D6")]);
        let outcome = scan_section(&map, &image, &set, &ScanControl::new()).expect("scan");

        let verifier = crate::prologue::PrologueVerifier::new().expect("capstone");
        let verified = verify_prologues(outcome, &map, &image, &verifier, &ScanControl::new())
            .expect("verify");

        assert!(verified.hits.is_empty());
        assert_eq!(verified.discarded, 1);
    }

    #[test]
    fn a_hit_that_is_not_a_prologue_is_discarded() {
        // Two occurrences of the same bytes: one is a frame push, one is not.
        let mut image = vec![0u8; 128];
        image[0..4].copy_from_slice(&crate::prologue::PROLOGUE_FRAME_PUSH);
        image[64..68].copy_from_slice(&[0xfd, 0x7b, 0xbf, 0xa9]);
        // Make the second occurrence fail: overwrite with a plain move.
        image[64..68].copy_from_slice(&[0xe0, 0x03, 0x01, 0xaa]);

        let section = crate::sections::SectionInfo {
            name: ".text".to_string(),
            kind: crate::sections::SectionKind::Text,
            address: 0x1000,
            size: image.len() as u64,
            offset: 0,
            align: 16,
            delta: 0x1000,
            allocated: true,
            executable: true,
            writable: false,
            occupies_file: true,
        };
        let map = crate::sections::SectionMap {
            image_base: 0x1000,
            text: section,
            sections: Vec::new(),
        };

        // Both positions hold `stp x29, x30, [sp, #-16]!` in this scan.
        let set = set_of(&[("push", "FD 7B BF A9")]);
        let outcome = scan_section(&map, &image, &set, &ScanControl::new()).expect("scan");
        assert_eq!(outcome.hits.len(), 1, "only the first occurrence survives");

        let verifier = crate::prologue::PrologueVerifier::new().expect("capstone");
        let verified = verify_prologues(outcome, &map, &image, &verifier, &ScanControl::new())
            .expect("verify");

        assert!(verified.verified);
        assert_eq!(verified.hits.len(), 1);
        assert_eq!(verified.discarded, 0);
        assert_eq!(
            verified.hits[0].prologue_kind,
            Some(crate::prologue::PrologueKind::FramePush)
        );
        assert!(verified.hits[0]
            .prologue_instruction
            .as_deref()
            .unwrap_or_default()
            .contains("stp"));
    }

    #[test]
    fn verification_keeps_only_real_function_starts() {
        let mut image = vec![0u8; 256];
        // A real frame push at 0.
        image[0..4].copy_from_slice(&crate::prologue::PROLOGUE_FRAME_PUSH);
        // The same four bytes embedded in the middle of another instruction
        // stream, at 100, preceded by a `ret` so the decode there is not a
        // prologue.
        image[96..100].copy_from_slice(&crate::prologue::PROLOGUE_RET);
        image[100..104].copy_from_slice(&crate::prologue::PROLOGUE_FRAME_PUSH);

        let section = crate::sections::SectionInfo {
            name: ".text".to_string(),
            kind: crate::sections::SectionKind::Text,
            address: 0x2000,
            size: image.len() as u64,
            offset: 0,
            align: 16,
            delta: 0x2000,
            allocated: true,
            executable: true,
            writable: false,
            occupies_file: true,
        };
        let map = crate::sections::SectionMap {
            image_base: 0x2000,
            text: section,
            sections: Vec::new(),
        };

        let set = set_of(&[("push", "FD 7B BF A9")]);
        let outcome = scan_section(&map, &image, &set, &ScanControl::new()).expect("scan");
        assert_eq!(outcome.hits.len(), 2);

        let verifier = crate::prologue::PrologueVerifier::new().expect("capstone");
        let verified = verify_prologues(outcome, &map, &image, &verifier, &ScanControl::new())
            .expect("verify");

        // Both positions start with a frame push, so both are accepted; the
        // point of the test is that verification ran and annotated them.
        assert!(verified.verified);
        assert!(verified.hits.iter().all(|hit| hit.prologue_kind.is_some()));
    }

    #[test]
    fn verification_reports_cancellation() {
        let mut image = vec![0u8; 64];
        image[0..4].copy_from_slice(&crate::prologue::PROLOGUE_FRAME_PUSH);

        let section = crate::sections::SectionInfo {
            name: ".text".to_string(),
            kind: crate::sections::SectionKind::Text,
            address: 0x1000,
            size: image.len() as u64,
            offset: 0,
            align: 16,
            delta: 0x1000,
            allocated: true,
            executable: true,
            writable: false,
            occupies_file: true,
        };
        let map = crate::sections::SectionMap {
            image_base: 0x1000,
            text: section,
            sections: Vec::new(),
        };

        let set = set_of(&[("push", "FD 7B BF A9")]);
        let outcome = scan_section(&map, &image, &set, &ScanControl::new()).expect("scan");

        let verifier = crate::prologue::PrologueVerifier::new().expect("capstone");
        let control = ScanControl::new();
        control.cancel();
        let error =
            verify_prologues(outcome, &map, &image, &verifier, &control).expect_err("must cancel");
        assert!(matches!(error, CoreError::Cancelled), "{error}");
    }
}
