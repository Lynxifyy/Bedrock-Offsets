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

use rayon::prelude::*;
use serde::{Deserialize, Serialize};

use crate::error::{CoreError, CoreResult};
use crate::patterns::PatternSet;
use crate::prologue::{PrologueKind, PrologueVerifier};
use crate::rtti::{read_u64, RelocationIndex, VtableMap};
use crate::scanner::{scan_section_releasing_pages, PageReleaser, ProgressSink, ScanControl};
use crate::sections::{SectionMap, RODATA_SECTION, TEXT_SECTION};
use crate::shards::{NdjsonWriter, RecordWriter};
use crate::symbols::{find_symbol_address, DataSymbol, NamedSymbol};

/// Hard sanity limit on the slots read for one class.
pub const MAX_SLOTS_PER_CLASS: usize = 4096;

/// Bytes sampled from a function when auto-generating a signature.
pub const MAX_SIGNATURE_BYTES: usize = 48;

/// Upper bound the signature generator extends a pattern to before giving up
/// on uniqueness.
pub const MAX_SIGNATURE_EXTENDED_BYTES: usize = 128;

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
            max_slots_per_class: MAX_SLOTS_PER_CLASS,
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
    /// Completeness counts: functions against FDEs, and every cap.
    #[serde(default)]
    pub coverage: InternalCoverage,
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
    /// File offset of the function.
    #[serde(serialize_with = "hex_u64")]
    pub file_offset: u64,
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
}

/// One RTTI class and its slots.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ClassRecord {
    /// Demangled class name.
    pub name: String,
    /// Mangled name.
    pub mangled: String,
    /// Base classes, most-derived first.
    pub bases: Vec<String>,
    /// Virtual address of the vtable header.
    #[serde(serialize_with = "hex_u64")]
    pub vtable: u64,
    /// Offset of the vtable from the image base.
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
    /// File offset.
    #[serde(serialize_with = "hex_u64")]
    pub file_offset: u64,
    /// Size in bytes.
    pub size: u64,
    /// Best name.
    pub name: String,
    /// Where the name came from.
    pub name_source: String,
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
/// The records go to [writer], which either streams the NDJSON table or writes
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
    let mut classes = collect_class_slots(
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
    insert_slot_names(&mut names, &classes);

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
    let (signatures, truncated_signatures) = if options.generate_signatures {
        generate_signatures(
            map,
            image,
            &names,
            &functions,
            options.max_signatures,
            control,
            sink,
            releaser,
        )?
    } else {
        (HashMap::new(), false)
    };

    check_cancelled(control)?;
    if let Some(releaser) = releaser {
        // The signature pass walked the whole `.text`; drop those pages before
        // the write pass faults them back in one function at a time.
        releaser.release(0, usize::MAX);
    }
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
                    slot.signature = Some(Arc::clone(signature));
                    slot.signature_unique = Some(*unique);
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
        let file_offset = file_offset_of(map, *address).unwrap_or(0);
        if let Some(releaser) = releaser {
            let current = file_offset as usize;
            if current > released_to + RELEASE_WINDOW {
                let end = current.saturating_sub(RELEASE_WINDOW / 2);
                releaser.release(released_to, end);
                released_to = end;
            }
        }
        let (prologue_kind, thunk_target) = classify_function(map, image, *address, &verifier);
        let from_fde = fde_sizes.contains(*address);

        let (name, source, aliases, kind, mangled, status) = match entry {
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
                    entry.aliases,
                    entry.kind,
                    entry.mangled,
                    status,
                )
            }
            None => (
                format!("sub_{:x}", address.saturating_sub(map.image_base)),
                "auto",
                Vec::new(),
                "function",
                None,
                if from_fde { "fde_only" } else { "suspect" },
            ),
        };
        let status = if suspect.contains(address) {
            "suspect"
        } else {
            status
        };
        let suspect_reason = if suspect.contains(address) {
            Some("overlapping_fde".to_string())
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

        let (signature, signature_unique) = signatures
            .get(address)
            .map(|(signature, unique)| (Some(signature.clone()), Some(*unique)))
            .unwrap_or((None, None));

        let kind = if thunk_target.is_some() {
            "thunk"
        } else {
            kind
        };
        let record = FunctionRecord {
            address: *address,
            file_offset,
            size,
            name,
            name_source: source.to_string(),
            aliases,
            kind: kind.to_string(),
            status: status.to_string(),
            prologue_kind,
            mangled,
            target: thunk_target,
            signature,
            signature_unique,
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
fn report(sink: Option<&dyn ProgressSink>, percent: u32, message: &str) {
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
) -> CoreResult<Vec<ClassSlots>> {
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

    let mut by_name: BTreeMap<String, Vec<&crate::rtti::TypeVTable>> = BTreeMap::new();
    for vtable in all {
        by_name.entry(vtable.name.clone()).or_default().push(vtable);
    }

    let mut classes = Vec::with_capacity(by_name.len());
    for (name, mut groups) in by_name {
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
            for slot in 0..limit {
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
                        file_offset: file_offset_of(map, *address).unwrap_or(0),
                        size: 0,
                        name: slot_name,
                        kind: kind.to_string(),
                        prologue_kind: "none".to_string(),
                        overrides: false,
                        inherited: false,
                        status: "pending".to_string(),
                        signature: None,
                        signature_unique: None,
                    }
                    .into()
                })
                .collect();

            let offset_to_top = read_at(map, image, vtable.vtable_address).unwrap_or(0) as i64;
            group_meta.push((offset_to_top, vtable.vtable_address));
            group_addresses.push(addresses);
            group_slots.push(slots);
        }

        let primary = &group_slots[0];
        let record = ClassRecord {
            name: name.clone(),
            mangled: groups[0].mangled.clone(),
            bases: Vec::new(),
            vtable: groups[0].vtable_address,
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
    Ok(classes)
}
fn resolve_bases(
    map: &SectionMap,
    image: &[u8],
    relocation: &RelocationIndex,
    vtables: &VtableMap,
) -> HashMap<String, Vec<String>> {
    let type_info_to_name: HashMap<u64, String> = vtables
        .entries
        .values()
        .map(|vtable| (vtable.type_info_address, vtable.name.clone()))
        .collect();
    let mut bases = HashMap::new();
    for vtable in vtables.entries.values() {
        let mut found = Vec::new();
        // Single-inheritance (`__si_class_type_info`) has one base pointer at
        // type_info + 16.
        if let Some(pointer) = read_pointer(map, image, relocation, vtable.type_info_address + 16) {
            if let Some(name) = type_info_to_name.get(&pointer) {
                if name != &vtable.name {
                    found.push(name.clone());
                }
            }
        }
        // Virtual/multiple inheritance (`__vmi_class_type_info`) stores a count
        // and then 16-byte (base, offset/flags) pairs.
        if found.is_empty() {
            if let Some(count_raw) = read_at(map, image, vtable.type_info_address + 20) {
                let count = (count_raw & 0xffff_ffff) as usize;
                if count > 0 && count < 256 {
                    for index in 0..count {
                        let at = vtable.type_info_address + 24 + (index as u64) * 16;
                        if let Some(pointer) = read_pointer(map, image, relocation, at) {
                            if let Some(name) = type_info_to_name.get(&pointer) {
                                if name != &vtable.name && !found.contains(name) {
                                    found.push(name.clone());
                                }
                            }
                        }
                    }
                }
            }
        }
        bases.insert(vtable.name.clone(), found);
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
fn apply_inheritance(classes: &mut [ClassSlots], bases: &HashMap<String, Vec<String>>) {
    let by_name: HashMap<String, Vec<u64>> = classes
        .iter()
        .map(|class| (class.record.name.clone(), class.addresses.clone()))
        .collect();
    let slot_names: HashMap<String, Vec<String>> = classes
        .iter()
        .map(|class| {
            (
                class.record.name.clone(),
                class
                    .record
                    .slots
                    .iter()
                    .map(|slot| slot.name.clone())
                    .collect(),
            )
        })
        .collect();

    for class in classes.iter_mut() {
        let base_names = bases.get(&class.record.name).cloned().unwrap_or_default();
        class.record.bases = base_names.clone();
        let base_slots: Vec<&Vec<u64>> = base_names
            .iter()
            .filter_map(|name| by_name.get(name))
            .collect();
        let base_slot_names: Vec<Option<&Vec<String>>> =
            base_names.iter().map(|name| slot_names.get(name)).collect();
        if base_slots.is_empty() {
            continue;
        }

        for group in class.record.vtable_groups.iter_mut() {
            for (index, slot) in group.slots.iter_mut().enumerate() {
                let slot = Arc::make_mut(slot);
                let mut has_base = false;
                let mut inherited_name: Option<&String> = None;
                for (base_index, slots) in base_slots.iter().enumerate() {
                    let Some(address) = slots.get(index) else {
                        continue;
                    };
                    has_base = true;
                    if *address == slot.address {
                        inherited_name = base_slot_names
                            .get(base_index)
                            .copied()
                            .flatten()
                            .and_then(|names| names.get(index));
                        break;
                    }
                }
                if !has_base {
                    continue;
                }
                slot.inherited = inherited_name.is_some();
                slot.overrides = inherited_name.is_none();
                if let Some(name) = inherited_name {
                    if !is_placeholder_slot_name(name) {
                        slot.name = name.clone();
                    }
                }
            }
        }
        if let Some(primary) = class.record.vtable_groups.first() {
            class.record.slots = primary.slots.clone();
        }
    }
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
        );
    }
}

/// Inserts every vtable slot name into the table.
fn insert_slot_names(names: &mut HashMap<u64, NamedEntry>, classes: &[ClassSlots]) {
    for class in classes {
        for group in &class.record.vtable_groups {
            for slot in &group.slots {
                if slot.address == 0 {
                    // An unresolved import has no address to name.
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
                );
            }
        }
    }
}

/// Adds a name, keeping the highest-priority one and recording the rest.
fn insert_name(
    names: &mut HashMap<u64, NamedEntry>,
    address: u64,
    name: String,
    source: &'static str,
    kind: &'static str,
    mangled: Option<String>,
    status: Option<&'static str>,
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
        "symbol" => 1,
        "jni" => 2,
        "vtable" => 3,
        "signature" => 4,
        "string" => 5,
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
                    );
                }
            }
        }
    }
}

/// Finds every `ADRP`+`ADD`/`LDR` pair that materialises one of `occurrences`.
fn find_string_xrefs(
    map: &SectionMap,
    image: &[u8],
    occurrences: &BTreeSet<u64>,
    out: &mut BTreeSet<u64>,
    releaser: Option<&dyn PageReleaser>,
) {
    const RELEASE_EVERY: usize = 4 * 1024 * 1024;
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
        if word & 0x9F00_0000 == 0x9000_0000 {
            let destination = (word & 31) as usize;
            adrp[destination] = Some((pc, adrp_page(pc, word)));
        } else if word & 0xFF80_0000 == 0x9100_0000 {
            let source = ((word >> 5) & 31) as usize;
            let destination = (word & 31) as usize;
            if let Some((adrp_pc, page)) = adrp[source] {
                if pc.saturating_sub(adrp_pc) <= 16 {
                    let immediate = ((word >> 10) & 0xFFF) as u64;
                    let shift = ((word >> 22) & 0x3) as u64;
                    let target = page.wrapping_add(immediate << (12 * shift));
                    if occurrences.contains(&target) {
                        out.insert(pc);
                    }
                    adrp[destination] = Some((pc, page));
                }
            }
        } else if word & 0xFFC0_0000 == 0xF940_0000 {
            let source = ((word >> 5) & 31) as usize;
            if let Some((adrp_pc, page)) = adrp[source] {
                if pc.saturating_sub(adrp_pc) <= 16 {
                    let immediate = ((word >> 10) & 0xFFF) as u64;
                    let target = page.wrapping_add(immediate * 8);
                    if occurrences.contains(&target) {
                        out.insert(pc);
                    }
                }
            }
        }
    }
}

/// Generates a wildcarded signature per named function and checks uniqueness.
type SignatureMap = HashMap<u64, (Arc<str>, bool)>;

/// Builds and extends a signature for every named function and every slot.
///
/// A signature starts at 48 bytes. When it is not unique among function starts
/// the pattern is extended by 16 bytes at a time, up to 128, and re-checked
/// against the real matcher. A pattern that is still ambiguous at 128 bytes is
/// kept with `signatureUnique: false` rather than dropped, so a client can see
/// what the engine managed.
#[allow(clippy::too_many_arguments)]
fn generate_signatures(
    map: &SectionMap,
    image: &[u8],
    names: &HashMap<u64, NamedEntry>,
    functions: &[(u64, u64)],
    max_signatures: usize,
    control: &ScanControl,
    sink: Option<&dyn ProgressSink>,
    releaser: Option<&dyn PageReleaser>,
) -> CoreResult<(SignatureMap, bool)> {
    let text = map.bytes_of(TEXT_SECTION, image);
    if text.is_empty() {
        return Ok((HashMap::new(), false));
    }

    let mut addresses: Vec<u64> = names
        .iter()
        .filter(|(address, entry)| entry.source != "auto" && **address != 0)
        .map(|(address, _)| *address)
        .collect();
    addresses.sort_unstable();
    addresses.dedup();
    let limit = max_signatures.max(1);
    let truncated = addresses.len() > limit;
    addresses.truncate(limit);

    let mut candidates: Vec<(u64, String)> = Vec::with_capacity(addresses.len());
    for address in &addresses {
        if control.is_cancelled() {
            return Err(CoreError::Cancelled);
        }
        let Some(file_offset) = file_offset_of(map, *address) else {
            continue;
        };
        let Some(pattern) = wildcard_signature(map, image, file_offset, MAX_SIGNATURE_BYTES) else {
            continue;
        };
        let Ok(compiled) = crate::patterns::compile(&address.to_string(), &pattern) else {
            continue;
        };
        // A signature whose longest fixed run is tiny matches thousands of
        // functions; extending it would not converge.
        if compiled.longest_literal_run() < MIN_SIGNATURE_RUN {
            continue;
        }
        candidates.push((*address, pattern));
    }
    if candidates.is_empty() {
        return Ok((HashMap::new(), truncated));
    }

    // Most signatures are already unique among function starts after eight
    // bytes, and proving that needs one sorted list of start prefixes instead of
    // a matcher run over the whole buffer. Only the rest go through the rounds.
    let prefixes = StartPrefixes::new(map, image, functions);
    let mut unique = vec![false; candidates.len()];
    let mut remaining = 0usize;
    for (index, (_, pattern)) in candidates.iter().enumerate() {
        match fixed_prefix_word(pattern) {
            Some(word) if prefixes.count(word) == 1 => unique[index] = true,
            _ => remaining += 1,
        }
    }
    report(
        sink,
        80,
        &format!(
            "Signatures: {} resolved by prefix, {remaining} need scanning",
            candidates.len() - remaining
        ),
    );

    let mut lengths = vec![MAX_SIGNATURE_BYTES];
    let mut next = MAX_SIGNATURE_BYTES;
    while next < MAX_SIGNATURE_EXTENDED_BYTES {
        next = (next + 16).min(MAX_SIGNATURE_EXTENDED_BYTES);
        lengths.push(next);
    }

    for (round, length) in lengths.iter().enumerate() {
        if control.is_cancelled() {
            return Err(CoreError::Cancelled);
        }
        if *length > MAX_SIGNATURE_BYTES {
            for (index, (address, pattern)) in candidates.iter_mut().enumerate() {
                if unique[index] {
                    continue;
                }
                let Some(file_offset) = file_offset_of(map, *address) else {
                    continue;
                };
                if let Some(extended) = wildcard_signature(map, image, file_offset, *length) {
                    *pattern = extended;
                }
            }
        }

        let active: Vec<(usize, String)> = candidates
            .iter()
            .enumerate()
            .filter(|(index, _)| !unique[*index])
            .map(|(index, (_, pattern))| (index, pattern.clone()))
            .collect();
        if active.is_empty() {
            break;
        }
        let percent = 80 + (round as u32).min(8) * 2;
        report(
            sink,
            percent,
            &format!(
                "Signatures: round {} ({} bytes, {} candidates)",
                round + 1,
                length,
                active.len()
            ),
        );
        let counts = count_at_function_starts(map, image, functions, &active, control, releaser)?;
        for (index, count) in counts.iter().enumerate() {
            if *count == 1 {
                unique[active[index].0] = true;
            }
        }
    }

    let mut signatures = HashMap::with_capacity(candidates.len());
    for (index, (address, pattern)) in candidates.into_iter().enumerate() {
        signatures.insert(address, (Arc::from(pattern.as_str()), unique[index]));
    }
    Ok((signatures, truncated))
}

/// Counts how many function starts each pattern matches, capped at two.
///
/// The scan packs one prefix per function start and runs the real matcher over
/// that buffer, so a count is the number of distinct functions a signature
/// would select. The work is split across Rayon workers in waves, and each wave
/// is offered for page release afterwards.
fn count_at_function_starts(
    map: &SectionMap,
    image: &[u8],
    functions: &[(u64, u64)],
    active: &[(usize, String)],
    control: &ScanControl,
    releaser: Option<&dyn PageReleaser>,
) -> CoreResult<Vec<u8>> {
    let text = map.bytes_of(TEXT_SECTION, image);

    const STRIDE: usize = 64;
    const PREFIX: usize = MAX_SIGNATURE_EXTENDED_BYTES;
    const CHUNK: usize = 4096;
    const WAVE: usize = 64;
    // One matcher for every candidate needs about a kilobyte of automaton per
    // pattern, which is gigabytes at a million candidates. Each group is
    // matched separately instead; the buffer is rescanned per group, which
    // costs seconds and keeps the peak flat.
    const PATTERN_GROUP: usize = 8192;

    let chunks: Vec<&[(u64, u64)]> = functions.chunks(CHUNK).collect();
    let mut counts = vec![0u8; active.len()];
    let group_count = active.len().div_ceil(PATTERN_GROUP);
    for (group_index, group) in active.chunks(PATTERN_GROUP).enumerate() {
        let specs: Vec<(String, String)> = group
            .iter()
            .map(|(index, pattern)| (index.to_string(), pattern.clone()))
            .collect();
        let set = PatternSet::new(&specs)?;
        let base = group_index * PATTERN_GROUP;
        let last_group = group_index + 1 == group_count;
        for wave in chunks.chunks(WAVE) {
            if control.is_cancelled() {
                return Err(CoreError::Cancelled);
            }
            let local: Vec<Vec<u8>> = wave
                .par_iter()
                .map(|chunk| {
                    let mut buffer: Vec<u8> = Vec::with_capacity(CHUNK * STRIDE);
                    for (address, _) in chunk.iter() {
                        let offset = address.saturating_sub(map.text.address) as usize;
                        let end = (offset + PREFIX).min(text.len());
                        if offset < end {
                            buffer.extend_from_slice(&text[offset..end]);
                        }
                        buffer.resize(buffer.len().next_multiple_of(STRIDE), 0);
                    }
                    let mut chunk_counts = vec![0u8; group.len()];
                    set.count_matches_at(&buffer, STRIDE, &mut chunk_counts);
                    chunk_counts
                })
                .collect();
            for chunk_counts in local {
                for (index, count) in chunk_counts.iter().enumerate() {
                    if *count > counts[base + index] {
                        counts[base + index] = *count;
                    }
                }
            }
            if let Some(releaser) = releaser.filter(|_| last_group) {
                if let (Some((first, _)), Some((last, _))) = (
                    wave.first().and_then(|chunk| chunk.first()),
                    wave.last().and_then(|chunk| chunk.last()),
                ) {
                    let start = first.saturating_sub(map.text.address) as usize;
                    let end = last
                        .saturating_sub(map.text.address)
                        .saturating_add(PREFIX as u64) as usize;
                    releaser.release(
                        map.text.offset as usize + start,
                        map.text.offset as usize + end,
                    );
                }
            }
        }
    }
    Ok(counts)
}

/// Peak resident set size of the process, in MiB, when the OS exposes it.
fn peak_resident_mb() -> Option<u64> {
    let status = std::fs::read_to_string("/proc/self/status").ok()?;
    let line = status.lines().find(|line| line.starts_with("VmHWM:"))?;
    let kilobytes: u64 = line.split_whitespace().nth(1)?.parse().ok()?;
    Some(kilobytes / 1024)
}

/// First eight bytes at every function start, sorted.
///
/// A signature that matches at a function start necessarily matches that
/// start's first eight bytes, so a prefix that occurs once proves the whole
/// signature unique without touching `.text`.
struct StartPrefixes(Vec<u64>);

impl StartPrefixes {
    fn new(map: &SectionMap, image: &[u8], functions: &[(u64, u64)]) -> Self {
        let text = map.bytes_of(TEXT_SECTION, image);
        let mut prefixes = Vec::with_capacity(functions.len());
        for (address, _) in functions {
            let offset = address.saturating_sub(map.text.address) as usize;
            if offset + 8 <= text.len() {
                let mut word = [0u8; 8];
                word.copy_from_slice(&text[offset..offset + 8]);
                prefixes.push(u64::from_le_bytes(word));
            }
        }
        prefixes.sort_unstable();
        Self(prefixes)
    }

    /// How many starts begin with [word], capped at two.
    fn count(&self, word: u64) -> u8 {
        let lower = self.0.partition_point(|probe| *probe < word);
        let upper = self.0.partition_point(|probe| *probe <= word);
        (upper - lower).min(2) as u8
    }
}

/// The first eight bytes of a hex signature when all of them are fixed.
///
/// @returns the bytes as a little-endian word, or `None` when the signature
///   starts with a wildcard or is shorter than eight bytes.
fn fixed_prefix_word(pattern: &str) -> Option<u64> {
    let mut word = 0u64;
    let mut count = 0usize;
    for token in pattern.split_whitespace().take(8) {
        let value = u8::from_str_radix(token, 16).ok()?;
        word |= (value as u64) << (count * 8);
        count += 1;
    }
    (count == 8).then_some(word)
}

/// Shortest fixed run a generated signature must contain.
const MIN_SIGNATURE_RUN: usize = 10;

/// Builds a wildcarded hex signature from the first bytes of a function.
fn wildcard_signature(
    map: &SectionMap,
    image: &[u8],
    file_offset: u64,
    length: usize,
) -> Option<String> {
    let text_offset = (file_offset.saturating_sub(map.text.offset)) as usize;
    let text = map.bytes_of(TEXT_SECTION, image);
    let available = text.len().saturating_sub(text_offset);
    if available < 4 {
        return None;
    }
    let length = available.min(length).max(4);
    let bytes = &text[text_offset..text_offset + length];
    let mut tokens = Vec::with_capacity(length);
    let mut index = 0usize;
    let mut wildcards = 0usize;
    while index + 4 <= bytes.len() {
        let word = u32::from_le_bytes([
            bytes[index],
            bytes[index + 1],
            bytes[index + 2],
            bytes[index + 3],
        ]);
        let operand_bytes = is_relocatable_operand(word);
        for byte in 0..4 {
            if operand_bytes && byte > 0 {
                tokens.push("??".to_string());
                wildcards += 1;
            } else {
                tokens.push(format!("{:02X}", bytes[index + byte]));
            }
        }
        index += 4;
    }
    for byte in bytes.iter().skip(index) {
        tokens.push(format!("{byte:02X}"));
    }
    if wildcards == tokens.len() {
        return None;
    }
    Some(tokens.join(" "))
}

/// Whether an instruction carries a relocatable immediate in bytes 1..4.
fn is_relocatable_operand(word: u32) -> bool {
    // ADRP, ADR, B, BL, LDR literal, ADD/SUB immediate.
    word & 0x9F00_0000 == 0x9000_0000
        || word & 0x9F00_0000 == 0x1000_0000
        || word & 0x7C00_0000 == 0x1400_0000
        || word & 0xFF00_0000 == 0x5800_0000
        || word & 0xFF80_0000 == 0x9100_0000
        || word & 0xFF80_0000 == 0xD100_0000
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
fn file_offset_of(map: &SectionMap, address: u64) -> Option<u64> {
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

/// Reads an encoded DWARF pointer.
fn read_encoded(
    bytes: &[u8],
    position: &mut usize,
    encoding: u8,
    section_address: u64,
    datarel_base: u64,
) -> Option<u64> {
    if encoding == 0 {
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
fn skip_encoded(
    bytes: &[u8],
    position: &mut usize,
    encoding: u8,
    section_address: u64,
    datarel_base: u64,
) -> Option<()> {
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
fn collect_functions(
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
                return Ok((functions, "eh_frame", fde_functions));
            }
        }
    }
    let functions = scan_prologues(map, image, control)?;
    Ok((functions, "prologue_scan", 0))
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
fn is_probable_prologue(word: u32) -> bool {
    word == 0xD503_237F
        || word == 0xD503_233F
        || word & 0xFFC0_7FFF == 0xA980_7BFD
        || word & 0xFFC0_03FF == 0xD100_03FF
        || word & 0xFFFF_FC1F == 0xD503_241F
        || word == 0x9100_03FD
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
    fn relocatable_operands_are_recognised() {
        assert!(is_relocatable_operand(0x9000_0010));
        assert!(is_relocatable_operand(0x1400_0010));
        assert!(is_relocatable_operand(0x9400_0010));
        assert!(!is_relocatable_operand(0xD503_201F));
    }

    #[test]
    fn name_priority_prefers_symbols_over_slots() {
        assert!(rank("symbol") < rank("vtable"));
        assert!(rank("vtable") < rank("signature"));
        assert!(rank("signature") < rank("auto"));
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
        );
        insert_name(
            &mut names,
            0x1000,
            "update".to_string(),
            "symbol",
            "function",
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

    #[test]
    fn a_wildcarded_signature_keeps_opcode_bytes_and_masks_operands() {
        let map = map_with(section(".text", 0x1000, 0x1000, 0x100, true), Vec::new());
        let mut image = vec![0u8; 0x2000];
        // stp x29, x30, [sp, #-16]! then a BL.
        image[0x1000..0x1004].copy_from_slice(&0xA9BF_7BFDu32.to_le_bytes());
        image[0x1004..0x1008].copy_from_slice(&0x9400_0010u32.to_le_bytes());

        let signature =
            wildcard_signature(&map, &image, 0x1000, MAX_SIGNATURE_BYTES).expect("signature");

        assert!(signature.starts_with("FD 7B BF A9"), "{signature}");
        assert!(signature.contains("??"), "{signature}");
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
        crate::rtti::TypeVTable {
            name: name.to_string(),
            mangled: name.to_string(),
            vtable_address: type_info + 0x100,
            vtable_hex: format!("0x{:x}", type_info + 0x100),
            vtable_offset: type_info + 0x100,
            type_info_address: type_info,
        }
    }

    fn vtables(entries: Vec<crate::rtti::TypeVTable>) -> crate::rtti::VtableMap {
        let all = entries.clone();
        crate::rtti::VtableMap {
            source: crate::rtti::VtableSource::Relocations,
            type_info_candidates: entries.len(),
            relocations_examined: 0,
            entries: entries.into_iter().map(|v| (v.name.clone(), v)).collect(),
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
        let map_vtables = vtables(vec![vtable("Base", 0x3000), vtable("Derived", 0x3080)]);
        let relocations = crate::rtti::RelocationIndex::for_tests(Vec::new());

        let bases = resolve_bases(&map, &image, &relocations, &map_vtables);

        assert_eq!(bases.get("Derived"), Some(&vec!["Base".to_string()]));
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
        let map_vtables = vtables(vec![vtable("Base", 0x3000), vtable("Derived", 0x3080)]);
        let relocations = crate::rtti::RelocationIndex::for_tests(Vec::new());

        let bases = resolve_bases(&map, &image, &relocations, &map_vtables);

        assert_eq!(bases.get("Derived"), Some(&vec!["Base".to_string()]));
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
                    file_offset: *address,
                    size: 4,
                    name: format!("{name}::vfunc_{index}"),
                    kind: "virtual".to_string(),
                    prologue_kind: "none".to_string(),
                    overrides: false,
                    inherited: false,
                    status: "verified".to_string(),
                    signature: None,
                    signature_unique: None,
                })
            })
            .collect();
        ClassSlots {
            record: ClassRecord {
                name: name.to_string(),
                mangled: name.to_string(),
                bases: Vec::new(),
                vtable: 0,
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
    fn inheritance_marks_identical_slots_inherited_and_different_ones_overridden() {
        let mut classes = vec![
            class("Base", vec![0x1000, 0x2000]),
            class("Derived", vec![0x1000, 0x3000]),
        ];
        let mut bases = HashMap::new();
        bases.insert("Derived".to_string(), vec!["Base".to_string()]);

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
            mangled: name.to_string(),
            vtable_address,
            vtable_hex: format!("0x{vtable_address:x}"),
            vtable_offset: vtable_address,
            type_info_address: type_info,
        }
    }

    fn data_map() -> SectionMap {
        map_with(
            section(".text", 0x1000, 0x1000, 0x100, true),
            vec![section(".data.rel.ro", 0x3000, 0x3000, 0x400, false)],
        )
    }

    #[test]
    fn fixed_prefix_word_requires_eight_literal_bytes() {
        assert_eq!(
            fixed_prefix_word("FD 7B BF A9 FD 03 00 91 FF 43"),
            Some(0x9100_03fd_a9bf_7bfd)
        );
        // A wildcard in the first eight bytes makes the shortcut unusable.
        assert_eq!(fixed_prefix_word("FD 7B BF A9 ?? ?? ?? ??"), None);
        assert_eq!(fixed_prefix_word("FD 7B BF A9"), None);
    }

    #[test]
    fn a_unique_start_prefix_proves_a_signature_unique() {
        let mut image = vec![0u8; 0x2000];
        // Two functions in `.text`: 0x1000 and 0x1010, distinct prefixes.
        image[0x1000..0x1008].copy_from_slice(&[0xFD, 0x7B, 0xBF, 0xA9, 1, 2, 3, 4]);
        image[0x1010..0x1018].copy_from_slice(&[0xFD, 0x7B, 0xBF, 0xA9, 9, 9, 9, 9]);
        let map = map_with(section(".text", 0x1000, 0x1000, 0x100, true), vec![]);
        let functions = vec![(0x1000u64, 0x10u64), (0x1010, 0x10)];

        let prefixes = StartPrefixes::new(&map, &image, &functions);

        assert_eq!(
            prefixes.count(fixed_prefix_word("FD 7B BF A9 01 02 03 04").unwrap()),
            1
        );
        assert_eq!(
            prefixes.count(fixed_prefix_word("FD 7B BF A9 09 09 09 09").unwrap()),
            1
        );
        // The first four bytes alone are shared, so an eight-byte word built
        // from them plus equal tails counts both starts.
        assert_eq!(
            prefixes.count(fixed_prefix_word("FD 7B BF A9 01 02 03 04").unwrap()),
            1
        );
        let shared = u64::from_le_bytes([0xFD, 0x7B, 0xBF, 0xA9, 0, 0, 0, 0]);
        assert_eq!(prefixes.count(shared), 0);
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

        let classes = collect_class_slots(
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

        let classes = collect_class_slots(
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

        let classes = collect_class_slots(
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

        let classes = collect_class_slots(
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
}
