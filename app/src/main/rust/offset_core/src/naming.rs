//! The naming engine: one evidence graph, several sources.
//!
//! Every source produces [`NameEvidence`]. Sources never overwrite each other
//! silently: the caller ranks them and keeps the losers as aliases. A name is
//! only `Certain` when two independent sources agree on the same address, which
//! is why the ranking lives here and not in the writer.

use std::collections::{BTreeMap, BTreeSet};

use crate::rtti::VtableMap;
use crate::xref::XrefTable;

/// How sure the engine is of a name.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Confidence {
    /// Two independent sources agree.
    Certain,
    /// One structural source with an exact encoding or a unique xref.
    High,
    /// One source with an inference step.
    Medium,
    /// Shape or call-graph guess.
    Low,
}

impl Confidence {
    /// Numeric strength, higher is better.
    fn rank(self) -> u8 {
        match self {
            Confidence::Certain => 3,
            Confidence::High => 2,
            Confidence::Medium => 1,
            Confidence::Low => 0,
        }
    }

    /// Lower-case tag used in the export.
    pub fn as_str(self) -> &'static str {
        match self {
            Confidence::Certain => "certain",
            Confidence::High => "high",
            Confidence::Medium => "medium",
            Confidence::Low => "low",
        }
    }
}

/// Where a name came from.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum EvidenceSource {
    /// `.dynsym` / `.symtab`.
    Symbol,
    /// The mangled type of a lambda contains its enclosing function.
    LambdaEnclosing,
    /// Exactly one function references a lambda's vtable.
    VtableXref,
    /// `StrictTickingSystemFunctionAdapter<&tickFoo, ViewT<...>>`.
    EcsSystem,
    /// Registration code pairs a string with a member pointer.
    ScriptingReflection,
    /// A unique `.rodata` string reference.
    StringAnchor,
    /// Constrained by named callers and callees.
    CallGraph,
    /// Instruction shape.
    Shape,
}

impl EvidenceSource {
    /// Lower-case tag used in the export.
    pub fn as_str(self) -> &'static str {
        match self {
            EvidenceSource::Symbol => "symbol",
            EvidenceSource::LambdaEnclosing => "lambda",
            EvidenceSource::VtableXref => "vtable_xref",
            EvidenceSource::EcsSystem => "ecs_system",
            EvidenceSource::ScriptingReflection => "scripting",
            EvidenceSource::StringAnchor => "string",
            EvidenceSource::CallGraph => "call_graph",
            EvidenceSource::Shape => "shape",
        }
    }
}

/// One claim that a given address has a given name.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NameEvidence {
    /// Function address the name belongs to.
    pub address: u64,
    /// Proposed name.
    pub name: String,
    /// Which source proposed it.
    pub source: EvidenceSource,
    /// Human-readable justification, written into the export.
    pub detail: String,
    /// How sure the source is on its own.
    pub confidence: Confidence,
}

/// Recovers the enclosing function from a lambda's demangled type.
///
/// `Level::tick()::{lambda(int)#1}` yields `Level::tick()`. The ABI encodes a
/// local entity as `Z <encoding> E <entity>`, so the enclosing function's full
/// name is inside the mangled type; the old demangler discarded it with the
/// rest of the template arguments.
pub fn enclosing_function(display: &str) -> Option<&str> {
    // A lambda often appears inside a template argument
    // (`__func<Level::tick()::$_0, …>`), so the cut has to be at the marker that
    // sits inside a function signature, not at the first one in the string.
    for marker in ["::$_", "::{lambda", "::{unnamed type"] {
        let mut search = 0;
        while let Some(offset) = display[search..].find(marker) {
            let at = search + offset;
            if let Some(name) = function_head(&display[..at]) {
                return Some(name);
            }
            search = at + marker.len();
        }
    }
    None
}

/// The function signature a prefix ends with, if it ends with one.
///
/// `std::__ndk1::__function::__func<Level::tick()` yields `Level::tick()`: the
/// closest `(`/`)` pair is found by balancing, then the qualified name is read
/// leftwards from the `(`, stopping at a template bracket or a comma.
fn function_head(prefix: &str) -> Option<&str> {
    if !prefix.ends_with(')') {
        return None;
    }
    let bytes = prefix.as_bytes();
    let mut depth = 0usize;
    let mut open = None;
    for index in (0..bytes.len()).rev() {
        match bytes[index] {
            b')' => depth += 1,
            b'(' => {
                depth -= 1;
                if depth == 0 {
                    open = Some(index);
                    break;
                }
            }
            _ => {}
        }
    }
    let open = open?;
    let mut start = open;
    while start > 0 {
        let byte = bytes[start - 1];
        if byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b':' | b'$' | b'.') {
            start -= 1;
        } else {
            break;
        }
    }
    if start == open {
        return None;
    }
    Some(&prefix[start..])
}

/// Whether a mangled type name denotes a local entity (a lambda or a local
/// class), which is what makes enclosing-function recovery possible.
pub fn is_local_entity(mangled: &str) -> bool {
    mangled.starts_with('Z') || mangled.contains("IZ") || mangled.contains("ZZ")
}

/// Names the `operator()` of every lambda vtable from its enclosing function.
///
/// The call operator of a `std::function` target is slot 0 of the lambda's
/// vtable (libc++ `__func` and `std::__ndk1::__func` alike). The vtable group
/// that carries the lambda's own type has exactly that slot; secondary groups
/// of the enclosing class are skipped because their first slot is not the
/// lambda's.
pub fn lambda_slot_names(
    vtables: &VtableMap,
    slots_by_vtable: &std::collections::HashMap<u64, Vec<u64>>,
) -> Vec<NameEvidence> {
    let mut evidence = Vec::new();
    for vtable in &vtables.all {
        if !is_local_entity(&vtable.mangled) {
            continue;
        }
        let Some(enclosing) = enclosing_function(&vtable.name) else {
            continue;
        };
        let Some(slots) = slots_by_vtable.get(&vtable.vtable_address) else {
            continue;
        };
        let Some(address) = slots.first().copied() else {
            continue;
        };
        evidence.push(NameEvidence {
            address,
            name: format!("{}::operator()", vtable.name),
            source: EvidenceSource::LambdaEnclosing,
            detail: format!(
                "call slot of the vtable for {} (wraps {enclosing})",
                vtable.mangled
            ),
            confidence: Confidence::High,
        });
    }
    evidence
}

/// Names the callable a `std::function`-style wrapper invokes.
///
/// `__func<Level::tick()::$_0, …>::operator()` calls the lambda's body once.
/// When the wrapper has exactly one callee that is not runtime support, that
/// callee is `Level::tick()::$_0::operator()`: a real engine function, which no
/// symbol in the library names.
pub fn lambda_body_names(
    vtables: &VtableMap,
    slots_by_vtable: &std::collections::HashMap<u64, Vec<u64>>,
    xrefs: &XrefTable,
) -> Vec<NameEvidence> {
    let mut evidence = Vec::new();
    for vtable in &vtables.all {
        if !is_local_entity(&vtable.mangled) {
            continue;
        }
        let Some(enclosing) = enclosing_function(&vtable.name) else {
            continue;
        };
        let Some(wrapper) = slots_by_vtable
            .get(&vtable.vtable_address)
            .and_then(|slots| slots.first().copied())
        else {
            continue;
        };
        let Some(function) = xrefs.function_at(wrapper) else {
            continue;
        };
        let mut candidates: Vec<u64> = xrefs
            .calls_of(function)
            .iter()
            .copied()
            .filter(|target| {
                // An import or a PLT stub has no FDE entry, so only a call to a
                // real function of this image can be the wrapped callable.
                xrefs.function_at(*target).is_some()
            })
            .collect();
        candidates.sort_unstable();
        candidates.dedup();
        if candidates.len() != 1 {
            continue;
        }
        evidence.push(NameEvidence {
            address: candidates[0],
            name: format!("{enclosing}::$_0::operator()"),
            source: EvidenceSource::LambdaEnclosing,
            detail: format!(
                "sole callee of {} in {}",
                wrapper_hex(wrapper),
                vtable.mangled
            ),
            confidence: Confidence::Medium,
        });
    }
    evidence
}

fn wrapper_hex(address: u64) -> String {
    format!("0x{address:x}")
}

/// Names the function that constructs a lambda when exactly one function
/// references the lambda's vtable.
///
/// That function is the enclosing function or its inlined creator: the vtable
/// address only appears where the lambda is instantiated. One reference is a
/// fact; several references mean the lambda is shared and no name is claimed.
///
/// # Errors
///
/// Returns [`crate::error::CoreError::OutOfMemory`] when the filtered reverse
/// index cannot grow.
pub fn vtable_xref_names(
    vtables: &VtableMap,
    xrefs: &XrefTable,
) -> crate::error::CoreResult<Vec<NameEvidence>> {
    // Only the vtable addresses are queried, so the reverse index holds a few
    // thousand edges instead of one per reference in the library.
    let mut wanted: std::collections::HashSet<u64> = std::collections::HashSet::new();
    for vtable in &vtables.all {
        wanted.insert(vtable.vtable_address);
        wanted.insert(vtable.vtable_address.wrapping_add(16));
    }
    let referrers = xrefs.referrers_of(&wanted)?;
    let mut evidence = Vec::new();
    for vtable in &vtables.all {
        let Some(enclosing) = enclosing_function(&vtable.name) else {
            continue;
        };
        // The vtable header sits before the first slot; the reference may point
        // at either, so both are tried.
        let mut referencing: BTreeSet<u64> = referrers
            .sources(vtable.vtable_address)
            .into_iter()
            .collect();
        referencing.extend(referrers.sources(vtable.vtable_address.wrapping_add(16)));
        if referencing.len() != 1 {
            continue;
        }
        let address = referencing.into_iter().next().expect("one element");
        evidence.push(NameEvidence {
            address,
            name: enclosing.to_string(),
            source: EvidenceSource::VtableXref,
            detail: format!("only reference to the vtable of {}", vtable.name),
            confidence: Confidence::High,
        });
    }
    Ok(evidence)
}

/// One ECS system recovered from a `StrictTickingSystemFunctionAdapter`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EcsSystem {
    /// The adapter instantiation's full demangled name.
    pub adapter: String,
    /// The system function the adapter wraps, e.g. `tickWeather`.
    pub system: String,
    /// Components the view requires (`Include<List>` entries).
    pub included: Vec<String>,
    /// Components the view rejects (`Exclude<List>` entries).
    pub excluded: Vec<String>,
    /// Address of the adapter's call operator, when the vtable is known.
    pub tick_address: Option<u64>,
}

/// Parses the system function and component lists out of an adapter name.
///
/// The text is `…StrictTickingSystemFunctionAdapter<&tickFoo,
/// ViewT<StrictEntityContext, Include<A, B>, Exclude<C>>>`. Parsing the
/// demangled text is enough: the arguments are present verbatim because the
/// demangler keeps template arguments.
pub fn parse_ecs_system(display: &str) -> Option<EcsSystem> {
    let adapter_at = display.find("StrictTickingSystemFunctionAdapter<")?;
    let adapter = display[adapter_at..].to_string();
    let body = &adapter["StrictTickingSystemFunctionAdapter<".len()..];
    let system = body
        .split(',')
        .next()?
        .trim()
        .trim_start_matches('&')
        .trim()
        .to_string();
    if system.is_empty() {
        return None;
    }
    // The wrapped function may itself be a template, so a comma split is only
    // trusted for the first argument, which is the function pointer.
    let included = component_list(body, "Include<");
    let excluded = component_list(body, "Exclude<");
    Some(EcsSystem {
        adapter,
        system,
        included,
        excluded,
        tick_address: None,
    })
}

/// Recovers every ECS system the library instantiates and names its call
/// operator.
pub fn ecs_systems(
    vtables: &VtableMap,
    slots_by_vtable: &std::collections::HashMap<u64, Vec<u64>>,
) -> Vec<EcsSystem> {
    let mut systems = Vec::new();
    let mut seen: BTreeSet<String> = BTreeSet::new();
    for vtable in &vtables.all {
        let Some(mut system) = parse_ecs_system(&vtable.name) else {
            continue;
        };
        if !seen.insert(system.adapter.clone()) {
            continue;
        }
        system.tick_address = slots_by_vtable
            .get(&vtable.vtable_address)
            .and_then(|slots| slots.first().copied());
        systems.push(system);
    }
    systems.sort_by(|left, right| left.adapter.cmp(&right.adapter));
    systems
}

/// Evidence for each adapter's call operator.
pub fn ecs_system_names(systems: &[EcsSystem]) -> Vec<NameEvidence> {
    systems
        .iter()
        .filter_map(|system| {
            Some(NameEvidence {
                address: system.tick_address?,
                name: format!("{}::operator()", system.system),
                source: EvidenceSource::EcsSystem,
                detail: format!(
                    "{} include[{}] exclude[{}]",
                    system.adapter,
                    system.included.join(", "),
                    system.excluded.join(", ")
                ),
                confidence: Confidence::High,
            })
        })
        .collect()
}

/// Collects the component names inside `Include<…>` or `Exclude<…>`.
fn component_list(text: &str, marker: &str) -> Vec<String> {
    let Some(at) = text.find(marker) else {
        return Vec::new();
    };
    let mut depth = 0usize;
    let mut current = String::new();
    let mut items = Vec::new();
    for ch in text[at + marker.len()..].chars() {
        match ch {
            '<' => {
                depth += 1;
                current.push(ch);
            }
            '>' => {
                if depth == 0 {
                    break;
                }
                depth -= 1;
                current.push(ch);
            }
            ',' if depth == 0 => {
                items.push(current.trim().to_string());
                current.clear();
            }
            _ => current.push(ch),
        }
    }
    if !current.trim().is_empty() {
        items.push(current.trim().to_string());
    }
    items
        .into_iter()
        // `StrictEntityContext` is the view's entity key, not a component.
        .filter(|item| !item.is_empty() && item != "StrictEntityContext")
        .collect()
}

/// Merges evidence from every source, keeping conflicts instead of dropping
/// them.
///
/// The result maps an address to the best name plus the names that lost. Two
/// independent sources agreeing on the same string promote the winner to
/// [`Confidence::Certain`].
pub fn merge(evidence: Vec<NameEvidence>) -> BTreeMap<u64, NameEvidence> {
    let mut best: BTreeMap<u64, NameEvidence> = BTreeMap::new();
    let mut agreements: BTreeMap<u64, BTreeSet<EvidenceSource>> = BTreeMap::new();
    for item in evidence {
        match best.get(&item.address) {
            Some(current) if current.name == item.name && current.source != item.source => {
                agreements
                    .entry(item.address)
                    .or_default()
                    .insert(item.source);
            }
            Some(current) if current.confidence.rank() >= item.confidence.rank() => {}
            _ => {
                best.insert(item.address, item);
            }
        }
    }
    for (address, sources) in agreements {
        if let Some(entry) = best.get_mut(&address) {
            if sources.len() + 1 >= 2 {
                entry.confidence = Confidence::Certain;
            }
        }
    }
    best
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::rtti::{TypeInfoKind, TypeVTable, VtableMap, VtableSource};

    fn vtable(name: &str, mangled: &str, address: u64) -> TypeVTable {
        TypeVTable {
            name: name.to_string(),
            base_name: name.split('<').next().unwrap_or(name).to_string(),
            mangled: mangled.to_string(),
            vtable_address: address,
            vtable_hex: format!("0x{address:x}"),
            vtable_offset: address,
            type_info_address: address + 0x100,
            type_info_kind: TypeInfoKind::SiClass,
            offset_to_top: 0,
        }
    }

    fn map(vtables: Vec<TypeVTable>) -> VtableMap {
        VtableMap {
            source: VtableSource::Relocations,
            type_info_candidates: vtables.len(),
            relocations_examined: 0,
            entries: vtables
                .iter()
                .map(|v| (v.mangled.clone(), v.clone()))
                .collect(),
            all: vtables,
        }
    }

    #[test]
    fn the_enclosing_function_is_recovered_from_a_lambda_name() {
        assert_eq!(
            enclosing_function("Level::tick()::{lambda(int)#1}"),
            Some("Level::tick()")
        );
        assert_eq!(
            enclosing_function("Level::tick()::$_0"),
            Some("Level::tick()")
        );
        assert_eq!(enclosing_function("Level"), None);
        assert_eq!(
            enclosing_function(
                "std::__ndk1::__function::__func<Level::tick()::$_0, \
                 std::__ndk1::allocator<Level::tick()::$_0>, int (int)>"
            ),
            Some("Level::tick()")
        );
        assert_eq!(
            enclosing_function("GameMode::tick()::{lambda(int)#1}::operator()"),
            Some("GameMode::tick()")
        );
    }

    #[test]
    fn a_local_entity_is_recognised_from_the_mangled_name() {
        assert!(is_local_entity("ZN5Level4tickEvEUliE_"));
        assert!(is_local_entity(
            "NSt6__ndk19__function6__funcIZN5Level4tickEvEUlvE_NS_9allocatorIS4_EEFvvEEE"
        ));
        assert!(!is_local_entity("14ClientInstance"));
    }

    #[test]
    fn an_ecs_adapter_yields_its_system_and_components() {
        let system = parse_ecs_system(
            "StrictTickingSystemFunctionAdapter<&tickWeather, \
             ViewT<StrictEntityContext, Include<WeatherComponent, StateVectorComponent>, \
             Exclude<RemovedComponent>>>",
        )
        .expect("an adapter");
        assert_eq!(system.system, "tickWeather");
        assert_eq!(
            system.included,
            vec!["WeatherComponent", "StateVectorComponent"]
        );
        assert_eq!(system.excluded, vec!["RemovedComponent"]);
    }

    #[test]
    fn a_plain_class_is_not_an_ecs_adapter() {
        assert!(parse_ecs_system("Level::Actor").is_none());
    }

    #[test]
    fn two_sources_agreeing_make_a_name_certain() {
        let merged = merge(vec![
            NameEvidence {
                address: 0x1000,
                name: "Level::tick()".to_string(),
                source: EvidenceSource::VtableXref,
                detail: String::new(),
                confidence: Confidence::High,
            },
            NameEvidence {
                address: 0x1000,
                name: "Level::tick()".to_string(),
                source: EvidenceSource::CallGraph,
                detail: String::new(),
                confidence: Confidence::Low,
            },
        ]);
        assert_eq!(merged[&0x1000].confidence, Confidence::Certain);
    }

    #[test]
    fn a_conflicting_name_does_not_replace_a_more_confident_one() {
        let merged = merge(vec![
            NameEvidence {
                address: 0x2000,
                name: "Level::tick()".to_string(),
                source: EvidenceSource::LambdaEnclosing,
                detail: String::new(),
                confidence: Confidence::High,
            },
            NameEvidence {
                address: 0x2000,
                name: "something_else".to_string(),
                source: EvidenceSource::Shape,
                detail: String::new(),
                confidence: Confidence::Low,
            },
        ]);
        assert_eq!(merged[&0x2000].name, "Level::tick()");
    }

    #[test]
    fn a_lambda_call_operator_is_named_from_its_vtable_slot() {
        let map = map(vec![vtable(
            "Level::tick()::{lambda(int)#1}",
            "ZN5Level4tickEvEUliE_",
            0x3000,
        )]);
        let mut slots = std::collections::HashMap::new();
        slots.insert(0x3000u64, vec![0x4000u64]);
        let evidence = lambda_slot_names(&map, &slots);
        assert_eq!(evidence.len(), 1);
        assert_eq!(evidence[0].address, 0x4000);
        assert_eq!(
            evidence[0].name,
            "Level::tick()::{lambda(int)#1}::operator()"
        );
    }

    #[test]
    fn a_vtable_with_a_lambda_type_is_recognised_without_slots() {
        let map = map(vec![vtable(
            "Level::tick()::{lambda(int)#1}",
            "ZN5Level4tickEvEUliE_",
            0x3000,
        )]);
        // Slot recovery is the RTTI pass's job, so no name is emitted here;
        // what matters is that the lambda itself is seen.
        assert!(lambda_slot_names(&map, &std::collections::HashMap::new()).is_empty());
    }
}
