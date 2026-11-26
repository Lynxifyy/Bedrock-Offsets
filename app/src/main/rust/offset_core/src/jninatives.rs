//! `JNINativeMethod` tables found in the library's data sections.
//!
//! A library that registers its natives instead of exporting them keeps an
//! array of three pointers per method: `{ const char *name, const char
//! *signature, void *fnPtr }`. In a position-independent library those three
//! words are filled by the loader, so the array is visible as three
//! consecutive relocations whose targets point at read-only data (twice) and at
//! an executable section (once).
//!
//! Only relocations are consulted: reading the array words in place would see
//! zeroes, because the file has not been loaded. A triple is accepted only when
//! all three targets land in the section that makes sense for their role, which
//! is what keeps an arbitrary run of three pointers from looking like a table.
//!
//! The class a table belongs to is a heuristic. `RegisterNatives` is called
//! through a function pointer in the `JNIEnv` table, so there is no direct call
//! site to read the `FindClass` argument from. Instead the nearest class
//! descriptor string (`com/...`, `net/...`, `org/...`) relocated before the
//! table is used, and `class` is left out when none is within
//! [`CLASS_SEARCH_WINDOW_BYTES`].

use serde::Serialize;

use crate::error::CoreResult;
use crate::rtti::RelocationIndex;
use crate::sections::SectionMap;

/// Upper bound on the entries kept.
///
/// A real library registers a few thousand natives. The cap exists so a corrupt
/// relocation table cannot make the engine allocate without limit; hitting it is
/// reported in [`NativeTable::truncated`].
pub const MAX_REGISTERED_NATIVES: usize = 100_000;

/// How far back to look for the class descriptor of a table.
pub const CLASS_SEARCH_WINDOW_BYTES: u64 = 4096;

/// Longest method name accepted, in bytes.
const MAX_NAME_BYTES: usize = 256;

/// Longest JNI signature accepted, in bytes.
const MAX_SIGNATURE_BYTES: usize = 512;

/// One native method registered through `RegisterNatives`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct RegisteredNative {
    /// Method name as the JVM sees it.
    pub java_name: String,
    /// JNI signature, for example `(Ljava/lang/String;I)V`.
    pub signature: String,
    /// Address of the implementation.
    pub address: u64,
    /// Address rendered as `0x`-prefixed hex.
    pub address_hex: String,
    /// Owning class, when a descriptor was found near the table.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub class: Option<String>,
}

/// Every native method registered by the library.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
pub struct NativeTable {
    /// The methods, in table order.
    pub entries: Vec<RegisteredNative>,
    /// Number of three-pointer arrays that looked like a table.
    pub tables: usize,
    /// Whether [`MAX_REGISTERED_NATIVES`] stopped the scan.
    pub truncated: bool,
}

/// Finds every `JNINativeMethod` array in `image`.
///
/// # Errors
///
/// Returns `CoreError::OutOfMemory` when the relocation index cannot be
/// built, rather than aborting the process.
pub fn find_register_natives(image: &[u8], map: &SectionMap) -> CoreResult<NativeTable> {
    let relocations = RelocationIndex::build(image)?;
    Ok(collect_tables(&relocations, map, image))
}

/// Walks the relocation index looking for three-word runs.
///
/// Split from [`find_register_natives`] so tests can supply an index directly.
fn collect_tables(relocations: &RelocationIndex, map: &SectionMap, image: &[u8]) -> NativeTable {
    let mut table = NativeTable::default();
    let entries = relocations.entries();
    let mut index = 0usize;

    while index + 2 < entries.len() {
        let (first_slot, name_address) = entries[index];
        let (second_slot, signature_address) = entries[index + 1];
        let (third_slot, function_address) = entries[index + 2];

        // A table is three adjacent words, so the slots must be 8 bytes apart.
        if second_slot != first_slot + 8 || third_slot != first_slot + 16 {
            index += 1;
            continue;
        }

        let Some(java_name) = read_c_string(map, image, name_address, MAX_NAME_BYTES) else {
            index += 1;
            continue;
        };
        if !is_method_name(&java_name) {
            index += 1;
            continue;
        }
        let Some(signature) = read_c_string(map, image, signature_address, MAX_SIGNATURE_BYTES)
        else {
            index += 1;
            continue;
        };
        if !is_jni_signature(&signature) {
            index += 1;
            continue;
        }
        if !is_executable_address(map, function_address) {
            index += 1;
            continue;
        }

        if table.entries.len() >= MAX_REGISTERED_NATIVES {
            table.truncated = true;
            break;
        }

        table.entries.push(RegisteredNative {
            java_name,
            signature,
            address: function_address,
            address_hex: format!("0x{function_address:x}"),
            class: nearest_class(relocations, map, image, first_slot),
        });
        table.tables += 1;
        // Skip the triple so a long table does not report overlapping windows.
        index += 3;
    }

    table
}

/// Returns the class descriptor relocated most recently before `table_slot`.
fn nearest_class(
    relocations: &RelocationIndex,
    map: &SectionMap,
    image: &[u8],
    table_slot: u64,
) -> Option<String> {
    let entries = relocations.entries();
    let position = entries.partition_point(|(slot, _)| *slot < table_slot);
    let window_start = table_slot.saturating_sub(CLASS_SEARCH_WINDOW_BYTES);

    for (slot, target) in entries[..position].iter().rev() {
        if *slot < window_start {
            break;
        }
        let Some(text) = read_c_string(map, image, *target, MAX_NAME_BYTES) else {
            continue;
        };
        if is_class_descriptor(&text) {
            return Some(text);
        }
    }
    None
}

/// Reads a NUL-terminated ASCII string at a virtual address.
///
/// The address has to fall inside an allocated, non-executable section: a
/// "string" inside the code section is an instruction, not a name.
fn read_c_string(map: &SectionMap, image: &[u8], address: u64, limit: usize) -> Option<String> {
    let section = map.sections.iter().find(|section| {
        section.allocated
            && !section.executable
            && address >= section.address
            && address < section.address.saturating_add(section.size)
    })?;
    let file_offset = section.offset.checked_add(address - section.address)? as usize;
    let body = image.get(file_offset..)?;
    let end = body.iter().take(limit).position(|byte| *byte == 0)?;
    if end == 0 {
        return None;
    }
    let text = std::str::from_utf8(&body[..end]).ok()?;
    if !text.is_ascii() || text.chars().any(|c| c.is_control()) {
        return None;
    }
    Some(text.to_string())
}

/// Whether `text` can be a Java method name.
fn is_method_name(text: &str) -> bool {
    !text.is_empty()
        && text.len() <= MAX_NAME_BYTES
        && text
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '$' || c == '<' || c == '>')
}

/// Whether `text` follows the JNI descriptor grammar closely enough.
///
/// The full grammar is not needed: a leading `(` with a matching `)` and a
/// return descriptor is what separates a signature from an arbitrary string.
fn is_jni_signature(text: &str) -> bool {
    let bytes = text.as_bytes();
    if bytes.len() < 3 || bytes.len() > MAX_SIGNATURE_BYTES || bytes[0] != b'(' {
        return false;
    }
    let Some(close) = text.find(')') else {
        return false;
    };
    // `()` is a valid empty argument list, so only the closing paren at index
    // zero (a string that does not start with `(`) is rejected.
    if close == 0 {
        return false;
    }
    let returns = &text[close + 1..];
    if returns.is_empty() {
        return false;
    }
    let arguments_ok = text[1..close].bytes().all(|byte| {
        matches!(
            byte,
            b'Z' | b'B'
                | b'C'
                | b'S'
                | b'I'
                | b'J'
                | b'F'
                | b'D'
                | b'L'
                | b'['
                | b';'
                | b'/'
                | b'$'
                | b'_'
                | b'0'..=b'9'
                | b'a'..=b'z'
                | b'A'..=b'Z'
        )
    });
    // `V` for void, a single primitive letter, or an object/array descriptor.
    arguments_ok
        && (returns == "V"
            || returns.len() == 1
            || (returns.starts_with('L') && returns.ends_with(';'))
            || returns.starts_with('['))
}

/// Whether `text` looks like a class descriptor such as `com/mojang/Foo`.
fn is_class_descriptor(text: &str) -> bool {
    const ROOTS: [&str; 4] = ["com/", "net/", "org/", "java/"];
    ROOTS.iter().any(|root| text.starts_with(root))
        && text.len() <= MAX_NAME_BYTES
        && text
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '/' || c == '_' || c == '$')
}

/// Whether `address` falls inside the executable section.
fn is_executable_address(map: &SectionMap, address: u64) -> bool {
    let text = &map.text;
    address >= text.address && address < text.address.saturating_add(text.size)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sections::{SectionInfo, SectionKind, TEXT_SECTION};

    /// A synthetic image: code at 0x1000, read-only data at 0x2000, data at 0x3000.
    fn fixture() -> (Vec<u8>, SectionMap) {
        let mut image = vec![0u8; 0x4000];
        let strings = [
            (0x2000u64, "nativeInit"),
            (0x2020, "()V"),
            (0x2030, "com/mojang/minecraftpe/MainActivity"),
            (0x2080, "nativeTick"),
            (0x20a0, "(JLjava/lang/String;)Z"),
        ];
        for (address, text) in strings {
            let at = address as usize;
            image[at..at + text.len()].copy_from_slice(text.as_bytes());
        }
        let sections = vec![
            SectionInfo {
                name: TEXT_SECTION.to_string(),
                kind: SectionKind::Text,
                address: 0x1000,
                size: 0x1000,
                offset: 0x1000,
                align: 4,
                delta: 0,
                allocated: true,
                executable: true,
                writable: false,
                occupies_file: true,
            },
            SectionInfo {
                name: ".rodata".to_string(),
                kind: SectionKind::Rodata,
                address: 0x2000,
                size: 0x1000,
                offset: 0x2000,
                align: 8,
                delta: 0,
                allocated: true,
                executable: false,
                writable: false,
                occupies_file: true,
            },
            SectionInfo {
                name: ".data.rel.ro".to_string(),
                kind: SectionKind::DataRelRo,
                address: 0x3000,
                size: 0x1000,
                offset: 0x3000,
                align: 8,
                delta: 0,
                allocated: true,
                executable: false,
                writable: true,
                occupies_file: true,
            },
        ];
        let map = SectionMap {
            image_base: 0x1000,
            text: sections[0].clone(),
            sections,
        };
        (image, map)
    }

    #[test]
    fn a_three_pointer_array_becomes_a_registered_native() {
        let (image, map) = fixture();
        let relocations = RelocationIndex::for_tests(vec![
            (0x3010, 0x2030),
            (0x3040, 0x2000),
            (0x3048, 0x2020),
            (0x3050, 0x1100),
        ]);
        let table = collect_tables(&relocations, &map, &image);
        assert_eq!(table.tables, 1);
        assert_eq!(table.entries.len(), 1);
        let entry = &table.entries[0];
        assert_eq!(entry.java_name, "nativeInit");
        assert_eq!(entry.signature, "()V");
        assert_eq!(entry.address, 0x1100);
        assert_eq!(entry.address_hex, "0x1100");
        assert_eq!(
            entry.class.as_deref(),
            Some("com/mojang/minecraftpe/MainActivity")
        );
    }

    #[test]
    fn two_tables_in_one_run_are_both_reported() {
        let (image, map) = fixture();
        let relocations = RelocationIndex::for_tests(vec![
            (0x3010, 0x2000),
            (0x3018, 0x2020),
            (0x3020, 0x1100),
            (0x3028, 0x2080),
            (0x3030, 0x20a0),
            (0x3038, 0x1200),
        ]);
        let table = collect_tables(&relocations, &map, &image);
        assert_eq!(table.tables, 2);
        assert_eq!(table.entries[1].java_name, "nativeTick");
        assert_eq!(table.entries[1].signature, "(JLjava/lang/String;)Z");
        assert_eq!(table.entries[1].address, 0x1200);
    }

    #[test]
    fn a_run_without_a_signature_is_not_a_table() {
        let (image, map) = fixture();
        let relocations =
            RelocationIndex::for_tests(vec![(0x3010, 0x2000), (0x3018, 0x2080), (0x3020, 0x1100)]);
        let table = collect_tables(&relocations, &map, &image);
        assert_eq!(table.tables, 0);
        assert!(table.entries.is_empty());
    }

    #[test]
    fn a_function_pointer_outside_the_code_section_is_rejected() {
        let (image, map) = fixture();
        let relocations =
            RelocationIndex::for_tests(vec![(0x3010, 0x2000), (0x3018, 0x2020), (0x3020, 0x2050)]);
        assert!(collect_tables(&relocations, &map, &image)
            .entries
            .is_empty());
    }

    #[test]
    fn slots_that_are_not_adjacent_are_rejected() {
        let (image, map) = fixture();
        let relocations =
            RelocationIndex::for_tests(vec![(0x3010, 0x2000), (0x3020, 0x2020), (0x3030, 0x1100)]);
        assert!(collect_tables(&relocations, &map, &image)
            .entries
            .is_empty());
    }

    #[test]
    fn the_nearest_class_descriptor_is_attributed() {
        let (image, map) = fixture();
        let relocations = RelocationIndex::for_tests(vec![
            (0x3000, 0x2030),
            (0x3010, 0x2000),
            (0x3018, 0x2020),
            (0x3020, 0x1100),
        ]);
        let attributed = collect_tables(&relocations, &map, &image);
        assert_eq!(
            attributed.entries[0].class.as_deref(),
            Some("com/mojang/minecraftpe/MainActivity")
        );
    }

    #[test]
    fn a_table_without_a_class_descriptor_has_no_class() {
        let (image, map) = fixture();
        let relocations =
            RelocationIndex::for_tests(vec![(0x3040, 0x2000), (0x3048, 0x2020), (0x3050, 0x1100)]);
        assert_eq!(
            collect_tables(&relocations, &map, &image).entries[0].class,
            None
        );
    }

    #[test]
    fn the_signature_grammar_accepts_descriptors() {
        assert!(is_jni_signature("()V"));
        assert!(is_jni_signature("(I)V"));
        assert!(is_jni_signature("(JLjava/lang/String;)Z"));
        assert!(is_jni_signature("([B)V"));
        assert!(is_jni_signature("(Landroid/view/View;)V"));
        assert!(is_jni_signature(
            "(IIJLjava/lang/Object;)Ljava/lang/Object;"
        ));
        assert!(!is_jni_signature(""));
        assert!(!is_jni_signature("nativeInit"));
        assert!(!is_jni_signature("(V)"));
        assert!(!is_jni_signature("()"));
        assert!(!is_jni_signature("(I)"));
    }

    #[test]
    fn the_name_filter_rejects_free_text() {
        assert!(is_method_name("nativeInit"));
        assert!(is_method_name("<init>"));
        assert!(!is_method_name("not a name"));
        assert!(!is_method_name(""));
    }
}
