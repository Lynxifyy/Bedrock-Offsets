//! Complete internal C++ extraction: every vtable slot and every function.
//!
//! The library is stripped, so most functions have no name in the binary. This
//! module therefore recovers *addresses* completely and names whatever the
//! binary still lets it derive:
//!
//! * **Pass A** reads every slot of every resolved RTTI vtable through the
//!   relocation table, and parses the `type_info` object for base classes.
//! * **Pass B** parses `.eh_frame` (and `.eh_frame_hdr`) into the authoritative
//!   function list, falling back to a prologue walk when the tables are absent.
//! * **Pass C** merges symbols, JNI exports, vtable slots, signatures and string
//!   anchors into one address-keyed table, choosing the best name per address.
//! * **Pass D** applies hand-maintained internal signatures with wildcard
//!   support and the `self`/`bl_target`/`adrp_add`/`prologue_back` resolvers.
//! * **Pass E** resolves `.rodata` string anchors through `ADRP`+`ADD`/`LDR`
//!   cross-references.
//!
//! Results are streamed as NDJSON so a table of several hundred thousand
//! functions never has to be materialised as one JSON string.

use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::io::Write;
use std::sync::Arc;

use serde::{Deserialize, Serialize};

use crate::error::{CoreError, CoreResult};
use crate::patterns::PatternSet;
use crate::prologue::{PrologueKind, PrologueVerifier};
use crate::rtti::{read_u64, RelocationIndex, TypeInfoKind, VtableMap};
use crate::scanner::{scan_section_releasing_pages, PageReleaser, ProgressSink, ScanControl};
use crate::sections::{SectionMap, RODATA_SECTION, TEXT_SECTION};
use crate::shards::{NdjsonWriter, RecordWriter};
use crate::signature::Signature;
use crate::symbols::{find_symbol_address, DataSymbol, NamedSymbol};

/// Hard sanity limit on the slots read for one class.
///
/// The cap the user picks in Settings is clamped to this, so it has to be at
/// least as large as the largest value the UI offers (16384); a value the user
/// can select but the engine silently reduces is a bug, not a safeguard.
pub const MAX_SLOTS_PER_CLASS: usize = 65_536;

/// Default cap on the slots read for one class.
pub const DEFAULT_SLOTS_PER_CLASS: usize = 4096;

/// Bytes sampled from a function when auto-generating a signature.
pub const MAX_SIGNATURE_BYTES: usize = 48;

/// Upper bound the signature generator extends a pattern to before giving up
/// on uniqueness.
pub const MAX_SIGNATURE_EXTENDED_BYTES: usize = 512;

/// Bytes the signature pass may hold for the patterns it is still extending.
///
/// Reaching further is what makes a signature survive a game update, so the
/// budget is generous: at 192 MB roughly 340,000 unresolved patterns can still
/// reach 512 bytes. What it prevents is the case a real 26.60 library hits — a
/// few hundred thousand candidates times 512 bytes of pattern, on a tablet that
/// then kills the process at the 112-byte round.
pub const SIGNATURE_MEMORY_BUDGET: usize = 192 * 1024 * 1024;

/// Default cap on auto-generated signatures.
///
/// The pass generates one signature per symbol-named function and per vtable
/// slot, which is a few hundred thousand on a real library, so the default is
/// effectively "no cap" while still bounding a corrupt input.
pub const DEFAULT_MAX_SIGNATURES: usize = 1_000_000;

/// Number of bytes before a vtable's first function pointer.
const VTABLE_PREFIX_BYTES: u64 = 16;

/// Signature-generation options and the hand-maintained packs.
#[derive(Debug, Clone, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct InternalOptions {
    /// Whether to enumerate every function from `.eh_frame`.
    pub include_all_functions: bool,
    /// Whether to generate and verify a signature per named function.
    pub generate_signatures: bool,
    /// Hard cap on slots read for one class.
    pub max_slots_per_class: usize,
    /// Cap on auto-generated signatures.
    pub max_signatures: usize,
    /// Hand-maintained internal signatures.
    pub internal_patterns: Vec<InternalPattern>,
    /// `.rodata` string anchors.
    pub string_anchors: Vec<StringAnchor>,
    /// Category rules; when present the engine writes one JSON document per
    /// category instead of a single NDJSON table.
    pub categories: Option<serde_json::Value>,
    /// Extra top-level fields for a category document, already serialised.
    pub source_header: String,
}

impl Default for InternalOptions {
    fn default() -> Self {
        Self {
            include_all_functions: true,
            generate_signatures: true,
            max_slots_per_class: DEFAULT_SLOTS_PER_CLASS,
            max_signatures: DEFAULT_MAX_SIGNATURES,
            internal_patterns: Vec::new(),
            string_anchors: Vec::new(),
            categories: None,
            source_header: String::new(),
        }
    }
}

/// One hand-maintained internal signature.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InternalPattern {
    /// Name reported for the resolved address.
    pub name: String,
    /// Space-separated hex bytes with `??` wildcards.
    pub pattern: String,
    /// How the match is turned into an address.
    #[serde(default = "default_resolve")]
    pub resolve: String,
    /// Byte adjustment applied to a `self` match.
    #[serde(default)]
    pub offset: i64,
}

/// One `.rodata` string anchor.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StringAnchor {
    /// Name reported for the enclosing function.
    pub name: String,
    /// Unique string whose address the function materialises.
    pub string: String,
}

fn default_resolve() -> String {
    "self".to_string()
}

/// Name for a function the engine cannot identify: its RVA from the load base.
///
/// The name has to agree with the row's own `address` field, or a modder who
/// pastes `sub_9cbf81c` into a disassembler lands somewhere else. Both are
/// derived from [`SectionMap::image_base`], which is the lowest `PT_LOAD`
/// address — not the lowest allocated section, which sits above the header area
/// (`.gnu.hash` at 0x2463f0 in the 26.60 library).
fn auto_name(address: u64, image_base: u64) -> String {
    format!("sub_{:x}", address.saturating_sub(image_base))
}

/// Counts and the path of the streamed table.
#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InternalSummary {
    /// Number of RTTI classes.
    pub classes: usize,
    /// Number of virtual slots across every class.
    pub vtable_slots: usize,
    /// Number of functions in the table.
    pub functions: usize,
    /// Functions with a derived name.
    pub named: usize,
    /// Functions named `sub_<hex>`.
    pub auto_named: usize,
    /// Functions confirmed by a second source.
    pub verified: usize,
    /// Signatures or anchors with more than one candidate.
    pub ambiguous: usize,
    /// Signatures or anchors with no candidate.
    pub not_found: usize,
    /// Auto-generated signatures.
    pub signatures: usize,
    /// Auto-generated signatures that matched exactly one function start.
    pub signatures_unique: usize,
    /// Virtual slots whose function has no size.
    pub slots_with_size_zero: usize,
    /// Data symbols moved out of the function table.
    pub data_symbols: usize,
    /// Whether the signature cap truncated generation.
    pub truncated_signatures: bool,
    /// Absolute path of the NDJSON table, filled in by the JNI layer.
    pub path: String,
    /// Size of the NDJSON table on disk, filled in by the JNI layer.
    pub table_bytes: u64,
    /// Records written: classes plus functions plus data symbols.
    pub rows: usize,
    /// One entry per category file, empty for a single-table run.
    #[serde(default)]
    pub shards: Vec<ShardSummary>,
    /// Absolute path of the class-to-category map, empty for a table run.
    #[serde(default)]
    pub class_map_path: String,
    /// Whether the engine classified the records itself.
    #[serde(default)]
    pub categories_applied: bool,
    /// Whether the per-class slot cap cut any class.
    #[serde(default)]
    pub truncated_slots: bool,
    /// Number of classes the slot cap cut.
    #[serde(default)]
    pub truncated_slot_classes: usize,
    /// Classes whose slot run stopped at the per-class cap.
    #[serde(default)]
    pub truncated_slot_names: Vec<String>,
    /// Completeness counts: functions against FDEs, and every cap.
    #[serde(default)]
    pub coverage: InternalCoverage,
    /// Engine version that wrote the table.
    ///
    /// A table written by another engine is not reusable: names, `fileOffset`
    /// presence and the load base can all differ, so the reader compares this
    /// against the engine that would consume it.
    #[serde(default)]
    pub engine_version: String,
    /// Load base the addresses in the table are relative to.
    ///
    /// Every `sub_<hex>` name and every `vtableOffset` is derived from it, so a
    /// reader that computes an RVA with a different base is off by the
    /// difference — the 0x2463f0 shift this field now makes detectable.
    #[serde(default)]
    pub image_base: u64,
}

/// How complete the internal half of a scan is.
///
/// The function list is supposed to be the set of `.eh_frame` FDEs: the unwind
/// table names every function the compiler emitted, so a count that disagrees
/// means a pass dropped rows. The two counts are both reported, with the
/// verdict, rather than one being asserted away.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InternalCoverage {
    /// Functions written to the table.
    pub functions: usize,
    /// Frames read from `.eh_frame`, before duplicate starts were merged.
    pub fde_functions: usize,
    /// FDE starts that are missing from the table; zero means nothing was
    /// dropped.
    pub fde_missing: usize,
    /// Whether every FDE start reached the table.
    pub functions_match_fdes: bool,
    /// RTTI classes written.
    pub classes: usize,
    /// Virtual slots written.
    pub slots: usize,
    /// Data symbols written.
    pub data_symbols: usize,
    /// `eh_frame`, `prologue_scan` or `symbols`.
    pub function_source: String,
    /// Whether any cap cut output.
    pub truncated: bool,
    /// Classes the per-class slot cap cut.
    pub truncated_slot_classes: usize,
    /// Whether the signature cap cut generation.
    pub truncated_signatures: bool,
    /// Functions whose address could not be converted to a file offset, so the
    /// record carries offset 0. A non-zero count means the section table does
    /// not cover an executable address, which is a real defect and must not be
    /// mistaken for "the function starts at file offset 0".
    #[serde(default)]
    pub functions_without_file_offset: usize,
}

/// One category file the engine wrote.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ShardSummary {
    /// File identifier, for example `block_placement` or `unnamed_part_02`.
    pub id: String,
    /// Logical category.
    pub category: String,
    /// Human-readable label.
    pub label: String,
    /// One-based part number.
    pub part: usize,
    /// Classes in the file.
    pub classes: usize,
    /// Functions in the file.
    pub functions: usize,
    /// Data symbols in the file.
    pub symbols: usize,
    /// Absolute path of the finished JSON document.
    pub path: String,
    /// Size of the finished JSON document on disk, in bytes.
    #[serde(default)]
    pub bytes: u64,
}

/// One virtual slot.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SlotRecord {
    /// Slot index.
    pub slot: usize,
    /// Byte offset of the slot from the first function pointer.
    #[serde(serialize_with = "hex_u64")]
    pub offset: u64,
    /// Virtual address of the function.
    #[serde(serialize_with = "hex_u64")]
    pub address: u64,
    /// File offset of the function. Absent when the address has no bytes in
    /// the file (a slot that points into `.bss`), so a consumer can never read
    /// a silent zero.
    #[serde(serialize_with = "hex_option", skip_serializing_if = "Option::is_none")]
    pub file_offset: Option<u64>,
    /// Function size when known.
    pub size: u64,
    /// Slot name.
    pub name: String,
    /// `virtual`, `pure_virtual`, `thunk` or `import`.
    pub kind: String,
    /// Which prologue form was recognised, or `leaf`/`none`.
    pub prologue_kind: String,
    /// Whether the slot differs from the first base's slot.
    pub overrides: bool,
    /// Whether the slot is identical to the first base's slot.
    pub inherited: bool,
    /// Verification status.
    pub status: String,
    /// Auto-generated signature, when one was produced.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub signature: Option<Arc<str>>,
    /// Whether the signature matched exactly once.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub signature_unique: Option<bool>,
    /// The exact signature form: raw bytes, two hex digits per byte.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub signature_hex: Option<Arc<str>>,
    /// The AND mask that belongs to `signatureHex`; `FF` means the byte must
    /// match, `00` means it is ignored, and anything between is a bit mask the
    /// scanner applies instead of a whole-byte wildcard.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub signature_mask: Option<Arc<str>>,
    /// How much of the signature is literal, as a percentage. A signature that
    /// keeps most of its bits survives a rebuild that moves code.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub signature_stability: Option<u8>,
    /// Locator for a function whose body is byte-identical to another's.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub signature_anchor: Option<Arc<str>>,
    /// Group of functions sharing one normalised body.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub identical_group: Option<u32>,
    /// Members of that group, including this function.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub identical_group_size: Option<u32>,
}

/// One RTTI class and its slots.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ClassRecord {
    /// Full demangled identity, template arguments included.
    pub name: String,
    /// Template-stripped family name, for example `entt::basic_storage`.
    pub base_name: String,
    /// Mangled name: the identity of this row.
    pub mangled: String,
    /// Base classes, most-derived first.
    pub bases: Vec<String>,
    /// The inheritance graph entry for each base: name, offset and ABI flags.
    #[serde(default)]
    pub base_classes: Vec<BaseRecord>,
    /// Virtual address of the vtable header.
    #[serde(serialize_with = "hex_u64")]
    pub vtable: u64,
    /// File offset of the vtable header, absent when the vtable has no bytes
    /// in the file. This is the number to patch or read at, unlike
    /// `vtableOffset`, which is measured from the image base.
    #[serde(serialize_with = "hex_option", skip_serializing_if = "Option::is_none")]
    pub file_offset: Option<u64>,
    /// Offset of the vtable from the image base (the lowest allocated
    /// section), which is neither a virtual address nor a file offset.
    #[serde(serialize_with = "hex_u64")]
    pub vtable_offset: u64,
    /// Virtual address of the `type_info` object.
    #[serde(serialize_with = "hex_u64")]
    pub type_info: u64,
    /// Number of slots in the primary group.
    pub slot_count: usize,
    /// The slots of the primary vtable group.
    ///
    /// Shared with the primary [`VtableGroup`] instead of copied: a class with
    /// a few hundred slots would otherwise hold every slot record twice.
    pub slots: Vec<Arc<SlotRecord>>,
    /// Every vtable group of the class, including the primary one.
    ///
    /// Multiple inheritance gives a class one group per base subobject; the
    /// primary group is the one the class pointer points at.
    pub vtable_groups: Vec<VtableGroup>,
}

/// One base class of an RTTI class.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BaseRecord {
    /// Demangled name of the base.
    pub name: String,
    /// Mangled identity of the base, which is what the graph is keyed by.
    pub mangled: String,
    /// Byte offset of the base subobject, decoded from the `__vmi` pair.
    pub offset: i64,
    /// `__vmi` flags word: bit 0 is `public`, bit 1 is `virtual`.
    pub flags: u32,
    /// Whether the base is public.
    pub is_public: bool,
    /// Whether the base is virtual.
    pub is_virtual: bool,
}

/// One vtable group of a class with multiple inheritance.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct VtableGroup {
    /// Offset from this subobject to the most-derived object.
    pub offset_to_top: i64,
    /// Virtual address of the group's vtable header.
    #[serde(serialize_with = "hex_u64")]
    pub address: u64,
    /// The group's virtual slots.
    pub slots: Vec<Arc<SlotRecord>>,
}

/// One function in the merged table.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FunctionRecord {
    /// Virtual address.
    #[serde(serialize_with = "hex_u64")]
    pub address: u64,
    /// File offset. Absent when the address has no bytes in the file.
    #[serde(serialize_with = "hex_option", skip_serializing_if = "Option::is_none")]
    pub file_offset: Option<u64>,
    /// Size in bytes.
    pub size: u64,
    /// Best name.
    pub name: String,
    /// Where the name came from.
    pub name_source: String,
    /// How sure the naming engine is: `certain`, `high`, `medium` or `low`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub confidence: Option<String>,
    /// Other names for the same address.
    pub aliases: Vec<String>,
    /// `function`, `thunk`, or `pure_virtual`.
    pub kind: String,
    /// Verification status.
    pub status: String,
    /// Which prologue form was recognised, or `leaf`/`none`.
    pub prologue_kind: String,
    /// Mangled name when the best name is demangled.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub mangled: Option<String>,
    /// Tail-call target for a thunk.
    #[serde(skip_serializing_if = "Option::is_none", serialize_with = "hex_option")]
    pub target: Option<u64>,
    /// Auto-generated signature.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub signature: Option<Arc<str>>,
    /// Whether the signature matched exactly once.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub signature_unique: Option<bool>,
    /// The exact signature form: raw bytes, two hex digits per byte.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub signature_hex: Option<Arc<str>>,
    /// The AND mask that belongs to `signatureHex`; `FF` means the byte must
    /// match, `00` means it is ignored, and anything between is a bit mask the
    /// scanner applies instead of a whole-byte wildcard.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub signature_mask: Option<Arc<str>>,
    /// How much of the signature is literal, as a percentage.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub signature_stability: Option<u8>,
    /// Locator for a function whose body is byte-identical to another's.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub signature_anchor: Option<Arc<str>>,
    /// Group of functions sharing one normalised body.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub identical_group: Option<u32>,
    /// Members of that group, including this function.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub identical_group_size: Option<u32>,
    /// Why the entry is marked `suspect`, when it is.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub suspect_reason: Option<String>,
}

/// One data symbol, kept out of the function table.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DataSymbolRecord {
    /// Virtual address.
    #[serde(serialize_with = "hex_u64")]
    pub address: u64,
    /// Size recorded by the linker, or zero.
    pub size: u64,
    /// Demangled name when possible.
    pub name: String,
    /// Mangled name as it appears in `.dynstr`.
    pub mangled: String,
    /// `data` for an object, `tls` for thread-local storage.
    pub kind: String,
    /// Why the symbol is data rather than a function.
    pub reason: String,
}

/// A named address and its provenance.
#[derive(Debug, Clone)]
struct NamedEntry {
    name: String,
    source: &'static str,
    confidence: Option<&'static str>,
    aliases: Vec<String>,
    kind: &'static str,
    mangled: Option<String>,
    status: Option<&'static str>,
}

/// Slots of one class, kept until inheritance flags can be compared.
struct ClassSlots {
    record: ClassRecord,
    addresses: Vec<u64>,
}

/// Reads every slot of every vtable and every function the binary exposes.
///
/// # Errors
///
/// Returns [`CoreError::Cancelled`] when `control` is cancelled, and any ELF or
/// I/O failure from the individual passes.
#[allow(clippy::too_many_arguments)]
pub fn analyze(
    image: &[u8],
    map: &SectionMap,
    vtables: &VtableMap,
    named_symbols: &[NamedSymbol],
    data_symbols: &[DataSymbol],
    options: &InternalOptions,
    control: &ScanControl,
    sink: Option<&dyn ProgressSink>,
    releaser: Option<&dyn PageReleaser>,
    out: &mut dyn Write,
) -> CoreResult<InternalSummary> {
    let mut writer = NdjsonWriter::new(out);
    analyze_with_writer(
        image,
        map,
        vtables,
        named_symbols,
        data_symbols,
        options,
        control,
        sink,
        releaser,
        &mut writer,
    )
}

/// Reads every slot of every vtable and every function the binary exposes.
///
/// The records go to `writer`, which either streams the NDJSON table or writes
/// one finished JSON document per category.
///
/// # Errors
///
/// Returns [`CoreError::Cancelled`] when `control` is cancelled, and any ELF or
/// I/O failure from the individual passes.
#[allow(clippy::too_many_arguments)]
pub fn analyze_with_writer(
    image: &[u8],
    map: &SectionMap,
    vtables: &VtableMap,
    named_symbols: &[NamedSymbol],
    data_symbols: &[DataSymbol],
    options: &InternalOptions,
    control: &ScanControl,
    sink: Option<&dyn ProgressSink>,
    releaser: Option<&dyn PageReleaser>,
    writer: &mut dyn RecordWriter,
) -> CoreResult<InternalSummary> {
    report(sink, 0, "Pass A: reading vtable slots");
    let relocation = RelocationIndex::build(image)?;
    let pure_virtual = find_symbol_address(image, "__cxa_pure_virtual");
    let (mut classes, truncated_slot_names) = collect_class_slots(
        map,
        image,
        vtables,
        &relocation,
        pure_virtual,
        options.max_slots_per_class,
        control,
    )?;

    report(sink, 15, "Pass A: resolving base classes");
    let bases = resolve_bases(map, image, &relocation, vtables);
    apply_inheritance(&mut classes, &bases);
    unify_slot_names_by_address(&mut classes);
    // The relocation index is a million-entry sorted table; nothing after Pass A
    // reads it, so releasing it here halves the resident cost of the whole
    // analysis instead of letting it overlap with the function and signature
    // passes.
    drop(relocation);

    check_cancelled(control)?;
    report(sink, 30, "Pass B: reading .eh_frame");
    let (mut functions, function_source, fde_functions) = if options.include_all_functions {
        collect_functions(map, image, control)?
    } else {
        (Vec::new(), "prologue_scan", 0)
    };
    // `.eh_frame` records are not guaranteed to be in address order. Sorting
    // here makes every later pass (signature scan, merge, write) walk `.text`
    // from low to high, which is what lets the page releaser actually drop the
    // pages behind the cursor instead of re-faulting the whole library.
    functions.sort_unstable_by_key(|(address, _)| *address);
    report(
        sink,
        45,
        &format!("Pass B: {} functions ({function_source})", functions.len()),
    );

    check_cancelled(control)?;
    report(sink, 50, "Pass C: merging names");
    let mut names: HashMap<u64, NamedEntry> = HashMap::new();
    insert_symbol_names(&mut names, named_symbols);
    insert_slot_names(map, &mut names, &classes);

    // The naming engine needs the cross-reference table: a lambda's identity
    // lives in the function that constructs its vtable, and the adapter's tick
    // function is the caller of its call operator.
    report(sink, 55, "Pass C: cross-references and evidence names");
    let xrefs = crate::xref::scan(map, image, &functions, control)?;
    let slots_by_vtable = slots_by_vtable_address(&classes);
    let lambda_evidence = crate::naming::lambda_slot_names(vtables, &slots_by_vtable);
    let xref_evidence = crate::naming::vtable_xref_names(vtables, &xrefs)?;
    let systems = crate::naming::ecs_systems(vtables, &slots_by_vtable);
    let body_evidence = crate::naming::lambda_body_names(vtables, &slots_by_vtable, &xrefs);
    let system_evidence = crate::naming::ecs_system_names(&systems);
    let mut evidence_counts = EvidenceCounts::default();
    for evidence in lambda_evidence
        .iter()
        .chain(&body_evidence)
        .chain(&xref_evidence)
        .chain(&system_evidence)
    {
        insert_name(
            &mut names,
            evidence.address,
            evidence.name.clone(),
            evidence.source.as_str(),
            "function",
            None,
            None,
            Some(evidence.confidence.as_str()),
        );
        evidence_counts.bump(evidence.source);
    }

    check_cancelled(control)?;
    report(sink, 60, "Pass D/E: internal signatures and string anchors");
    let mut ambiguous = 0usize;
    let mut not_found = 0usize;
    apply_internal_patterns(
        map,
        image,
        options,
        control,
        sink,
        releaser,
        &functions,
        &mut names,
        &mut ambiguous,
        &mut not_found,
    )?;
    apply_string_anchors(
        map,
        image,
        options,
        &functions,
        &mut names,
        &mut ambiguous,
        &mut not_found,
        releaser,
    );

    check_cancelled(control)?;
    if let Some(releaser) = releaser {
        // Pass D/E walked `.text` and the vtable pass touched `.data.rel.ro`.
        // Drop everything before the signature pass faults it back in, so the
        // two scans do not stack their resident pages.
        releaser.release(0, usize::MAX);
    }
    report(sink, 80, "Signatures: generating");
    let (mut signatures, truncated_signatures, _signature_stats) = if options.generate_signatures {
        generate_signatures(
            map,
            image,
            &names,
            &functions,
            &classes,
            &xrefs,
            options.max_signatures,
            control,
            sink,
            releaser,
        )?
    } else {
        (
            HashMap::new(),
            false,
            crate::signature::PlanStats::default(),
        )
    };

    check_cancelled(control)?;
    if let Some(releaser) = releaser {
        // The signature pass walked the whole `.text`; drop those pages before
        // the write pass faults them back in one function at a time.
        releaser.release(0, usize::MAX);
    }
    // Nothing after the signature pass reads the cross-reference table. Its
    // edge arenas are the largest single allocation of the run, so releasing
    // them here keeps them out of the write pass peak.
    drop(xrefs);
    report(sink, 90, "Writing the internal table");
    let (addresses, suspect) = merge_addresses(&functions, &names);
    // Every FDE names a function, so every FDE start must appear in the table.
    // Extra addresses are expected (PLT stubs, thunks and exports have no
    // frame), a missing one is not: it means a pass dropped a row.
    let fde_missing = functions
        .iter()
        .filter(|(start, _)| addresses.binary_search(start).is_err())
        .count();
    const RELEASE_WINDOW: usize = 4 * 1024 * 1024;
    // `functions` is already sorted by address, so the FDE sizes need no hash
    // table: a binary search over the same pairs answers every lookup and costs
    // a third of the memory of an 800k-entry map.
    let fde_sizes = FdeSizes::new(functions);
    let verifier = PrologueVerifier::new()?;

    // Join every slot to the function at the same address, so a slot carries
    // the same size, prologue form and status as the function table entry.
    let mut slot_meta: HashMap<u64, (u64, String, String)> = HashMap::new();
    for class in &classes {
        for group in &class.record.vtable_groups {
            for slot in &group.slots {
                if slot.address == 0 || slot_meta.contains_key(&slot.address) {
                    continue;
                }
                let size = fde_sizes
                    .get(slot.address)
                    .or_else(|| named_size(named_symbols, slot.address, &slot.name))
                    .unwrap_or(0);
                let (prologue, _) = classify_function(map, image, slot.address, &verifier);
                let status = if fde_sizes.contains(slot.address) {
                    "verified"
                } else if is_executable(map, slot.address) {
                    "fde_missing"
                } else {
                    "import"
                };
                slot_meta.insert(slot.address, (size, prologue, status.to_string()));
            }
        }
    }

    let mut slots_with_size_zero = 0usize;
    for class in &mut classes {
        for group in &mut class.record.vtable_groups {
            for slot in &mut group.slots {
                let slot = Arc::make_mut(slot);
                if slot.address == 0 {
                    slot.status = "import".to_string();
                    slots_with_size_zero += 1;
                } else if let Some((size, prologue, status)) = slot_meta.get(&slot.address) {
                    slot.size = *size;
                    slot.prologue_kind = prologue.clone();
                    slot.status = status.clone();
                    if *size == 0 {
                        slots_with_size_zero += 1;
                    }
                }
                if let Some((signature, unique)) = signatures.get(&slot.address) {
                    let extra = SignatureExtras::of(signature);
                    slot.signature = extra.pattern;
                    slot.signature_unique = Some(*unique);
                    slot.signature_hex = extra.hex;
                    slot.signature_mask = extra.mask;
                    slot.signature_stability = extra.stability;
                    slot.signature_anchor = extra.anchor;
                    slot.identical_group = extra.group;
                    slot.identical_group_size = extra.group_size;
                }
            }
        }
        if let Some(primary) = class.record.vtable_groups.first() {
            class.record.slots = primary.slots.clone();
        }
    }

    let signatures_unique = signatures.values().filter(|(_, unique)| *unique).count();
    let vtable_slots = classes
        .iter()
        .map(|class| {
            class
                .record
                .vtable_groups
                .iter()
                .map(|group| group.slots.len())
                .sum::<usize>()
        })
        .sum();

    let mut stats = InternalSummary {
        classes: classes.len(),
        vtable_slots,
        functions: addresses.len(),
        named: 0,
        auto_named: 0,
        verified: 0,
        ambiguous,
        not_found,
        signatures: signatures.len(),
        signatures_unique,
        slots_with_size_zero,
        data_symbols: data_symbols.len(),
        truncated_signatures,
        path: String::new(),
        table_bytes: 0,
        rows: classes.len() + addresses.len() + data_symbols.len(),
        truncated_slots: !truncated_slot_names.is_empty(),
        truncated_slot_classes: truncated_slot_names.len(),
        truncated_slot_names,
        engine_version: crate::engine_version(),
        image_base: map.image_base,
        ..Default::default()
    };
    stats.coverage = InternalCoverage {
        functions: addresses.len(),
        fde_functions,
        fde_missing,
        functions_match_fdes: fde_missing == 0,
        classes: classes.len(),
        slots: stats.vtable_slots,
        data_symbols: data_symbols.len(),
        function_source: function_source.to_string(),
        truncated: truncated_signatures || stats.truncated_slots,
        truncated_slot_classes: stats.truncated_slot_classes,
        truncated_signatures,
        functions_without_file_offset: 0,
    };

    writer.meta(&stats)?;

    for class in &classes {
        writer.class(&class.record)?;
    }

    // Data symbols come last: the category documents open their sections in
    // this order so each one is written in a single streaming pass.

    // The function loop walks `.text` from low to high, so pages behind the
    // current address are never read again. Hand them back as the loop
    // advances; otherwise a 340 MB library stays resident for the whole write.
    let mut released_to = 0usize;
    for address in &addresses {
        let entry = names.remove(address);
        let size = fde_sizes.get(*address).unwrap_or_else(|| {
            entry
                .as_ref()
                .and_then(|entry| named_size(named_symbols, *address, &entry.name))
                .unwrap_or(0)
        });
        let resolved_offset = file_offset_of(map, *address);
        if resolved_offset.is_none() {
            // An executable address outside every section that occupies file
            // space has no file offset. Writing 0 there would claim the
            // function lives at the start of the file, so the row is marked and
            // counted instead of silently carrying a wrong number.
            stats.coverage.functions_without_file_offset += 1;
        }
        if let Some(releaser) = releaser {
            let current = resolved_offset.unwrap_or(0) as usize;
            if current > released_to + RELEASE_WINDOW {
                let end = current.saturating_sub(RELEASE_WINDOW / 2);
                releaser.release(released_to, end);
                released_to = end;
            }
        }
        let (prologue_kind, thunk_target) = classify_function(map, image, *address, &verifier);
        let from_fde = fde_sizes.contains(*address);

        let (name, source, confidence, aliases, kind, mangled, status) = match entry {
            Some(entry) => {
                let status = entry.status.unwrap_or_else(|| {
                    if entry.source != "auto" && (from_fde || prologue_kind != "none") {
                        "verified"
                    } else if from_fde {
                        "fde_only"
                    } else {
                        "suspect"
                    }
                });
                (
                    entry.name,
                    entry.source,
                    entry.confidence,
                    entry.aliases,
                    entry.kind,
                    entry.mangled,
                    status,
                )
            }
            None => (
                auto_name(*address, map.image_base),
                "auto",
                None,
                Vec::new(),
                "function",
                None,
                if from_fde { "fde_only" } else { "suspect" },
            ),
        };
        let status = if suspect.contains(address) || resolved_offset.is_none() {
            "suspect"
        } else {
            status
        };
        let suspect_reason = if suspect.contains(address) {
            Some("overlapping_fde".to_string())
        } else if resolved_offset.is_none() {
            Some("no_file_offset".to_string())
        } else if status == "suspect" {
            Some(if from_fde {
                "unverified_name".to_string()
            } else {
                "no_fde_no_prologue".to_string()
            })
        } else {
            None
        };
        if source == "auto" {
            stats.auto_named += 1;
        } else {
            stats.named += 1;
        }
        if status == "verified" {
            stats.verified += 1;
        }
        if status == "ambiguous" {
            stats.ambiguous += 1;
        }

        // `remove` hands the entry back as the row is written, so the map
        // shrinks with the loop instead of staying resident to the end.
        let (signature_unique, extras) = signatures
            .remove(address)
            .map(|(signature, unique)| {
                let extras = SignatureExtras::of(&signature);
                (Some(unique), extras)
            })
            .unwrap_or((None, SignatureExtras::default()));

        let kind = if thunk_target.is_some() {
            "thunk"
        } else {
            kind
        };
        let record = FunctionRecord {
            address: *address,
            file_offset: resolved_offset,
            size,
            name,
            name_source: source.to_string(),
            confidence: confidence.map(str::to_string),
            aliases,
            kind: kind.to_string(),
            status: status.to_string(),
            prologue_kind,
            mangled,
            target: thunk_target,
            signature: extras.pattern,
            signature_unique,
            signature_hex: extras.hex,
            signature_mask: extras.mask,
            signature_stability: extras.stability,
            signature_anchor: extras.anchor,
            identical_group: extras.group,
            identical_group_size: extras.group_size,
            suspect_reason,
        };
        writer.function(&record)?;
    }

    for symbol in data_symbols {
        let record = DataSymbolRecord {
            address: symbol.address,
            size: symbol.size,
            name: symbol.name.clone(),
            mangled: symbol.mangled.clone(),
            kind: symbol.kind.clone(),
            reason: symbol.reason.clone(),
        };
        writer.symbol(&record)?;
    }

    if let Some(releaser) = releaser {
        // Nothing after this point reads the library, so release every page
        // the passes faulted in before reporting the peak.
        releaser.release(0, usize::MAX);
    }
    stats.named = stats.named.min(stats.functions);
    writer.finish(&stats)?;
    if let Some(peak) = peak_resident_mb() {
        report(
            sink,
            100,
            &format!("Internal extraction complete, peak resident {peak} MB"),
        );
    } else {
        report(sink, 100, "Internal extraction complete");
    }
    Ok(stats)
}

/// Function sizes from `.eh_frame`, sorted by address.
///
/// `.eh_frame` records arrive in address order and `analyze` sorts them, so a
/// binary search replaces the hash table an 800k-function library would
/// otherwise need and drops the resident cost from tens of megabytes to the
/// thirteen the pairs themselves take.
struct FdeSizes(Vec<(u64, u64)>);

impl FdeSizes {
    fn new(functions: Vec<(u64, u64)>) -> Self {
        Self(functions)
    }

    fn get(&self, address: u64) -> Option<u64> {
        self.0
            .binary_search_by_key(&address, |(start, _)| *start)
            .ok()
            .map(|index| self.0[index].1)
    }

    fn contains(&self, address: u64) -> bool {
        self.0
            .binary_search_by_key(&address, |(start, _)| *start)
            .is_ok()
    }
}

/// Writes one NDJSON record without building a JSON tree first.
///
/// Only the host tests use this directly; the analysis goes through a
/// [`RecordWriter`].
///
/// `serde_json::json!` allocates a `Value` and then a `String` per record, which
/// a table of a million records pays for twice; streaming the two fixed keys and
/// the record itself keeps the write pass allocation-free.
#[cfg(test)]
fn write_record<T: Serialize>(
    out: &mut dyn Write,
    kind: &str,
    key: &str,
    value: &T,
) -> std::io::Result<()> {
    out.write_all(b"{\"type\":\"")?;
    out.write_all(kind.as_bytes())?;
    out.write_all(b"\",\"")?;
    out.write_all(key.as_bytes())?;
    out.write_all(b"\":")?;
    serde_json::to_writer(&mut *out, value)?;
    out.write_all(b"}\n")
}

/// Reports progress to an optional sink.
pub(crate) fn report(sink: Option<&dyn ProgressSink>, percent: u32, message: &str) {
    let rss = current_resident_mb()
        .map(|mb| format!(" [rss {mb} MB]"))
        .unwrap_or_default();
    log::info!("internal {percent}%: {message}{rss}");
    if let Some(sink) = sink {
        sink.report(percent, &format!("{message}{rss}"));
    }
}

/// Current resident set size of the process, in MiB.
fn current_resident_mb() -> Option<u64> {
    let status = std::fs::read_to_string("/proc/self/status").ok()?;
    let line = status.lines().find(|line| line.starts_with("VmRSS:"))?;
    let kilobytes: u64 = line.split_whitespace().nth(1)?.parse().ok()?;
    Some(kilobytes / 1024)
}

/// Returns [`CoreError::Cancelled`] when the user has cancelled.
fn check_cancelled(control: &ScanControl) -> CoreResult<()> {
    if control.is_cancelled() {
        return Err(CoreError::Cancelled);
    }
    Ok(())
}

/// Reads every slot of every vtable.
fn collect_class_slots(
    map: &SectionMap,
    image: &[u8],
    vtables: &VtableMap,
    relocation: &RelocationIndex,
    pure_virtual: Option<u64>,
    max_slots: usize,
    control: &ScanControl,
) -> CoreResult<(Vec<ClassSlots>, Vec<String>)> {
    let limit = max_slots.min(MAX_SLOTS_PER_CLASS);
    // A class with multiple inheritance owns one vtable per base subobject. The
    // wire format keeps one entry per class name, so the full list is walked
    // here instead of `entries`.
    let all: Vec<&crate::rtti::TypeVTable> = if vtables.all.is_empty() {
        vtables.entries.values().collect()
    } else {
        vtables.all.iter().collect()
    };

    // A slot run must stop before it walks into the next class's vtable header
    // (the offset-to-top or the type_info pointer), which is the only reliable
    // terminator when two vtables are adjacent in `.data.rel.ro`.
    let mut headers: HashSet<u64> = HashSet::with_capacity(all.len() * 2);
    for vtable in &all {
        headers.insert(vtable.vtable_address);
        headers.insert(vtable.vtable_address.saturating_add(8));
    }

    // The mangled name is the identity. Grouping by demangled name merged
    // every template instantiation whose arguments the old demangler dropped.
    let mut by_identity: BTreeMap<String, Vec<&crate::rtti::TypeVTable>> = BTreeMap::new();
    for vtable in all {
        by_identity
            .entry(vtable.mangled.clone())
            .or_default()
            .push(vtable);
    }

    let mut classes = Vec::with_capacity(by_identity.len());
    let mut truncated: Vec<String> = Vec::new();
    for (_, mut groups) in by_identity {
        check_cancelled(control)?;
        // The primary vtable is the one whose offset-to-top is zero; the rest
        // are base-subobject groups. Sorting by address alone put a secondary
        // group first for a class like `Actor`, which then became the reported
        // primary and truncated `slotCount` to a single slot.
        groups.sort_by_key(|vtable| {
            let offset_to_top = read_at(map, image, vtable.vtable_address).unwrap_or(0) as i64;
            (offset_to_top != 0, vtable.vtable_address)
        });
        let mut group_addresses: Vec<Vec<u64>> = Vec::with_capacity(groups.len());
        let mut group_slots: Vec<Vec<Arc<SlotRecord>>> = Vec::with_capacity(groups.len());
        let mut group_meta: Vec<(i64, u64)> = Vec::with_capacity(groups.len());

        for vtable in &groups {
            let first_slot = vtable.vtable_address.saturating_add(VTABLE_PREFIX_BYTES);
            let mut addresses: Vec<u64> = Vec::new();
            // Set when the run reached the cap instead of a terminator. Without
            // it a class with more slots than the cap lost the rest silently:
            // the summary claimed a complete table and nothing said otherwise.
            let mut hit_cap = false;
            for slot in 0..limit {
                hit_cap = slot + 1 == limit;
                let slot_address = first_slot.saturating_add((slot as u64) * 8);
                if headers.contains(&slot_address) {
                    // The run reached another class's header.
                    break;
                }
                let raw = read_at(map, image, slot_address);
                let relocated = relocation.target_of(slot_address);
                let target = relocated.unwrap_or_else(|| raw.unwrap_or(0));
                if target == 0 {
                    if relocated.is_some() {
                        // An imported symbol (GLOB_DAT/JUMP_SLOT) has a zero
                        // addend because the loader fills it from the GOT. It is
                        // still a slot, so record it and keep going.
                        addresses.push(0);
                        continue;
                    }
                    // No relocation and no in-place pointer: the run ended.
                    break;
                }
                if !is_executable(map, target) {
                    if relocated.is_some() {
                        // A pointer outside `.text` is an unresolved import or a
                        // deleted-function stub, not the end of the vtable.
                        addresses.push(target);
                        continue;
                    }
                    break;
                }
                addresses.push(target);
            }

            let class_name = unqualified(&vtable.name);
            let destructor_pair = addresses.len() >= 2
                && addresses[0] != 0
                && addresses[1] != 0
                && (addresses[0] == addresses[1]
                    || is_thunk_to(map, image, addresses[1], addresses[0])
                    || calls_target(map, image, addresses[1], addresses[0]));

            let slots: Vec<Arc<SlotRecord>> = addresses
                .iter()
                .enumerate()
                .map(|(slot, address)| {
                    let (slot_name, kind) = if *address == 0 {
                        (format!("{}::vfunc_{slot}", vtable.name), "import")
                    } else if pure_virtual == Some(*address) {
                        (format!("{}::vfunc_{slot}", vtable.name), "pure_virtual")
                    } else if destructor_pair && slot == 0 {
                        (format!("{class_name}::~{class_name}"), "virtual")
                    } else if destructor_pair && slot == 1 {
                        (format!("{class_name}::~{class_name}_deleting"), "virtual")
                    } else if !is_executable(map, *address) {
                        (format!("{}::vfunc_{slot}", vtable.name), "import")
                    } else {
                        (format!("{}::vfunc_{slot}", vtable.name), "virtual")
                    };
                    SlotRecord {
                        slot,
                        offset: (slot as u64) * 8,
                        address: *address,
                        file_offset: file_offset_of(map, *address),
                        size: 0,
                        name: slot_name,
                        kind: kind.to_string(),
                        prologue_kind: "none".to_string(),
                        overrides: false,
                        inherited: false,
                        status: "pending".to_string(),
                        signature: None,
                        signature_unique: None,
                        signature_hex: None,
                        signature_mask: None,
                        signature_stability: None,
                        signature_anchor: None,
                        identical_group: None,
                        identical_group_size: None,
                    }
                    .into()
                })
                .collect();

            let offset_to_top = read_at(map, image, vtable.vtable_address).unwrap_or(0) as i64;
            if hit_cap && !truncated.iter().any(|name| name == &vtable.name) {
                truncated.push(vtable.name.clone());
            }
            group_meta.push((offset_to_top, vtable.vtable_address));
            group_addresses.push(addresses);
            group_slots.push(slots);
        }

        let primary = &group_slots[0];
        let record = ClassRecord {
            name: groups[0].name.clone(),
            base_name: groups[0].base_name.clone(),
            mangled: groups[0].mangled.clone(),
            bases: Vec::new(),
            base_classes: Vec::new(),
            vtable: groups[0].vtable_address,
            file_offset: file_offset_of(map, groups[0].vtable_address),
            vtable_offset: groups[0].vtable_offset,
            type_info: groups[0].type_info_address,
            slot_count: primary.len(),
            slots: primary.clone(),
            vtable_groups: group_meta
                .iter()
                .zip(group_slots.iter())
                .map(|((offset_to_top, address), slots)| VtableGroup {
                    offset_to_top: *offset_to_top,
                    address: *address,
                    slots: slots.clone(),
                })
                .collect(),
        };
        classes.push(ClassSlots {
            record,
            addresses: group_addresses[0].clone(),
        });
    }
    Ok((classes, truncated))
}
pub(crate) fn resolve_bases(
    map: &SectionMap,
    image: &[u8],
    relocation: &RelocationIndex,
    vtables: &VtableMap,
) -> HashMap<String, Vec<BaseRecord>> {
    // Every group is needed: a base that only appears as a secondary vtable
    // group would otherwise be invisible to the graph.
    let type_info_to_class: HashMap<u64, &crate::rtti::TypeVTable> = vtables
        .all
        .iter()
        .map(|vtable| (vtable.type_info_address, vtable))
        .collect();
    let mut bases = HashMap::with_capacity(vtables.all.len());
    for vtable in &vtables.all {
        let mut found: Vec<BaseRecord> = Vec::new();
        match vtable.type_info_kind {
            // Neither shape carries a base array. Reading +16 anyway is what
            // produced bases pointing at unrelated `type_info` objects.
            TypeInfoKind::Class | TypeInfoKind::Unknown => {}
            TypeInfoKind::SiClass => {
                if let Some(pointer) =
                    read_pointer(map, image, relocation, vtable.type_info_address + 16)
                {
                    if let Some(base) = type_info_to_class.get(&pointer) {
                        if base.mangled != vtable.mangled {
                            found.push(BaseRecord {
                                name: base.name.clone(),
                                mangled: base.mangled.clone(),
                                // A single public base always starts at offset 0.
                                offset: 0,
                                flags: 3,
                                is_public: true,
                                is_virtual: false,
                            });
                        }
                    }
                }
            }
            TypeInfoKind::VmiClass => {
                // `__vmi_class_type_info` layout: flags at +16, base count at
                // +20, then `count` pairs of (type_info*, offset<<8 | flags).
                let count = read_at(map, image, vtable.type_info_address + 20)
                    .map(|raw| (raw & 0xffff_ffff) as usize)
                    .unwrap_or(0);
                if count > 0 && count < 256 {
                    for index in 0..count {
                        let at = vtable.type_info_address + 24 + (index as u64) * 16;
                        let Some(pointer) = read_pointer(map, image, relocation, at) else {
                            continue;
                        };
                        let Some(base) = type_info_to_class.get(&pointer) else {
                            continue;
                        };
                        if found.iter().any(|known| known.mangled == base.mangled) {
                            continue;
                        }
                        let offset_flags =
                            read_pointer(map, image, relocation, at + 8).unwrap_or(0);
                        let offset = (offset_flags as i64) >> 8;
                        let flags = (offset_flags & 0xff) as u32;
                        found.push(BaseRecord {
                            name: base.name.clone(),
                            mangled: base.mangled.clone(),
                            offset,
                            flags,
                            is_public: flags & 1 != 0,
                            is_virtual: flags & 2 != 0,
                        });
                    }
                }
            }
        }
        bases.insert(vtable.mangled.clone(), found);
    }
    bases
}

/// Fills the `overrides`/`inherited` flags and copies real base names.
///
/// A slot is *inherited* when its address equals the same index in any base
/// that declares that index, and *overridden* when it differs. Comparing every
/// base rather than only the first one is what makes multiple inheritance
/// correct: the secondary vtable group of a class belongs to a base that is not
/// the first in the list.
fn apply_inheritance(classes: &mut [ClassSlots], bases: &HashMap<String, Vec<BaseRecord>>) {
    // Everything is keyed by mangled identity, so two instantiations of the
    // same template never inherit from each other's slots.
    let by_id: HashMap<String, usize> = classes
        .iter()
        .enumerate()
        .map(|(index, class)| (class.record.mangled.clone(), index))
        .collect();
    let base_ids: Vec<Vec<usize>> = classes
        .iter()
        .map(|class| {
            let mut ids: Vec<usize> = bases
                .get(&class.record.mangled)
                .map(|records| {
                    records
                        .iter()
                        .filter_map(|record| by_id.get(&record.mangled).copied())
                        .collect()
                })
                .unwrap_or_default();
            ids.sort_unstable();
            ids.dedup();
            ids
        })
        .collect();
    let addresses: Vec<Vec<u64>> = classes
        .iter()
        .map(|class| class.addresses.clone())
        .collect();
    let names: Vec<String> = classes
        .iter()
        .map(|class| class.record.name.clone())
        .collect();

    // Declaring-class pass: a slot belongs to the class that introduced it. A
    // slot byte-identical to a base's slot belongs to whatever that base says;
    // a differing address is an override and belongs to this class. This is
    // what stops naming every slot after the most-derived class.
    let mut declaring: Vec<Vec<usize>> = Vec::with_capacity(classes.len());
    for (index, row) in addresses.iter().enumerate() {
        let mut owners = Vec::with_capacity(row.len());
        for slot in 0..row.len() {
            owners.push(declaring_owner(
                index,
                slot,
                &addresses,
                &base_ids,
                &mut HashMap::new(),
                0,
            ));
        }
        declaring.push(owners);
    }

    let base_names: Vec<Vec<String>> = classes
        .iter()
        .map(|class| {
            bases
                .get(&class.record.mangled)
                .map(|records| records.iter().map(|record| record.name.clone()).collect())
                .unwrap_or_default()
        })
        .collect();
    let base_records: Vec<Vec<BaseRecord>> = classes
        .iter()
        .map(|class| {
            bases
                .get(&class.record.mangled)
                .cloned()
                .unwrap_or_default()
        })
        .collect();
    let slot_names: Vec<Vec<String>> = classes
        .iter()
        .map(|class| {
            class
                .record
                .slots
                .iter()
                .map(|slot| slot.name.clone())
                .collect()
        })
        .collect();

    for (index, class) in classes.iter_mut().enumerate() {
        class.record.bases = base_names[index].clone();
        class.record.base_classes = base_records[index].clone();
        if let Some(primary) = class.record.vtable_groups.first_mut() {
            for (slot_index, slot) in primary.slots.iter_mut().enumerate() {
                let slot = Arc::make_mut(slot);
                let owner = declaring[index].get(slot_index).copied().unwrap_or(index);
                slot.name = format!("{}::vfunc_{slot_index}", names[owner]);
                slot.inherited = owner != index;
                slot.overrides = owner == index && !base_ids[index].is_empty();
            }
        }

        // Real names propagate along the inheritance graph: if a base's slot
        // carries a recovered name (a symbol, a lambda, an adapter), every
        // inherited copy of that slot uses it.
        let mut inherited_names: HashMap<usize, String> = HashMap::new();
        for base in &base_ids[index] {
            for (slot_index, name) in slot_names[*base].iter().enumerate() {
                if !is_placeholder_slot_name(name) {
                    inherited_names
                        .entry(slot_index)
                        .or_insert_with(|| name.clone());
                }
            }
        }
        for group in class.record.vtable_groups.iter_mut() {
            for (slot_index, slot) in group.slots.iter_mut().enumerate() {
                let owner = declaring[index].get(slot_index).copied().unwrap_or(index);
                if owner == index {
                    continue;
                }
                if let Some(name) = inherited_names.get(&slot_index) {
                    let slot = Arc::make_mut(slot);
                    slot.name = name.clone();
                    slot.inherited = true;
                }
            }
        }
        if let Some(primary) = class.record.vtable_groups.first() {
            class.record.slots = primary.slots.clone();
        }
    }
}

/// The slot targets of every vtable group, keyed by the group's header address.
///
/// The naming engine reads slot 0 of a lambda's group; keeping the map next to
/// the classes avoids re-walking `.data.rel.ro`.
fn slots_by_vtable_address(classes: &[ClassSlots]) -> std::collections::HashMap<u64, Vec<u64>> {
    let mut map: HashMap<u64, Vec<u64>> = HashMap::with_capacity(classes.len());
    for class in classes {
        for group in &class.record.vtable_groups {
            // The stored address is the header (offset-to-top), and the group's
            // own `address` is what the RTTI entry and the caller use.
            let address = group.address;
            if address == 0 {
                continue;
            }
            map.insert(
                address,
                group
                    .slots
                    .iter()
                    .map(|slot| slot.address)
                    .collect::<Vec<_>>(),
            );
        }
    }
    map
}

/// Counts how many names each evidence source contributed.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EvidenceCounts {
    /// Names from a lambda's enclosing function.
    pub lambda: usize,
    /// Names from a unique vtable reference.
    pub vtable_xref: usize,
    /// Names from an ECS system adapter.
    pub ecs_system: usize,
    /// Names from scripting reflection.
    pub scripting: usize,
    /// Names from a unique string reference.
    pub string_anchor: usize,
}

impl EvidenceCounts {
    fn bump(&mut self, source: crate::naming::EvidenceSource) {
        use crate::naming::EvidenceSource as Source;
        match source {
            Source::LambdaEnclosing => self.lambda += 1,
            Source::VtableXref => self.vtable_xref += 1,
            Source::EcsSystem => self.ecs_system += 1,
            Source::ScriptingReflection => self.scripting += 1,
            Source::StringAnchor => self.string_anchor += 1,
            Source::Symbol | Source::CallGraph | Source::Shape => {}
        }
    }
}

/// Gives every slot row at the same address the same name.
///
/// A name learned for `Actor::vfunc_3` through one class must reach every other
/// class that inherits or shares that function, even when RTTI does not link
/// the two (a class with no RTTI, a secondary group, a shared thunk). Real
/// names win over placeholders; among real names the lexicographically first
/// wins so the output does not depend on hash order.
fn unify_slot_names_by_address(classes: &mut [ClassSlots]) {
    let mut best: HashMap<u64, String> = HashMap::new();
    for class in classes.iter() {
        for group in &class.record.vtable_groups {
            for slot in &group.slots {
                if slot.address == 0 || is_placeholder_slot_name(&slot.name) {
                    continue;
                }
                best.entry(slot.address)
                    .and_modify(|current| {
                        if slot.name < *current {
                            *current = slot.name.clone();
                        }
                    })
                    .or_insert_with(|| slot.name.clone());
            }
        }
    }
    if best.is_empty() {
        return;
    }
    for class in classes.iter_mut() {
        for group in class.record.vtable_groups.iter_mut() {
            for slot in group.slots.iter_mut() {
                let Some(name) = best.get(&slot.address) else {
                    continue;
                };
                if &slot.name == name {
                    continue;
                }
                let slot = Arc::make_mut(slot);
                slot.name = name.clone();
                slot.inherited = true;
            }
        }
        if let Some(primary) = class.record.vtable_groups.first() {
            class.record.slots = primary.slots.clone();
        }
    }
}

/// Which class a slot index belongs to, following `identical to base` links.
///
/// Bounded by depth so a malformed (cyclic) inheritance graph cannot recurse
/// forever: a cycle leaves the slot with the class the walk started from.
fn declaring_owner(
    class: usize,
    slot: usize,
    addresses: &[Vec<u64>],
    bases: &[Vec<usize>],
    memo: &mut HashMap<usize, usize>,
    depth: usize,
) -> usize {
    if depth > 64 {
        return class;
    }
    let Some(address) = addresses[class].get(slot).copied() else {
        return class;
    };
    for base in &bases[class] {
        let Some(base_address) = addresses[*base].get(slot).copied() else {
            continue;
        };
        if base_address == address {
            if let Some(known) = memo.get(base) {
                return *known;
            }
            let owner = declaring_owner(*base, slot, addresses, bases, memo, depth + 1);
            memo.insert(*base, owner);
            return owner;
        }
    }
    class
}

/// Whether a slot name is the generated `Class::vfunc_N` placeholder.
fn is_placeholder_slot_name(name: &str) -> bool {
    match name.rsplit_once("::vfunc_") {
        Some((_, suffix)) => !suffix.is_empty() && suffix.bytes().all(|byte| byte.is_ascii_digit()),
        None => false,
    }
}

/// The unqualified part of a C++ name, used for destructor spellings.
fn unqualified(name: &str) -> &str {
    name.rsplit_once("::").map(|(_, tail)| tail).unwrap_or(name)
}

/// Inserts the exported symbol names into the table.
fn insert_symbol_names(names: &mut HashMap<u64, NamedEntry>, symbols: &[NamedSymbol]) {
    for symbol in symbols {
        let source = if symbol.name.starts_with("Java_") {
            "jni"
        } else {
            "symbol"
        };
        insert_name(
            names,
            symbol.address,
            symbol.name.clone(),
            source,
            "function",
            Some(symbol.mangled.clone()),
            None,
            None,
        );
    }
}

/// Inserts every vtable slot name into the table.
fn insert_slot_names(
    map: &SectionMap,
    names: &mut HashMap<u64, NamedEntry>,
    classes: &[ClassSlots],
) {
    for class in classes {
        for group in &class.record.vtable_groups {
            for slot in &group.slots {
                if slot.address == 0 {
                    // An unresolved import has no address to name.
                    continue;
                }
                // A secondary vtable group holds data as well as code: a
                // virtual-base group starts with a vbase offset, and a nested
                // vtable slot points into `.data.rel.ro`. Naming such a slot
                // creates a "function" at an address that holds no code, and
                // the prologue check then "verifies" whatever bytes sit there.
                if !is_executable(map, slot.address) {
                    continue;
                }
                insert_name(
                    names,
                    slot.address,
                    slot.name.clone(),
                    "vtable",
                    if slot.kind == "pure_virtual" {
                        "pure_virtual"
                    } else {
                        "function"
                    },
                    None,
                    None,
                    None,
                );
            }
        }
    }
}

/// Adds a name, keeping the highest-priority one and recording the rest.
#[allow(clippy::too_many_arguments)]
fn insert_name(
    names: &mut HashMap<u64, NamedEntry>,
    address: u64,
    name: String,
    source: &'static str,
    kind: &'static str,
    mangled: Option<String>,
    status: Option<&'static str>,
    confidence: Option<&'static str>,
) {
    if address == 0 {
        return;
    }
    match names.get_mut(&address) {
        Some(existing) => {
            if rank(source) < rank(existing.source) {
                if existing.name != name {
                    existing.aliases.push(existing.name.clone());
                }
                existing.name = name;
                existing.source = source;
                existing.kind = kind;
                existing.mangled = mangled;
                existing.status = status;
                existing.confidence = confidence;
            } else if existing.name != name && !existing.aliases.contains(&name) {
                existing.aliases.push(name);
            }
        }
        None => {
            names.insert(
                address,
                NamedEntry {
                    name,
                    source,
                    confidence,
                    aliases: Vec::new(),
                    kind,
                    mangled,
                    status,
                },
            );
        }
    }
}

/// Priority of a name source; lower is better.
fn rank(source: &str) -> u8 {
    match source {
        // A symbol is the library's own claim.
        "symbol" => 1,
        // A name recovered from the ABI encoding or from a unique vtable
        // reference is structural: it must beat the `Class::vfunc_N` label the
        // slot walker guessed, or the evidence would never surface.
        "jni" | "lambda" | "vtable_xref" | "ecs_system" | "scripting" => 2,
        "vtable" => 3,
        "signature" => 4,
        "string" | "call_graph" | "shape" => 5,
        _ => 6,
    }
}

/// Applies the hand-maintained internal signatures.
#[allow(clippy::too_many_arguments)]
fn apply_internal_patterns(
    map: &SectionMap,
    image: &[u8],
    options: &InternalOptions,
    control: &ScanControl,
    sink: Option<&dyn ProgressSink>,
    releaser: Option<&dyn PageReleaser>,
    functions: &[(u64, u64)],
    names: &mut HashMap<u64, NamedEntry>,
    ambiguous: &mut usize,
    not_found: &mut usize,
) -> CoreResult<()> {
    if options.internal_patterns.is_empty() {
        return Ok(());
    }
    let specs: Vec<(String, String)> = options
        .internal_patterns
        .iter()
        .map(|pattern| (pattern.name.clone(), pattern.pattern.clone()))
        .collect();
    let set = PatternSet::new(&specs)?;
    let outcome = scan_section_releasing_pages(map, image, &set, control, sink, releaser)?;

    for pattern in &options.internal_patterns {
        check_cancelled(control)?;
        let hits: Vec<_> = outcome
            .hits
            .iter()
            .filter(|hit| hit.name == pattern.name)
            .collect();
        let mut resolved = Vec::new();
        for hit in &hits {
            if let Some(address) =
                resolve_pattern_hit(image, hit, &pattern.resolve, pattern.offset, functions)
            {
                resolved.push(address);
            }
        }
        resolved.sort_unstable();
        resolved.dedup();
        match resolved.len() {
            0 => *not_found += 1,
            1 => insert_name(
                names,
                resolved[0],
                pattern.name.clone(),
                "signature",
                "function",
                None,
                None,
                None,
            ),
            _ => {
                *ambiguous += 1;
                for address in resolved {
                    insert_name(
                        names,
                        address,
                        pattern.name.clone(),
                        "signature",
                        "function",
                        None,
                        Some("ambiguous"),
                        None,
                    );
                }
            }
        }
    }
    Ok(())
}

/// Turns a pattern hit into the address its `resolve` mode selects.
fn resolve_pattern_hit(
    image: &[u8],
    hit: &crate::scanner::PatternHit,
    resolve: &str,
    offset: i64,
    functions: &[(u64, u64)],
) -> Option<u64> {
    match resolve {
        "bl_target" => bl_target(image, hit.file_offset),
        "adrp_add" => adrp_add_target(image, hit.file_offset, hit.address),
        "prologue_back" => enclosing_function(functions, hit.address).map(|(start, _)| start),
        _ => Some((hit.address as i64 + offset).max(0) as u64),
    }
}

/// Decodes the first `BL` in the bytes at `file_offset` and returns its target.
fn bl_target(image: &[u8], file_offset: u64) -> Option<u64> {
    let start = file_offset as usize;
    for index in 0..4 {
        let at = start + index * 4;
        let bytes = image.get(at..at + 4)?;
        let word = u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]);
        if word & 0xFC00_0000 == 0x9400_0000 {
            let immediate = sign_extend((word & 0x03FF_FFFF) as i64, 26) << 2;
            return Some((at as i64 + immediate) as u64);
        }
    }
    None
}

/// Decodes an `ADRP`+`ADD` pair at `file_offset` and returns the global address.
fn adrp_add_target(image: &[u8], file_offset: u64, address: u64) -> Option<u64> {
    let at = file_offset as usize;
    let first = image.get(at..at + 4)?;
    let second = image.get(at + 4..at + 8)?;
    let adrp = u32::from_le_bytes([first[0], first[1], first[2], first[3]]);
    let add = u32::from_le_bytes([second[0], second[1], second[2], second[3]]);
    if adrp & 0x9F00_0000 != 0x9000_0000 || add & 0xFF80_0000 != 0x9100_0000 {
        return None;
    }
    let page = adrp_page(address, adrp);
    let immediate = ((add >> 10) & 0xFFF) as u64;
    let shift = ((add >> 22) & 0x3) as u64;
    Some(page.wrapping_add(immediate << (12 * shift)))
}

/// Computes the page an `ADRP` instruction materialises.
fn adrp_page(pc: u64, word: u32) -> u64 {
    let immhi = ((word >> 5) & 0x7_FFFF) as i64;
    let immlo = ((word >> 29) & 0x3) as i64;
    let immediate = sign_extend((immhi << 2) | immlo, 21) << 12;
    ((pc as i64 & !0xFFF).wrapping_add(immediate)) as u64
}

/// Applies the `.rodata` string anchors.
#[allow(clippy::too_many_arguments)]
fn apply_string_anchors(
    map: &SectionMap,
    image: &[u8],
    options: &InternalOptions,
    functions: &[(u64, u64)],
    names: &mut HashMap<u64, NamedEntry>,
    ambiguous: &mut usize,
    not_found: &mut usize,
    releaser: Option<&dyn PageReleaser>,
) {
    if options.string_anchors.is_empty() {
        return;
    }
    let rodata = map.find(RODATA_SECTION);
    for anchor in &options.string_anchors {
        let Some(section) = rodata else {
            *not_found += 1;
            continue;
        };
        let bytes = map.bytes_of(&section.name, image);
        let needle = anchor.string.as_bytes();
        if needle.is_empty() {
            *not_found += 1;
            continue;
        }
        let mut occurrences = BTreeSet::new();
        for (index, window) in bytes.windows(needle.len()).enumerate() {
            if window == needle {
                occurrences.insert(section.address + index as u64);
            }
        }
        if occurrences.is_empty() {
            *not_found += 1;
            continue;
        }
        let mut references = BTreeSet::new();
        find_string_xrefs(map, image, &occurrences, &mut references, releaser);
        let mut targets: BTreeSet<u64> = BTreeSet::new();
        for reference in references {
            if let Some((start, _)) = enclosing_function(functions, reference) {
                targets.insert(start);
            }
        }
        match targets.len() {
            0 => *not_found += 1,
            1 => insert_name(
                names,
                *targets.iter().next().expect("one target"),
                anchor.name.clone(),
                "string",
                "function",
                None,
                None,
                None,
            ),
            _ => {
                *ambiguous += 1;
                for address in targets {
                    insert_name(
                        names,
                        address,
                        anchor.name.clone(),
                        "string",
                        "function",
                        None,
                        Some("ambiguous"),
                        None,
                    );
                }
            }
        }
    }
}

/// Finds every `ADRP`+`ADD`/`LDR` pair that materialises one of `occurrences`.
///
/// The window is 64 bytes rather than 16: a compiler routinely puts a `MOV`,
/// a load of the second half of a 128-bit value or a register save between the
/// page load and the `ADD` that completes the address. The register file is
/// tracked so a pair whose base register was overwritten is not mistaken for a
/// reference.
fn find_string_xrefs(
    map: &SectionMap,
    image: &[u8],
    occurrences: &BTreeSet<u64>,
    out: &mut BTreeSet<u64>,
    releaser: Option<&dyn PageReleaser>,
) {
    const RELEASE_EVERY: usize = 4 * 1024 * 1024;
    /// Largest instruction distance between the `ADRP`/`ADR` and its user.
    const MAX_PAIR_DISTANCE: u64 = 64;
    let text = map.bytes_of(TEXT_SECTION, image);
    let mut adrp: [Option<(u64, u64)>; 32] = [None; 32];
    for (index, chunk) in text.as_chunks::<4>().0.iter().enumerate() {
        let pc = map.text.address + (index as u64) * 4;
        if let Some(releaser) = releaser {
            if index % RELEASE_EVERY == 0 && index > 0 {
                let current = map.text.offset as usize + index * 4;
                releaser.release(current.saturating_sub(RELEASE_EVERY * 4), current);
            }
        }
        let word = u32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]);
        let destination = (word & 31) as usize;
        if word & 0x9F00_0000 == 0x9000_0000 {
            adrp[destination] = Some((pc, adrp_page(pc, word)));
        } else if word & 0x9F00_0000 == 0x1000_0000 {
            // ADR materialises a full address with no page base.
            let immlo = ((word >> 29) & 0x3) as i64;
            let immhi = ((word >> 5) & 0x7_FFFF) as i64;
            let target = (pc as i64).wrapping_add(sign_extend((immhi << 2) | immlo, 21)) as u64;
            if occurrences.contains(&target) {
                out.insert(pc);
            }
            adrp[destination] = Some((pc, target));
        } else if word & 0xFF80_0000 == 0x9100_0000 {
            let source = ((word >> 5) & 31) as usize;
            if let Some((adrp_pc, page)) = adrp[source] {
                if pc.saturating_sub(adrp_pc) <= MAX_PAIR_DISTANCE {
                    let immediate = ((word >> 10) & 0xFFF) as u64;
                    let shift = ((word >> 22) & 0x3) as u64;
                    let target = page.wrapping_add(immediate << (12 * shift));
                    if occurrences.contains(&target) {
                        out.insert(pc);
                    }
                    adrp[destination] = Some((pc, target));
                } else {
                    adrp[destination] = None;
                }
            } else {
                adrp[destination] = None;
            }
        } else if word & 0x3B00_0000 == 0x3900_0000 {
            let source = ((word >> 5) & 31) as usize;
            let size_field = (word >> 30) & 0x3;
            let scale = 1u64 << size_field;
            if let Some((adrp_pc, page)) = adrp[source] {
                if pc.saturating_sub(adrp_pc) <= MAX_PAIR_DISTANCE {
                    let immediate = ((word >> 10) & 0xFFF) as u64;
                    let target = page.wrapping_add(immediate * scale);
                    if occurrences.contains(&target) {
                        out.insert(pc);
                    }
                }
            }
            if (word >> 22) & 0x3 != 0 {
                adrp[destination] = None;
            }
        } else if word & 0x7FE0_FFE0 == 0x2A00_03E0 {
            let source = ((word >> 16) & 31) as usize;
            adrp[destination] = adrp[source];
        } else if crate::xref::writes_gpr(word) {
            adrp[destination] = None;
        }
    }
}

/// The exported form of a signature, one field per JSON key.
///
/// Built in one place so the function table and the slot table can never drift
/// apart on what a signature looks like.
#[derive(Default)]
struct SignatureExtras {
    pattern: Option<Arc<str>>,
    hex: Option<Arc<str>>,
    mask: Option<Arc<str>>,
    stability: Option<u8>,
    anchor: Option<Arc<str>>,
    group: Option<u32>,
    group_size: Option<u32>,
}

impl SignatureExtras {
    fn of(signature: &Signature) -> Self {
        Self {
            pattern: Some(Arc::from(signature.render())),
            hex: Some(Arc::from(signature.hex())),
            mask: Some(Arc::from(signature.bit_mask_hex())),
            stability: Some(signature.stability()),
            anchor: signature.anchor().map(Arc::from),
            group: signature.identical_group(),
            group_size: (signature.group_size() > 1).then(|| signature.group_size()),
        }
    }
}

/// Signature per address, plus whether it is unique among function starts.
type SignatureMap = HashMap<u64, (Signature, bool)>;

/// Generates a signature per named function and per vtable slot.
///
/// The plan is one sort plus one refinement pass (see
/// [`crate::signature::plan`]); the old implementation grew every unresolved
/// pattern by 16 bytes a round and re-matched the whole table each time, which
/// is what took minutes and then ran out of memory at round 19.
///
/// Functions whose body is byte-identical to another function cannot be told
/// apart by bytes at all. They are grouped, counted, and given an anchor — a
/// declaring vtable slot, a unique caller or a string only they reference — so a
/// consumer still has a way to find them.
#[allow(clippy::too_many_arguments)]
fn generate_signatures(
    map: &SectionMap,
    image: &[u8],
    names: &HashMap<u64, NamedEntry>,
    functions: &[(u64, u64)],
    classes: &[ClassSlots],
    xrefs: &crate::xref::XrefTable,
    max_signatures: usize,
    control: &ScanControl,
    sink: Option<&dyn ProgressSink>,
    releaser: Option<&dyn PageReleaser>,
) -> CoreResult<(SignatureMap, bool, crate::signature::PlanStats)> {
    let text = map.bytes_of(TEXT_SECTION, image);
    if text.is_empty() {
        return Ok((
            HashMap::new(),
            false,
            crate::signature::PlanStats::default(),
        ));
    }

    let mut addresses: Vec<u64> = names
        .iter()
        .filter(|(address, entry)| entry.source != "auto" && **address != 0)
        .map(|(address, _)| *address)
        .collect();
    for class in classes {
        for group in &class.record.vtable_groups {
            for slot in &group.slots {
                if slot.address != 0 {
                    addresses.push(slot.address);
                }
            }
        }
    }
    addresses.sort_unstable();
    addresses.dedup();
    let limit = max_signatures.max(1);
    let truncated = addresses.len() > limit;
    addresses.truncate(limit);

    let (planned, stats) =
        crate::signature::plan(text, map.text.address, functions, &addresses, control, sink)?;

    // The anchor map is built only for the addresses that actually need one, so
    // a library with no identical bodies pays nothing for it.
    let mut needs_anchor: HashSet<u64> = HashSet::new();
    for (address, signature) in &planned {
        if signature.identical_group().is_some() {
            needs_anchor.insert(*address);
        }
    }
    let slot_names = if needs_anchor.is_empty() {
        HashMap::new()
    } else {
        slot_anchor_names(classes, &needs_anchor)
    };

    // The reverse indexes are filtered to the duplicate group members and the
    // data they reference, so they stay small even on a library with millions
    // of edges.
    let (anchor_callers, anchor_referrers) = if needs_anchor.is_empty() {
        (
            crate::xref::ReverseIndex::default(),
            crate::xref::ReverseIndex::default(),
        )
    } else {
        let callers = xrefs.callers_of(&needs_anchor)?;
        let mut referenced: HashSet<u64> = HashSet::new();
        for address in &needs_anchor {
            if let Some(function) = xrefs.function_at(*address) {
                referenced.extend(xrefs.data_refs_of(function).iter().copied());
            }
        }
        (callers, xrefs.referrers_of(&referenced)?)
    };

    let mut signatures: SignatureMap = HashMap::with_capacity(planned.len());
    for (address, mut signature) in planned {
        if signature.identical_group().is_some() {
            // A group member has no byte pattern of its own, so the last resort
            // is its ordinal inside the group: resolve the shared pattern, then
            // take the Nth hit in address order.
            let anchor = find_anchor(
                address,
                &slot_names,
                xrefs,
                &needs_anchor,
                &anchor_callers,
                &anchor_referrers,
            )
            .or_else(|| {
                signature.identical_group().map(|group| {
                    format!(
                        "group:{group}/{}#{}",
                        signature.group_size(),
                        signature.ordinal()
                    )
                    .into()
                })
            });
            signature.set_anchor(anchor);
        }
        let unique = signature.is_unique();
        signatures.insert(address, (signature, unique));
    }
    report(
        sink,
        79,
        &format!(
            "Signatures: {} text passes, {} heads, {} unique, {} identical-body",
            stats.text_passes, stats.functions, stats.unique, stats.identical_functions
        ),
    );
    if let Some(releaser) = releaser {
        releaser.release(0, usize::MAX);
    }
    Ok((signatures, truncated, stats))
}

/// Maps each address that appears as a vtable slot to `Class::vfunc_N`.
fn slot_anchor_names(classes: &[ClassSlots], wanted: &HashSet<u64>) -> HashMap<u64, String> {
    let mut out: HashMap<u64, String> = HashMap::new();
    for class in classes {
        for group in &class.record.vtable_groups {
            for slot in &group.slots {
                if !wanted.contains(&slot.address) || out.contains_key(&slot.address) {
                    continue;
                }
                out.insert(
                    slot.address,
                    format!("vtable:{}::vfunc_{}", class.record.name, slot.slot),
                );
            }
        }
    }
    out
}

/// Finds the most specific locator for a function whose bytes are not unique.
///
/// Order of preference: the declaring vtable slot, a caller that no other
/// member of the group has, a string only this function references, then a
/// `caller+offset` pair. `None` means the function genuinely cannot be located
/// by anything but its group.
fn find_anchor(
    address: u64,
    slot_names: &HashMap<u64, String>,
    xrefs: &crate::xref::XrefTable,
    group_members: &HashSet<u64>,
    callers: &crate::xref::ReverseIndex,
    referrers: &crate::xref::ReverseIndex,
) -> Option<Box<str>> {
    if let Some(name) = slot_names.get(&address) {
        return Some(name.as_str().into());
    }
    let distinct: Vec<u64> = callers
        .sources(address)
        .into_iter()
        .filter(|caller| !group_members.contains(caller))
        .collect();
    if distinct.len() == 1 {
        return Some(format!("caller:0x{:x}", distinct[0]).into());
    }
    if let Some(entry) = xrefs.function_at(address) {
        for reference in xrefs.data_refs_of(entry) {
            let referrers = referrers.sources(*reference);
            if referrers.len() == 1 && referrers[0] == address {
                return Some(format!("data:0x{reference:x}").into());
            }
        }
        if let Some(call) = entry.first_call_site {
            return Some(format!("caller:0x{:x}+0x{:x}", call.target, call.site).into());
        }
    }
    None
}

/// Peak resident set size of the process, in MiB, when the OS exposes it.
fn peak_resident_mb() -> Option<u64> {
    let status = std::fs::read_to_string("/proc/self/status").ok()?;
    let line = status.lines().find(|line| line.starts_with("VmHWM:"))?;
    let kilobytes: u64 = line.split_whitespace().nth(1)?.parse().ok()?;
    Some(kilobytes / 1024)
}

/// Merges the FDE and named addresses, and flags overlapping functions.
fn merge_addresses(
    functions: &[(u64, u64)],
    names: &HashMap<u64, NamedEntry>,
) -> (Vec<u64>, BTreeSet<u64>) {
    let mut addresses: BTreeSet<u64> = functions.iter().map(|(start, _)| *start).collect();
    addresses.extend(names.keys().copied());
    let mut suspect = BTreeSet::new();
    for pair in functions.windows(2) {
        let (start, size) = pair[0];
        let (next_start, _) = pair[1];
        if size > 0 && start.saturating_add(size) > next_start {
            suspect.insert(start);
            suspect.insert(next_start);
        }
    }
    (addresses.into_iter().collect(), suspect)
}

/// Finds the size recorded for a named symbol.
fn named_size(symbols: &[NamedSymbol], address: u64, name: &str) -> Option<u64> {
    symbols
        .iter()
        .find(|symbol| symbol.address == address && symbol.name == name)
        .map(|symbol| symbol.size)
        .filter(|size| *size > 0)
}

/// Classifies a function's prologue and detects a simple tail-call thunk.
fn classify_function(
    map: &SectionMap,
    image: &[u8],
    address: u64,
    verifier: &PrologueVerifier,
) -> (String, Option<u64>) {
    let Some(file_offset) = file_offset_of(map, address) else {
        return ("none".to_string(), None);
    };
    let text_offset = (file_offset.saturating_sub(map.text.offset)) as usize;
    let text = map.bytes_of(TEXT_SECTION, image);
    if text_offset >= text.len() {
        return ("none".to_string(), None);
    }
    let verdict = verifier.verify(text, text_offset);
    let kind = if verdict.accepted {
        prologue_name(verdict.kind)
    } else {
        "leaf".to_string()
    };
    let target = thunk_target(text, text_offset, address);
    (kind, target)
}

/// Detects an unconditional `b <target>` tail call at the start of a function.
fn thunk_target(text: &[u8], offset: usize, address: u64) -> Option<u64> {
    let bytes = text.get(offset..offset + 4)?;
    let word = u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]);
    if word & 0xFC00_0000 == 0x1400_0000 {
        let immediate = sign_extend((word & 0x03FF_FFFF) as i64, 26) << 2;
        return Some((address as i64 + immediate) as u64);
    }
    None
}

/// Whether the function at `from` is a `b <to>` thunk.
fn is_thunk_to(map: &SectionMap, image: &[u8], from: u64, to: u64) -> bool {
    let Some(file_offset) = file_offset_of(map, from) else {
        return false;
    };
    let text_offset = (file_offset.saturating_sub(map.text.offset)) as usize;
    let text = map.bytes_of(TEXT_SECTION, image);
    thunk_target(text, text_offset, from) == Some(to)
}

/// Whether the function at `from` calls `to` within its first few instructions.
///
/// The deleting destructor (`D0`) is not a plain tail call: it calls the
/// complete destructor (`D1`) and then `operator delete`. Looking for a `bl`
/// to the first slot catches that shape without decoding the whole function.
fn calls_target(map: &SectionMap, image: &[u8], from: u64, to: u64) -> bool {
    const INSTRUCTIONS: usize = 12;
    let Some(file_offset) = file_offset_of(map, from) else {
        return false;
    };
    let text_offset = (file_offset.saturating_sub(map.text.offset)) as usize;
    let text = map.bytes_of(TEXT_SECTION, image);
    let end = text_offset.saturating_add(INSTRUCTIONS * 4).min(text.len());
    let mut offset = text_offset;
    while offset + 4 <= end {
        let word = u32::from_le_bytes([
            text[offset],
            text[offset + 1],
            text[offset + 2],
            text[offset + 3],
        ]);
        if word & 0xFC00_0000 == 0x9400_0000 {
            let immediate = sign_extend((word & 0x03FF_FFFF) as i64, 26) << 2;
            if (from as i64 + immediate) as u64 == to {
                return true;
            }
        }
        offset += 4;
    }
    false
}

/// Maps a prologue kind onto the JSON spelling.
fn prologue_name(kind: PrologueKind) -> String {
    match kind {
        PrologueKind::FramePush => "frame_push",
        PrologueKind::RegisterSave => "register_save",
        PrologueKind::StackAllocation => "stack_allocation",
        PrologueKind::FramePointerSetup => "frame_pointer_setup",
        PrologueKind::PointerAuthentication => "pointer_authentication",
        PrologueKind::OffsetFramePush => "offset_frame_push",
        PrologueKind::BranchTargetLanding => "branch_target_landing",
        PrologueKind::NotAPrologue => "none",
    }
    .to_string()
}

/// Finds the function containing `address`.
fn enclosing_function(functions: &[(u64, u64)], address: u64) -> Option<(u64, u64)> {
    let index = functions.partition_point(|(start, _)| *start <= address);
    let (start, size) = *functions.get(index.checked_sub(1)?)?;
    if size == 0 || address < start.saturating_add(size) {
        Some((start, size))
    } else {
        None
    }
}

/// Reads a little-endian `u64` at a virtual address.
pub(crate) fn read_at(map: &SectionMap, image: &[u8], address: u64) -> Option<u64> {
    let section = map.sections.iter().find(|section| {
        section.occupies_file
            && address >= section.address
            && address.saturating_add(8) <= section.address.saturating_add(section.size)
    })?;
    let bytes = map.bytes_of(&section.name, image);
    let offset = (address - section.address) as usize;
    bytes.get(offset..offset + 8).map(|slice| {
        u64::from_le_bytes([
            slice[0], slice[1], slice[2], slice[3], slice[4], slice[5], slice[6], slice[7],
        ])
    })
}

/// Reads a relocated pointer at a virtual address.
fn read_pointer(
    map: &SectionMap,
    image: &[u8],
    relocation: &RelocationIndex,
    address: u64,
) -> Option<u64> {
    let raw = read_at(map, image, address)?;
    let target = relocation.target_of(address).unwrap_or(raw);
    if target == 0 {
        None
    } else {
        Some(target)
    }
}

/// Converts a virtual address to a file offset.
pub(crate) fn file_offset_of(map: &SectionMap, address: u64) -> Option<u64> {
    let section = map.sections.iter().find(|section| {
        section.occupies_file
            && address >= section.address
            && address < section.address.saturating_add(section.size)
    })?;
    Some(address.wrapping_sub(section.delta))
}

/// Whether `address` lies inside an executable section.
pub(crate) fn is_executable(map: &SectionMap, address: u64) -> bool {
    map.sections.iter().any(|section| {
        section.executable
            && address >= section.address
            && address < section.address.saturating_add(section.size)
    })
}

/// Sign-extends the low `bits` of `value`.
fn sign_extend(value: i64, bits: u32) -> i64 {
    let shift = 64 - bits;
    (value << shift) >> shift
}

/// Serialises a `u64` as a `0x`-prefixed hex string.
fn hex_u64<S: serde::Serializer>(value: &u64, serializer: S) -> Result<S::Ok, S::Error> {
    serializer.serialize_str(&format!("0x{value:x}"))
}

/// Serialises an optional address as a hex string.
fn hex_option<S: serde::Serializer>(value: &Option<u64>, serializer: S) -> Result<S::Ok, S::Error> {
    match value {
        Some(value) => serializer.serialize_str(&format!("0x{value:x}")),
        None => serializer.serialize_none(),
    }
}

/// Reads the DWARF length field, returning `(length, header bytes)`.
fn read_length(bytes: &[u8], offset: usize) -> (usize, usize) {
    let first = read_u32(bytes, offset);
    if first == 0xffff_ffff {
        (read_u64(bytes, offset + 4) as usize, 12)
    } else {
        (first as usize, 4)
    }
}

/// Reads a little-endian `u32`, or zero when it runs off the end.
fn read_u32(bytes: &[u8], offset: usize) -> u32 {
    match bytes.get(offset..offset + 4) {
        Some(slice) => u32::from_le_bytes([slice[0], slice[1], slice[2], slice[3]]),
        None => 0,
    }
}

/// A parsed CIE, reduced to what an FDE needs.
struct Cie {
    fde_encoding: u8,
    has_augmentation_length: bool,
}

/// Parses `.eh_frame` into `(start, size)` function ranges.
fn parse_eh_frame(map: &SectionMap, image: &[u8]) -> CoreResult<Vec<(u64, u64)>> {
    let Some(section) = map.find(".eh_frame") else {
        return Ok(Vec::new());
    };
    let bytes = map.bytes_of(&section.name, image);
    if bytes.is_empty() {
        return Ok(Vec::new());
    }
    let datarel_base = map
        .find(".eh_frame_hdr")
        .map(|header| header.address)
        .unwrap_or(section.address);
    let mut cies: HashMap<usize, Cie> = HashMap::new();
    let mut functions = Vec::new();
    let mut position = 0usize;
    while position + 4 <= bytes.len() {
        let record_start = position;
        let (length, header_len) = read_length(bytes, position);
        if length == 0 {
            break;
        }
        let content_start = position + header_len;
        let content_end = match content_start.checked_add(length) {
            Some(end) if end <= bytes.len() => end,
            _ => break,
        };
        let id = read_u32(bytes, content_start);
        if id == 0 {
            if let Some(cie) = parse_cie(bytes, content_start + 4, content_end) {
                cies.insert(record_start, cie);
            }
        } else {
            let cie_start = content_start.saturating_sub(id as usize);
            if let Some(cie) = cies.get(&cie_start) {
                if let Some(range) = parse_fde(
                    bytes,
                    content_start + 4,
                    content_end,
                    section.address,
                    datarel_base,
                    cie,
                ) {
                    if range.1 > 0 {
                        functions.push(range);
                    }
                }
            }
        }
        position = content_end;
    }
    functions.retain(|(start, _)| is_executable(map, *start));
    functions.sort_unstable();
    functions.dedup();
    Ok(functions)
}

/// Parses a CIE record body.
fn parse_cie(bytes: &[u8], mut position: usize, end: usize) -> Option<Cie> {
    if position >= end {
        return None;
    }
    let _version = bytes[position];
    position += 1;
    let augmentation_start = position;
    while position < end && bytes[position] != 0 {
        position += 1;
    }
    let augmentation = bytes.get(augmentation_start..position)?;
    position += 1;
    let _code_align = read_uleb(bytes, &mut position)?;
    let _data_align = read_sleb(bytes, &mut position)?;
    let _return_register = read_uleb(bytes, &mut position)?;

    let has_augmentation_length = augmentation.first() == Some(&b'z');
    let mut fde_encoding = 0u8;
    if has_augmentation_length {
        let augmentation_length = read_uleb(bytes, &mut position)? as usize;
        let augmentation_end = position.saturating_add(augmentation_length).min(end);
        for &character in augmentation.iter().skip(1) {
            match character {
                b'R' => {
                    fde_encoding = *bytes.get(position)?;
                    position += 1;
                }
                b'L' => {
                    position += 1;
                }
                b'P' => {
                    let encoding = *bytes.get(position)?;
                    position += 1;
                    skip_encoded(bytes, &mut position, encoding, 0, 0)?;
                }
                _ => {}
            }
            if position > augmentation_end {
                break;
            }
        }
        let _ = augmentation_end;
    }
    Some(Cie {
        fde_encoding,
        has_augmentation_length,
    })
}

/// Parses an FDE record body into a `(start, size)` range.
fn parse_fde(
    bytes: &[u8],
    mut position: usize,
    end: usize,
    section_address: u64,
    datarel_base: u64,
    cie: &Cie,
) -> Option<(u64, u64)> {
    let initial = read_encoded(
        bytes,
        &mut position,
        cie.fde_encoding,
        section_address,
        datarel_base,
    )?;
    let range = read_encoded(
        bytes,
        &mut position,
        cie.fde_encoding & 0x0f,
        section_address,
        datarel_base,
    )?;
    if cie.has_augmentation_length {
        let _augmentation_length = read_uleb(bytes, &mut position)?;
    }
    if position > end || initial == 0 || range == 0 || initial % 4 != 0 {
        return None;
    }
    Some((initial, range))
}

/// `DW_EH_PE_omit`: the encoded value is absent.
///
/// It is a sentinel, not a format: reading it as `0x0f` would run off the
/// augmentation record. A CIE without an `R` augmentation leaves `fde_encoding`
/// at `0`, which is `DW_EH_PE_absptr` and is valid, so the two must be
/// distinguished.
const DW_EH_PE_OMIT: u8 = 0xff;

/// Reads an encoded DWARF pointer.
///
/// `encoding == 0` (that is, `DW_EH_PE_absptr`) is a real encoding: a
/// pointer-sized absolute address. Treating it as invalid dropped every FDE
/// whose CIE omits the `R` augmentation.
fn read_encoded(
    bytes: &[u8],
    position: &mut usize,
    encoding: u8,
    section_address: u64,
    datarel_base: u64,
) -> Option<u64> {
    if encoding == DW_EH_PE_OMIT {
        return None;
    }
    let format = encoding & 0x0f;
    let application = encoding & 0x70;
    let field_address = section_address.wrapping_add(*position as u64);
    let value = read_format(bytes, position, format)?;
    let base = match application {
        0x10 => field_address as i128,
        0x30 => datarel_base as i128,
        _ => 0,
    };
    Some((value + base) as u64)
}

/// Skips an encoded pointer without returning it.
///
/// An omitted value occupies no bytes, so it succeeds without advancing.
fn skip_encoded(
    bytes: &[u8],
    position: &mut usize,
    encoding: u8,
    section_address: u64,
    datarel_base: u64,
) -> Option<()> {
    if encoding == DW_EH_PE_OMIT {
        return Some(());
    }
    read_encoded(bytes, position, encoding, section_address, datarel_base)?;
    Some(())
}

/// Reads one DWARF-encoded value, returning it sign-extended into `i128`.
fn read_format(bytes: &[u8], position: &mut usize, format: u8) -> Option<i128> {
    match format {
        0x00 => {
            let value = read_u64(bytes, *position);
            *position += 8;
            Some(value as i128)
        }
        0x01 => {
            let value = read_uleb(bytes, position)?;
            Some(value as i128)
        }
        0x02 => {
            let value = read_u16(bytes, *position)?;
            *position += 2;
            Some(value as i128)
        }
        0x03 => {
            let value = read_u32(bytes, *position);
            *position += 4;
            Some(value as i128)
        }
        0x04 => {
            let value = read_u64(bytes, *position);
            *position += 8;
            Some(value as i128)
        }
        0x09 => {
            let value = read_sleb(bytes, position)?;
            Some(value as i128)
        }
        0x0a => {
            let value = read_u16(bytes, *position)? as i16 as i128;
            *position += 2;
            Some(value)
        }
        0x0b => {
            let value = read_u32(bytes, *position) as i32 as i128;
            *position += 4;
            Some(value)
        }
        0x0c => {
            let value = read_u64(bytes, *position) as i64 as i128;
            *position += 8;
            Some(value)
        }
        _ => None,
    }
}

/// Reads a little-endian `u16`, or `None` when it runs off the end.
fn read_u16(bytes: &[u8], offset: usize) -> Option<u16> {
    bytes
        .get(offset..offset + 2)
        .map(|slice| u16::from_le_bytes([slice[0], slice[1]]))
}

/// Reads an unsigned LEB128 value.
fn read_uleb(bytes: &[u8], position: &mut usize) -> Option<u64> {
    let mut result = 0u64;
    let mut shift = 0u32;
    loop {
        let byte = *bytes.get(*position)?;
        *position += 1;
        result |= u64::from(byte & 0x7f) << shift;
        if byte & 0x80 == 0 {
            return Some(result);
        }
        shift += 7;
        if shift >= 64 {
            return None;
        }
    }
}

/// Reads a signed LEB128 value.
fn read_sleb(bytes: &[u8], position: &mut usize) -> Option<i64> {
    let mut result = 0i64;
    let mut shift = 0u32;
    let mut byte;
    loop {
        byte = *bytes.get(*position)?;
        *position += 1;
        result |= i64::from(byte & 0x7f) << shift;
        shift += 7;
        if byte & 0x80 == 0 {
            break;
        }
        if shift >= 64 {
            return None;
        }
    }
    if shift < 64 && byte & 0x40 != 0 {
        result |= !0i64 << shift;
    }
    Some(result)
}

/// Start and length pairs of the functions found in the image.
type FunctionRanges = Vec<(u64, u64)>;

/// Function ranges, the source they came from, and the FDE count before dedup.
type CollectedFunctions = (FunctionRanges, &'static str, usize);

/// Falls back to a prologue walk when `.eh_frame` is absent.
pub(crate) fn collect_functions(
    map: &SectionMap,
    image: &[u8],
    control: &ScanControl,
) -> CoreResult<CollectedFunctions> {
    if map.find(".eh_frame").is_some() {
        if let Ok(mut functions) = parse_eh_frame(map, image) {
            // Every FDE names one function, so the raw count is the number the
            // table must match; the dedup below can only make it smaller.
            let fde_functions = functions.len();
            // A few FDEs describe the same start (thunks and aliases); keeping
            // one entry per address halves the table on large libraries.
            functions.sort_unstable_by_key(|(start, _)| *start);
            functions.dedup_by_key(|(start, _)| *start);
            if !functions.is_empty() {
                // `.eh_frame` is not complete: leaf functions, outlined blocks
                // and hand-written assembly stubs often have no FDE. Walk the
                // gaps between FDEs and every direct call target so those
                // functions are not invisible to the table.
                let filled = fill_function_gaps(map, image, &functions, control)?;
                return Ok((filled, "eh_frame", fde_functions));
            }
        }
    }
    let functions = scan_prologues(map, image, control)?;
    Ok((functions, "prologue_scan", 0))
}

/// Adds function starts that `.eh_frame` does not describe.
///
/// Two sources are used: a verified prologue inside a gap between FDE ranges,
/// and the target of a direct `BL`, which is a function start by definition.
/// Only addresses outside every FDE range are considered, so the FDE sizes that
/// the rest of the pipeline relies on are never rewritten.
fn fill_function_gaps(
    map: &SectionMap,
    image: &[u8],
    fde: &[(u64, u64)],
    control: &ScanControl,
) -> CoreResult<Vec<(u64, u64)>> {
    let text = map.bytes_of(TEXT_SECTION, image);
    if text.len() < 4 {
        return Ok(fde.to_vec());
    }
    let text_base = map.text.address;
    let text_end = text_base.saturating_add(text.len() as u64);

    // Merge the FDE ranges so a coverage query is a binary search.
    let mut ranges: Vec<(u64, u64)> = fde
        .iter()
        .map(|(start, size)| (*start, start.saturating_add(*size).min(text_end)))
        .filter(|(start, end)| end > start)
        .collect();
    ranges.sort_unstable();
    let mut merged: Vec<(u64, u64)> = Vec::with_capacity(ranges.len());
    for (start, end) in ranges {
        if let Some(last) = merged.last_mut() {
            if start <= last.1 {
                last.1 = last.1.max(end);
                continue;
            }
        }
        merged.push((start, end));
    }
    let covered = |address: u64| -> bool {
        match merged.binary_search_by_key(&address, |(start, _)| *start) {
            Ok(_) => true,
            Err(index) => index > 0 && address < merged[index - 1].1,
        }
    };

    let verifier = PrologueVerifier::new()?;
    let limit = fde.len().saturating_mul(4).saturating_add(65_536);
    let mut candidates: Vec<u64> = Vec::new();
    for (index, chunk) in text.as_chunks::<4>().0.iter().enumerate() {
        if index % (1024 * 1024) == 0 && control.is_cancelled() {
            return Err(CoreError::Cancelled);
        }
        let address = text_base + (index as u64) * 4;
        let word = u32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]);

        if word & 0xFC00_0000 == 0x9400_0000 {
            // `bl` target: a function start even without an FDE, provided it
            // lands in `.text`, outside every FDE range, and decodes as one.
            let target = crate::xref::branch_target(address, word & 0x03FF_FFFF, 26);
            if target >= text_base && target < text_end && !covered(target) {
                let target_offset = (target - text_base) as usize;
                if verifier.verify(text, target_offset).accepted {
                    candidates.push(target);
                    if candidates.len() >= limit {
                        break;
                    }
                }
            }
        }
        if covered(address) {
            continue;
        }
        if is_probable_prologue(word) && verifier.verify(text, index * 4).accepted {
            candidates.push(address);
            if candidates.len() >= limit {
                break;
            }
        }
    }

    if candidates.is_empty() {
        return Ok(fde.to_vec());
    }

    let mut merged_functions: Vec<(u64, u64)> = fde.to_vec();
    merged_functions.extend(candidates.into_iter().map(|address| (address, 0)));
    merged_functions.sort_unstable_by_key(|(start, _)| *start);
    // A start that an FDE already described keeps its FDE size.
    let mut deduped: Vec<(u64, u64)> = Vec::with_capacity(merged_functions.len());
    for (start, size) in merged_functions {
        if let Some(last) = deduped.last_mut() {
            if last.0 == start {
                if last.1 == 0 {
                    last.1 = size;
                }
                continue;
            }
        }
        deduped.push((start, size));
    }
    // A gap-filled start runs to the next start; the last one to the end of
    // `.text`.
    let total = deduped.len();
    for index in 0..total {
        if deduped[index].1 == 0 {
            let next = deduped
                .get(index + 1)
                .map(|(start, _)| *start)
                .unwrap_or(text_end);
            deduped[index].1 = next.saturating_sub(deduped[index].0);
        }
    }
    Ok(deduped)
}

/// Walks `.text` for recognisable prologues when no unwind table is present.
fn scan_prologues(
    map: &SectionMap,
    image: &[u8],
    control: &ScanControl,
) -> CoreResult<Vec<(u64, u64)>> {
    let text = map.bytes_of(TEXT_SECTION, image);
    if text.is_empty() {
        return Ok(Vec::new());
    }
    let verifier = PrologueVerifier::new()?;
    let mut starts = Vec::new();
    for offset in (0..text.len().saturating_sub(4)).step_by(4) {
        if offset % (1024 * 1024) == 0 && control.is_cancelled() {
            return Err(CoreError::Cancelled);
        }
        let word = read_u32(text, offset);
        if !is_probable_prologue(word) {
            continue;
        }
        if verifier.verify(text, offset).accepted {
            starts.push(map.text.address + offset as u64);
        }
    }
    let mut functions = Vec::with_capacity(starts.len());
    for (index, start) in starts.iter().enumerate() {
        let next = starts
            .get(index + 1)
            .copied()
            .unwrap_or(map.text.address + text.len() as u64);
        functions.push((*start, next.saturating_sub(*start)));
    }
    Ok(functions)
}

/// A cheap pre-filter for the prologue walk.
///
/// It only has to be a superset of what the verifier accepts: a false positive
/// costs one Capstone decode, a false negative loses a function entirely.
fn is_probable_prologue(word: u32) -> bool {
    // PACIASP / PACIBSP / BTI C
    word == 0xD503_233F
        || word == 0xD503_237F
        || word == 0xD503_245F
        // stp <two regs>, [sp, #-imm]! — includes the canonical x29/x30 frame
        // push and a callee-saved x19..x28 save.
        || word & 0xFFC0_0000 == 0xA980_0000
        // stp x29, x30, [sp, #imm] — a frame push with a positive offset.
        || word & 0xFFC0_0000 == 0xA900_0000
        // sub sp, sp, #imm
        || word & 0xFFC0_03FF == 0xD100_03FF
        // mov x29, sp
        || word == 0x9100_03FD
        // str x29, [sp, #-imm]! — a leaf that saves only the frame pointer.
        || word & 0xFFE0_0FFF == 0xF800_0C1D
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sections::SectionInfo;
    use crate::verify::VerificationStatus;

    #[test]
    fn sign_extension_restores_negative_values() {
        assert_eq!(sign_extend(0b1, 1), -1);
        assert_eq!(sign_extend(0b0_1111, 4), -1);
        assert_eq!(sign_extend(0b0_1000, 4), -8);
        assert_eq!(sign_extend(7, 4), 7);
    }

    #[test]
    fn uleb_and_sleb_round_trip_small_values() {
        let mut position = 0;
        assert_eq!(read_uleb(&[0xE5, 0x8E, 0x26], &mut position), Some(624_485));
        assert_eq!(position, 3);

        let mut position = 0;
        assert_eq!(read_sleb(&[0x7F], &mut position), Some(-1));
        let mut position = 0;
        assert_eq!(read_sleb(&[0x3F], &mut position), Some(63));
    }

    #[test]
    fn adrp_page_is_computed_from_the_immediate() {
        // `adrp x16, 0x1000` at pc 0x1000 is 0x90000010.
        assert_eq!(adrp_page(0x1000, 0x9000_0010), 0x1000);
    }

    #[test]
    fn a_branch_is_recognised_as_a_thunk() {
        // `b +0x10` at 0x1000: imm26 = 4.
        let mut text = vec![0u8; 4];
        let word = 0x1400_0000u32 | 4;
        text.copy_from_slice(&word.to_le_bytes());
        assert_eq!(thunk_target(&text, 0, 0x1000), Some(0x1010));
    }

    #[test]
    fn name_priority_prefers_symbols_over_slots() {
        assert!(rank("symbol") < rank("vtable"));
        assert!(rank("vtable") < rank("signature"));
        assert!(rank("signature") < rank("auto"));
    }

    #[test]
    fn a_lambda_name_beats_a_vtable_placeholder_and_keeps_confidence() {
        let mut names = HashMap::new();
        insert_name(
            &mut names,
            0x1000,
            "Mob::vfunc_0".to_string(),
            "vtable",
            "function",
            None,
            None,
            None,
        );
        insert_name(
            &mut names,
            0x1000,
            "Level::tick()::$_0::operator()".to_string(),
            "lambda",
            "function",
            None,
            None,
            Some("high"),
        );
        let entry = &names[&0x1000];
        assert_eq!(entry.name, "Level::tick()::$_0::operator()");
        assert_eq!(entry.source, "lambda");
        assert_eq!(entry.confidence, Some("high"));
        assert!(entry.aliases.contains(&"Mob::vfunc_0".to_string()));
    }

    #[test]
    fn a_better_name_demotes_the_old_one_to_an_alias() {
        let mut names = HashMap::new();
        insert_name(
            &mut names,
            0x1000,
            "Class::vfunc_3".to_string(),
            "vtable",
            "function",
            None,
            None,
            None,
        );
        insert_name(
            &mut names,
            0x1000,
            "update".to_string(),
            "symbol",
            "function",
            None,
            None,
            None,
        );
        let entry = names.get(&0x1000).expect("entry");
        assert_eq!(entry.name, "update");
        assert!(entry.aliases.contains(&"Class::vfunc_3".to_string()));
    }

    #[test]
    fn enclosing_function_finds_the_range() {
        let functions = vec![(0x1000, 0x100), (0x2000, 0x40)];
        assert_eq!(
            enclosing_function(&functions, 0x1010),
            Some((0x1000, 0x100))
        );
        assert_eq!(enclosing_function(&functions, 0x2000), Some((0x2000, 0x40)));
        assert_eq!(enclosing_function(&functions, 0x3000), None);
    }

    fn section(name: &str, address: u64, offset: u64, size: u64, executable: bool) -> SectionInfo {
        SectionInfo {
            name: name.to_string(),
            kind: crate::sections::SectionKind::from_name(name),
            address,
            size,
            offset,
            align: 8,
            delta: address.wrapping_sub(offset),
            allocated: true,
            executable,
            writable: false,
            occupies_file: true,
        }
    }

    /// Distance between planted function starts.

    #[test]
    fn the_slot_cap_constant_covers_every_value_the_screen_offers() {
        // The Settings screen offers 1024, 4096 and 16384; a cap the user can
        // pick must never be reduced by the engine behind their back.
        const {
            assert!(
                MAX_SLOTS_PER_CLASS >= 16_384,
                "cap must cover the UI's 16384"
            )
        };
        assert_eq!(InternalOptions::default().max_slots_per_class, 4096);
    }

    fn map_with(text: SectionInfo, extra: Vec<SectionInfo>) -> SectionMap {
        let mut sections = vec![text.clone()];
        sections.extend(extra);
        SectionMap {
            image_base: text.address,
            text,
            sections,
        }
    }

    /// Builds a `.eh_frame` with one CIE and one FDE for `0x1000..0x1040`.
    fn eh_frame_bytes(zplr: bool) -> Vec<u8> {
        let mut cie_body = vec![1u8];
        cie_body.extend_from_slice(if zplr { b"zPLR\0" } else { b"zR\0" });
        cie_body.push(1); // code alignment
        cie_body.push(0x78); // data alignment -8
        cie_body.push(30); // return register
        let mut augmentation = Vec::new();
        if zplr {
            augmentation.push(0x1B); // P: pcrel | sdata4
            augmentation.extend_from_slice(&[0, 0, 0, 0]);
            augmentation.push(0); // L
            augmentation.push(0x1B); // R: pcrel | sdata4
        } else {
            augmentation.push(0x1B);
        }
        cie_body.push(augmentation.len() as u8);
        cie_body.extend_from_slice(&augmentation);

        let mut record = Vec::new();
        // The length covers the CIE id plus its body.
        record.extend_from_slice(&(4 + cie_body.len() as u32).to_le_bytes());
        record.extend_from_slice(&0u32.to_le_bytes());
        record.extend_from_slice(&cie_body);

        let fde_start = record.len();
        let content_start = fde_start + 4;
        let id = content_start as u32;
        let initial_position = content_start + 4;
        let field_address = 0x5000 + initial_position as u64;
        let initial = (0x1000i64 - field_address as i64) as i32;
        let mut body = Vec::new();
        body.extend_from_slice(&initial.to_le_bytes());
        body.extend_from_slice(&0x40i32.to_le_bytes());
        // Every `z` augmentation carries its own length, even when empty.
        body.push(0);
        record.extend_from_slice(&(4 + body.len() as u32).to_le_bytes());
        record.extend_from_slice(&id.to_le_bytes());
        record.extend_from_slice(&body);
        record
    }

    #[test]
    fn eh_frame_reads_pcrel_fdes_with_zr_and_zplr() {
        for zplr in [false, true] {
            let eh = eh_frame_bytes(zplr);
            let mut image = vec![0u8; 0x6000];
            image[0x5000..0x5000 + eh.len()].copy_from_slice(&eh);
            let map = map_with(
                section(".text", 0x1000, 0x1000, 0x100, true),
                vec![
                    section(".eh_frame", 0x5000, 0x5000, eh.len() as u64, false),
                    section(".eh_frame_hdr", 0x4000, 0x4000, 4, false),
                ],
            );

            assert_eq!(
                parse_eh_frame(&map, &image).expect("parse"),
                vec![(0x1000, 0x40)],
                "zplr={zplr}"
            );
        }
    }

    #[test]
    fn eh_frame_works_without_a_header_section() {
        let eh = eh_frame_bytes(false);
        let mut image = vec![0u8; 0x6000];
        image[0x5000..0x5000 + eh.len()].copy_from_slice(&eh);
        let map = map_with(
            section(".text", 0x1000, 0x1000, 0x100, true),
            vec![section(".eh_frame", 0x5000, 0x5000, eh.len() as u64, false)],
        );

        assert_eq!(
            parse_eh_frame(&map, &image).expect("parse"),
            vec![(0x1000, 0x40)]
        );
    }

    #[test]
    fn directional_encoding_zero_is_absptr_and_ff_is_omit() {
        let bytes = 0x1234_5678u64.to_le_bytes();

        // DW_EH_PE_absptr (0) is a real encoding: one pointer-sized address.
        let mut position = 0;
        assert_eq!(
            read_encoded(&bytes, &mut position, 0x00, 0, 0),
            Some(0x1234_5678)
        );
        assert_eq!(position, 8);

        // DW_EH_PE_omit (0xff) has no value and must not consume bytes.
        let mut position = 0;
        assert_eq!(read_encoded(&bytes, &mut position, 0xff, 0, 0), None);
        assert_eq!(position, 0);

        let mut position = 0;
        assert_eq!(skip_encoded(&bytes, &mut position, 0xff, 0, 0), Some(()));
        assert_eq!(position, 0);
    }

    #[test]
    fn collect_functions_reports_the_raw_fde_count() {
        let eh = eh_frame_bytes(false);
        let mut image = vec![0u8; 0x6000];
        image[0x5000..0x5000 + eh.len()].copy_from_slice(&eh);
        let map = map_with(
            section(".text", 0x1000, 0x1000, 0x100, true),
            vec![section(".eh_frame", 0x5000, 0x5000, eh.len() as u64, false)],
        );
        let control = ScanControl::new();

        let (functions, source, fdes) = collect_functions(&map, &image, &control).expect("walk");

        assert_eq!(source, "eh_frame");
        assert_eq!(functions, vec![(0x1000, 0x40)]);
        // The FDE count is the number the table has to match, so it must be
        // the raw count and not the deduplicated one.
        assert_eq!(fdes, 1);
    }

    #[test]
    fn every_resolve_mode_returns_the_expected_address() {
        let map = map_with(
            section(".text", 0x1000, 0x1000, 0x1000, true),
            vec![section(".rodata", 0x3000, 0x3000, 0x100, false)],
        );
        let mut image = vec![0u8; 0x4000];
        // BL from file offset 0x1000 to 0x2000: imm26 = 0x400.
        let branch = (0x9400_0000u32 | 0x400).to_le_bytes();
        image[0x1000..0x1004].copy_from_slice(&branch);
        // ADRP x0, 0x2000 then ADD x0, x0, #0x20 at 0x1004.
        let adrp = (0x9000_0000u32 | (1 << 29)).to_le_bytes();
        let add = (0x9100_0000u32 | (0x20 << 10)).to_le_bytes();
        image[0x1004..0x1008].copy_from_slice(&adrp);
        image[0x1008..0x100c].copy_from_slice(&add);

        let hit = |file_offset: u64, address: u64| crate::scanner::PatternHit {
            name: "x".to_string(),
            section_offset: 0,
            file_offset,
            address,
            address_hex: format!("0x{address:x}"),
            prologue_kind: None,
            prologue_instruction: None,
            verification_status: VerificationStatus::Unverified,
        };

        assert_eq!(
            resolve_pattern_hit(&image, &hit(0x1000, 0x1000), "self", 4, &[]),
            Some(0x1004)
        );
        assert_eq!(
            resolve_pattern_hit(&image, &hit(0x1000, 0x1000), "bl_target", 0, &[]),
            Some(0x2000)
        );
        assert_eq!(
            resolve_pattern_hit(&image, &hit(0x1004, 0x1004), "adrp_add", 0, &[]),
            Some(0x2020)
        );
        assert_eq!(
            resolve_pattern_hit(
                &image,
                &hit(0x1010, 0x1010),
                "prologue_back",
                0,
                &[(0x1000, 0x100)]
            ),
            Some(0x1000)
        );
        let _ = map;
    }

    /// `vtableOffset` is measured from the image base, which is not the file
    /// layout: `.data.rel.ro` starts 0x4000 after its file offset in the real
    /// 26.60 library. A class record must carry the real file offset too, or a
    /// consumer patching at `vtableOffset` writes into the wrong place.
    #[test]
    fn the_auto_name_is_the_rva_from_the_load_base() {
        // A non-zero load base: the name must be `address - base`, which is what
        // the row's own `address` field lets a reader reconstruct. Deriving it
        // from the first allocated section instead shifted all 293,854 such
        // names in the 26.60 library by 0x2463f0.
        assert_eq!(auto_name(0x1389f8a8, 0x10000), "sub_1388f8a8");
        assert_eq!(auto_name(0x10000, 0x10000), "sub_0");
        assert_eq!(auto_name(0x400000, 0), "sub_400000");
    }

    #[test]
    fn a_class_carries_a_file_offset_not_only_the_image_base_offset() {
        let text = section(".text", 0x1000, 0x1000, 0x1000, true);
        let rel_ro = section(".data.rel.ro", 0x8000, 0x4000, 0x1000, false);
        let map = map_with(text, vec![rel_ro]);
        let mut image = vec![0u8; 0x9000];
        // vtable: offset-to-top 0, type_info pointer, one function pointer.
        image[0x4000..0x4008].copy_from_slice(&0u64.to_le_bytes());
        image[0x4008..0x4010].copy_from_slice(&0x7000u64.to_le_bytes());
        image[0x4010..0x4018].copy_from_slice(&0x1100u64.to_le_bytes());
        let vtables = vtables(vec![type_vtable("First", 0x8000, 0x7000)]);
        let relocations =
            crate::rtti::RelocationIndex::for_tests(vec![(0x8008, 0x7000), (0x8010, 0x1100)]);

        let (classes, _) = collect_class_slots(
            &map,
            &image,
            &vtables,
            &relocations,
            None,
            4096,
            &crate::scanner::ScanControl::new(),
        )
        .expect("slots");

        let record = &classes[0].record;
        assert_eq!(record.vtable, 0x8000);
        // 0x8000 is 0x4000 into `.data.rel.ro`, which starts at file 0x0.
        assert_eq!(record.file_offset, Some(0x4000), "{:?}", record.file_offset);
        assert_ne!(
            record.file_offset,
            Some(record.vtable_offset),
            "the image-base offset is not the file offset"
        );
    }

    #[test]
    fn addresses_round_trip_through_the_section_table() {
        let map = map_with(
            section(".text", 0x1000, 0x1000, 0x100, true),
            vec![section(".data", 0x3000, 0x3000, 0x100, false)],
        );
        let mut image = vec![0u8; 0x4000];
        image[0x3010..0x3018].copy_from_slice(&0x1234_5678_9ABC_DEF0u64.to_le_bytes());

        assert_eq!(read_at(&map, &image, 0x3010), Some(0x1234_5678_9ABC_DEF0));
        assert_eq!(file_offset_of(&map, 0x3010), Some(0x3010));
        assert!(is_executable(&map, 0x1000));
        assert!(!is_executable(&map, 0x3010));
    }

    fn vtable(name: &str, type_info: u64) -> crate::rtti::TypeVTable {
        vtable_of_kind(name, type_info, crate::rtti::TypeInfoKind::Class)
    }

    fn vtable_of_kind(
        name: &str,
        type_info: u64,
        kind: crate::rtti::TypeInfoKind,
    ) -> crate::rtti::TypeVTable {
        crate::rtti::TypeVTable {
            name: name.to_string(),
            base_name: name.to_string(),
            mangled: name.to_string(),
            vtable_address: type_info + 0x100,
            vtable_hex: format!("0x{:x}", type_info + 0x100),
            vtable_offset: type_info + 0x100,
            type_info_address: type_info,
            type_info_kind: kind,
            offset_to_top: 0,
        }
    }

    fn vtables(entries: Vec<crate::rtti::TypeVTable>) -> crate::rtti::VtableMap {
        let all = entries.clone();
        crate::rtti::VtableMap {
            source: crate::rtti::VtableSource::Relocations,
            type_info_candidates: entries.len(),
            relocations_examined: 0,
            entries: entries
                .into_iter()
                .map(|v| (v.mangled.clone(), v))
                .collect(),
            all,
        }
    }

    #[test]
    fn si_class_type_info_names_the_first_base() {
        let map = map_with(
            section(".text", 0x1000, 0x1000, 0x100, true),
            vec![section(".data.rel.ro", 0x3000, 0x3000, 0x200, false)],
        );
        let mut image = vec![0u8; 0x4000];
        // Derived's type_info at 0x3080 points at Base's type_info at 0x3000.
        image[0x3090..0x3098].copy_from_slice(&0x3000u64.to_le_bytes());
        let map_vtables = vtables(vec![
            vtable("Base", 0x3000),
            vtable_of_kind("Derived", 0x3080, crate::rtti::TypeInfoKind::SiClass),
        ]);
        let relocations = crate::rtti::RelocationIndex::for_tests(Vec::new());

        let bases = resolve_bases(&map, &image, &relocations, &map_vtables);

        let found = bases.get("Derived").expect("Derived has a base");
        assert_eq!(found.len(), 1);
        assert_eq!(found[0].name, "Base");
        assert!(found[0].is_public);
        assert!(!found[0].is_virtual);
    }

    #[test]
    fn vmi_class_type_info_reads_every_base() {
        let map = map_with(
            section(".text", 0x1000, 0x1000, 0x100, true),
            vec![section(".data.rel.ro", 0x3000, 0x3000, 0x200, false)],
        );
        let mut image = vec![0u8; 0x4000];
        // Derived's type_info at 0x3080: no single base, count 2 at +20.
        image[0x3094..0x3098].copy_from_slice(&2u32.to_le_bytes());
        image[0x3098..0x30A0].copy_from_slice(&0x3000u64.to_le_bytes());
        image[0x30A8..0x30B0].copy_from_slice(&0x3000u64.to_le_bytes());
        image[0x30A0..0x30A8].copy_from_slice(&((16i64 << 8) | 1).to_le_bytes() as &[u8]);
        let map_vtables = vtables(vec![
            vtable("Base", 0x3000),
            vtable_of_kind("Derived", 0x3080, crate::rtti::TypeInfoKind::VmiClass),
        ]);
        let relocations = crate::rtti::RelocationIndex::for_tests(Vec::new());

        let bases = resolve_bases(&map, &image, &relocations, &map_vtables);

        let found = bases.get("Derived").expect("Derived has bases");
        assert_eq!(found.len(), 1, "duplicate base pointers collapse to one");
        assert_eq!(found[0].offset, 16);
        assert!(found[0].is_public);
    }

    fn class(name: &str, addresses: Vec<u64>) -> ClassSlots {
        let slots: Vec<Arc<SlotRecord>> = addresses
            .iter()
            .enumerate()
            .map(|(index, address)| {
                Arc::new(SlotRecord {
                    slot: index,
                    offset: (index as u64) * 8,
                    address: *address,
                    file_offset: Some(*address),
                    size: 4,
                    name: format!("{name}::vfunc_{index}"),
                    kind: "virtual".to_string(),
                    prologue_kind: "none".to_string(),
                    overrides: false,
                    inherited: false,
                    status: "verified".to_string(),
                    signature: None,
                    signature_unique: None,
                    signature_hex: None,
                    signature_mask: None,
                    signature_stability: None,
                    signature_anchor: None,
                    identical_group: None,
                    identical_group_size: None,
                })
            })
            .collect();
        ClassSlots {
            record: ClassRecord {
                name: name.to_string(),
                base_name: name.to_string(),
                mangled: name.to_string(),
                bases: Vec::new(),
                base_classes: Vec::new(),
                vtable: 0,
                file_offset: Some(0),
                vtable_offset: 0,
                type_info: 0,
                slot_count: addresses.len(),
                slots: slots.clone(),
                vtable_groups: vec![VtableGroup {
                    offset_to_top: 0,
                    address: 0,
                    slots,
                }],
            },
            addresses,
        }
    }

    #[test]
    fn a_name_learned_for_one_class_reaches_every_class_sharing_the_address() {
        let mut classes = vec![
            class("Mob", vec![0x1000, 0x2000]),
            class("Player", vec![0x1000, 0x2000]),
        ];
        classes[0].record.vtable_groups[0].slots[0] = Arc::new(SlotRecord {
            name: "Actor::getHealth".to_string(),
            ..(*classes[0].record.vtable_groups[0].slots[0]).clone()
        });
        unify_slot_names_by_address(&mut classes);
        assert_eq!(
            classes[1].record.vtable_groups[0].slots[0].name,
            "Actor::getHealth"
        );
        assert!(classes[1].record.vtable_groups[0].slots[0].inherited);
        // A different address keeps its own name.
        assert_eq!(
            classes[1].record.vtable_groups[0].slots[1].name,
            "Player::vfunc_1"
        );
    }

    #[test]
    fn inheritance_marks_identical_slots_inherited_and_different_ones_overridden() {
        let mut classes = vec![
            class("Base", vec![0x1000, 0x2000]),
            class("Derived", vec![0x1000, 0x3000]),
        ];
        let mut bases = HashMap::new();
        bases.insert(
            "Derived".to_string(),
            vec![BaseRecord {
                name: "Base".to_string(),
                mangled: "Base".to_string(),
                offset: 0,
                flags: 3,
                is_public: true,
                is_virtual: false,
            }],
        );

        apply_inheritance(&mut classes, &bases);

        let derived = &classes[1].record;
        assert!(derived.slots[0].inherited);
        assert!(!derived.slots[0].overrides);
        assert!(derived.slots[1].overrides);
        assert!(!derived.slots[1].inherited);
        assert_eq!(derived.bases, vec!["Base".to_string()]);
    }

    fn type_vtable(name: &str, vtable_address: u64, type_info: u64) -> crate::rtti::TypeVTable {
        crate::rtti::TypeVTable {
            name: name.to_string(),
            base_name: name.to_string(),
            mangled: name.to_string(),
            vtable_address,
            vtable_hex: format!("0x{vtable_address:x}"),
            vtable_offset: vtable_address,
            type_info_address: type_info,
            type_info_kind: crate::rtti::TypeInfoKind::Class,
            offset_to_top: 0,
        }
    }

    fn data_map() -> SectionMap {
        map_with(
            section(".text", 0x1000, 0x1000, 0x100, true),
            vec![section(".data.rel.ro", 0x3000, 0x3000, 0x400, false)],
        )
    }

    /// A slot can hold data, not only a function pointer: a virtual-base
    /// group starts with a vbase offset and a nested vtable slot points into
    /// `.data.rel.ro`. Naming such a slot would create a "function" at an
    /// address with no code, which the prologue check then "verifies".
    #[test]
    fn a_slot_pointing_at_data_is_not_named() {
        let map = map_with(
            section(".text", 0x1000, 0x1000, 0x100, true),
            vec![
                section(".data.rel.ro", 0x3000, 0x3000, 0x400, false),
                section(".rodata", 0x4000, 0x4000, 0x100, false),
            ],
        );
        let image = vec![0u8; 0x5000];
        let relocations = crate::rtti::RelocationIndex::for_tests(vec![
            (0x3008, 0x2f00),
            (0x3010, 0x1040),
            (0x3018, 0x4010),
        ]);
        let vtables = vtables(vec![type_vtable("Mob", 0x3000, 0x2f00)]);

        let (classes, _) = collect_class_slots(
            &map,
            &image,
            &vtables,
            &relocations,
            None,
            4096,
            &crate::scanner::ScanControl::new(),
        )
        .expect("slots");

        let mut names = HashMap::new();
        insert_slot_names(&map, &mut names, &classes);

        assert!(
            names.contains_key(&0x1040),
            "the code slot must still be named"
        );
        assert!(
            !names.contains_key(&0x4010),
            "a slot pointing into .rodata must not become a function"
        );
    }

    #[test]
    fn fde_sizes_answers_by_binary_search() {
        let sizes = FdeSizes::new(vec![(0x1000, 0x40), (0x1040, 0x18), (0x2000, 0)]);

        assert_eq!(sizes.get(0x1000), Some(0x40));
        assert_eq!(sizes.get(0x1040), Some(0x18));
        assert_eq!(sizes.get(0x1080), None);
        assert!(sizes.contains(0x2000));
        assert!(!sizes.contains(0x1fff));
    }

    #[test]
    fn a_record_is_written_as_one_ndjson_line() {
        #[derive(serde::Serialize)]
        struct Record {
            address: u64,
            name: String,
        }

        let record = Record {
            address: 0x1234,
            name: "Actor::~Actor".to_string(),
        };
        let mut out: Vec<u8> = Vec::new();
        write_record(&mut out, "function", "function", &record).unwrap();

        assert_eq!(
            String::from_utf8(out).unwrap(),
            "{\"type\":\"function\",\"function\":{\"address\":4660,\"name\":\"Actor::~Actor\"}}\n"
        );
    }

    #[test]
    fn a_pure_virtual_first_slot_does_not_end_the_vtable() {
        let map = data_map();
        let image = vec![0u8; 0x4000];
        // 0x1000 is `__cxa_pure_virtual`; the run must continue to 0x1010 and
        // only stop when a slot has no relocation at all.
        let relocations =
            crate::rtti::RelocationIndex::for_tests(vec![(0x3010, 0x1000), (0x3018, 0x1010)]);
        let vtables = vtables(vec![type_vtable("Actor", 0x3000, 0x2f00)]);

        let (classes, _truncated) = collect_class_slots(
            &map,
            &image,
            &vtables,
            &relocations,
            Some(0x1000),
            4096,
            &crate::scanner::ScanControl::new(),
        )
        .expect("slots");

        let slots = &classes[0].record.slots;
        assert_eq!(slots.len(), 2);
        assert_eq!(slots[0].kind, "pure_virtual");
        assert_eq!(slots[1].kind, "virtual");
    }

    #[test]
    fn an_unresolved_import_slot_is_kept_and_the_run_continues() {
        let map = data_map();
        let image = vec![0u8; 0x4000];
        let relocations = crate::rtti::RelocationIndex::for_tests(vec![
            (0x3010, 0x1000),
            (0x3018, 0),
            (0x3020, 0x1010),
        ]);
        let vtables = vtables(vec![type_vtable("Mob", 0x3000, 0x2f00)]);

        let (classes, _truncated) = collect_class_slots(
            &map,
            &image,
            &vtables,
            &relocations,
            None,
            4096,
            &crate::scanner::ScanControl::new(),
        )
        .expect("slots");

        let slots = &classes[0].record.slots;
        assert_eq!(slots.len(), 3);
        assert_eq!(slots[1].kind, "import");
        assert_eq!(slots[1].address, 0);
        assert_eq!(slots[2].address, 0x1010);
    }

    #[test]
    fn multiple_inheritance_keeps_every_vtable_group() {
        let map = data_map();
        let image = vec![0u8; 0x4000];
        let relocations =
            crate::rtti::RelocationIndex::for_tests(vec![(0x3010, 0x1000), (0x3210, 0x1010)]);
        let vtables = vtables(vec![
            type_vtable("Player", 0x3000, 0x2f00),
            type_vtable("Player", 0x3200, 0x2f00),
        ]);

        let (classes, _truncated) = collect_class_slots(
            &map,
            &image,
            &vtables,
            &relocations,
            None,
            4096,
            &crate::scanner::ScanControl::new(),
        )
        .expect("slots");

        assert_eq!(classes.len(), 1);
        let groups = &classes[0].record.vtable_groups;
        assert_eq!(groups.len(), 2);
        assert_eq!(groups[0].slots.len(), 1);
        assert_eq!(groups[1].slots.len(), 1);
        assert_eq!(groups[1].slots[0].address, 0x1010);
    }

    #[test]
    fn a_slot_run_stops_before_the_next_vtable_header() {
        let map = data_map();
        let image = vec![0u8; 0x4000];
        // Every slot between 0x3010 and 0x30f8 has a relocation, but 0x3080 is
        // the header of another class and must terminate the first run.
        let mut relocations = Vec::new();
        for slot in 0..16 {
            relocations.push((0x3010 + slot * 8, 0x1000 + slot * 4));
        }
        let relocations = crate::rtti::RelocationIndex::for_tests(relocations);
        let vtables = vtables(vec![
            type_vtable("First", 0x3000, 0x2f00),
            type_vtable("Second", 0x3080, 0x2f10),
        ]);

        let (classes, _truncated) = collect_class_slots(
            &map,
            &image,
            &vtables,
            &relocations,
            None,
            4096,
            &crate::scanner::ScanControl::new(),
        )
        .expect("slots");

        let first = classes
            .iter()
            .find(|class| class.record.name == "First")
            .expect("First");
        // 0x3010 .. 0x3078 is 14 slots; 0x3080 is the next header.
        assert_eq!(first.record.slots.len(), 14);
    }

    /// A row whose address has no bytes in the file must not claim offset 0:
    /// `.bss` slots are zero-initialised memory, and `0x0` is the ELF header.
    #[test]
    fn a_row_without_file_bytes_omits_the_file_offset() {
        let mut record = sample_function();
        record.file_offset = None;
        let json = serde_json::to_string(&record).expect("json");
        assert!(
            !json.contains("fileOffset"),
            "a missing file offset must not be written: {json}"
        );

        record.file_offset = Some(0x1234);
        let json = serde_json::to_string(&record).expect("json");
        assert!(json.contains("\"fileOffset\":\"0x1234\""), "{json}");
    }

    fn sample_function() -> FunctionRecord {
        FunctionRecord {
            address: 0x4000,
            file_offset: Some(0x4000),
            size: 4,
            name: "Actor::vfunc_0".to_string(),
            name_source: "vtable".to_string(),
            confidence: None,
            aliases: Vec::new(),
            kind: "function".to_string(),
            status: "verified".to_string(),
            prologue_kind: "leaf".to_string(),
            mangled: None,
            target: None,
            suspect_reason: None,
            signature: None,
            signature_unique: None,
            signature_hex: None,
            signature_mask: None,
            signature_stability: None,
            signature_anchor: None,
            identical_group: None,
            identical_group_size: None,
        }
    }
}
