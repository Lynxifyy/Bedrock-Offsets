//! Section headers and virtual address mapping.
//!
//! Scanning works on *virtual addresses*, because that is what an offset table
//! consumed by a loader needs, while the bytes are addressed by *file offset*.
//! The two differ by the section's load delta, which this module computes once
//! per section and exposes to the scanner.
//!
//! Only the section header table and its string table are read here. No section
//! body is ever copied, so a 800 MB `.rodata` costs nothing extra.

use goblin::elf::section_header::{
    SectionHeader, SHF_ALLOC, SHF_EXECINSTR, SHF_WRITE, SHT_NOBITS, SHT_STRTAB,
};
use goblin::elf::Elf;
use goblin::strtab::Strtab;
use serde::Serialize;

use crate::elf::{parse_elf_bytes, parse_section_headers};
use crate::error::{CoreError, CoreResult};

/// The section that holds executable code.
pub const TEXT_SECTION: &str = ".text";

/// Read-only initialised data.
pub const RODATA_SECTION: &str = ".rodata";

/// Read-only data that carries dynamic relocations, such as vtables.
pub const DATA_REL_RO_SECTION: &str = ".data.rel.ro";

/// Dynamic relocations, used by the RTTI phase.
pub const RELA_DYN_SECTION: &str = ".rela.dyn";

/// The GNU build identifier note.
pub const BUILD_ID_SECTION: &str = ".note.gnu.build-id";

/// Dynamic symbol table.
pub const DYNSYM_SECTION: &str = ".dynsym";

/// Dynamic string table.
pub const DYNSTR_SECTION: &str = ".dynstr";

/// Role a section plays for the scanner.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum SectionKind {
    /// Executable code; the scanner reads this.
    Text,
    /// Read-only data; holds RTTI strings.
    Rodata,
    /// Relocatable read-only data; holds vtables.
    DataRelRo,
    /// Anything else, retained so relocations can be resolved.
    Other,
}

impl SectionKind {
    /// Classifies a section by its name.
    ///
    /// # Returns
    ///
    /// The matching kind, or [`SectionKind::Other`] for unnamed or unrelated
    /// sections.
    pub fn from_name(name: &str) -> Self {
        match name {
            TEXT_SECTION => Self::Text,
            RODATA_SECTION => Self::Rodata,
            DATA_REL_RO_SECTION => Self::DataRelRo,
            _ => Self::Other,
        }
    }
}

/// One section header, resolved to both address spaces.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct SectionInfo {
    /// Name from the section header string table.
    pub name: String,
    /// Role the section plays for the scanner.
    pub kind: SectionKind,
    /// Virtual address the section is loaded at.
    pub address: u64,
    /// Size of the section in bytes.
    pub size: u64,
    /// Offset of the section body inside the file.
    pub offset: u64,
    /// Required alignment.
    pub align: u64,
    /// `address - offset` for this section.
    pub delta: u64,
    /// Whether the section is mapped into memory at run time.
    pub allocated: bool,
    /// Whether the section is executable.
    pub executable: bool,
    /// Whether the section is writable.
    pub writable: bool,
    /// Whether the section occupies file space; `.bss` does not.
    pub occupies_file: bool,
}

impl SectionInfo {
    /// Converts a file offset inside this section to a virtual address.
    ///
    /// # Returns
    ///
    /// The virtual address, or `None` when the section has no address.
    pub fn address_of(&self, file_offset: u64) -> Option<u64> {
        if self.address == 0 {
            return None;
        }
        Some(file_offset.wrapping_add(self.delta))
    }

    /// Converts a virtual address back to a file offset.
    ///
    /// # Returns
    ///
    /// The file offset, or `None` when the address is outside the section.
    pub fn offset_of(&self, address: u64) -> Option<u64> {
        let end = self.address.checked_add(self.size)?;
        if address < self.address || address >= end {
            return None;
        }
        Some(address - self.delta)
    }
}

/// Every section of a library, plus the derived load base.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct SectionMap {
    /// Lowest load address of any allocated section.
    pub image_base: u64,
    /// The executable section, which the scanner walks.
    pub text: SectionInfo,
    /// Every section, in header order.
    pub sections: Vec<SectionInfo>,
}

impl SectionMap {
    /// Returns the first section with `name`.
    ///
    /// # Returns
    ///
    /// The section, or `None` when it is absent or stripped.
    pub fn find(&self, name: &str) -> Option<&SectionInfo> {
        self.sections.iter().find(|section| section.name == name)
    }

    /// Returns the raw bytes of a section, or an empty slice when it is absent.
    ///
    /// The returned slice borrows the mapped file; nothing is copied. `.text` is
    /// resolved from the dedicated field, so a map built without the full
    /// section list still yields the executable bytes.
    pub fn bytes_of<'a>(&self, name: &str, image: &'a [u8]) -> &'a [u8] {
        let found = self
            .find(name)
            .or_else(|| (self.text.name == name).then_some(&self.text));
        match found {
            Some(section) if section.occupies_file => {
                let start = section.offset as usize;
                let end = start.saturating_add(section.size as usize);
                image.get(start..end).unwrap_or(&[])
            }
            _ => &[],
        }
    }
}

/// Parses the section header table and builds the address map.
///
/// # Errors
///
/// Returns [`CoreError::InvalidElf`] when the section table or its string table
/// cannot be read, and [`CoreError::MissingSection`] when the library has no
/// usable `.text` section, which would make scanning meaningless.
pub fn build_section_map(image: &[u8]) -> CoreResult<SectionMap> {
    // Validates the magic number, the class and the machine before any table is
    // read, so a non-ELF file is rejected with the right error.
    let elf = parse_elf_bytes(image)?;
    let headers = parse_section_headers(image)?;

    if headers.is_empty() {
        return Err(CoreError::MissingSection(
            "the library has no section header table; it may be fully stripped".to_string(),
        ));
    }

    let names = section_names(&elf, &headers, image)?;

    let mut sections = Vec::with_capacity(headers.len());
    for header in &headers {
        let name = names
            .get_at(header.sh_name)
            .map(str::to_string)
            .unwrap_or_default();
        let allocated = header.sh_flags & u64::from(SHF_ALLOC) != 0;
        sections.push(SectionInfo {
            kind: SectionKind::from_name(&name),
            name,
            address: header.sh_addr,
            size: header.sh_size,
            offset: header.sh_offset,
            align: header.sh_addralign,
            delta: header.sh_addr.wrapping_sub(header.sh_offset),
            allocated,
            executable: header.sh_flags & u64::from(SHF_EXECINSTR) != 0,
            writable: header.sh_flags & u64::from(SHF_WRITE) != 0,
            occupies_file: header.sh_type != SHT_NOBITS,
        });
    }

    let image_base = sections
        .iter()
        .filter(|section| section.allocated && section.address != 0)
        .map(|section| section.address)
        .min()
        .unwrap_or(0);

    let text = sections
        .iter()
        .find(|section| section.kind == SectionKind::Text)
        .cloned()
        .ok_or_else(|| {
            CoreError::MissingSection(format!(
                "{TEXT_SECTION} is absent; the library is corrupted or stripped"
            ))
        })?;

    if text.address == 0 {
        return Err(CoreError::MissingSection(format!(
            "{TEXT_SECTION} has no load address"
        )));
    }
    if text.size == 0 {
        return Err(CoreError::MissingSection(format!(
            "{TEXT_SECTION} is empty"
        )));
    }

    Ok(SectionMap {
        image_base,
        text,
        sections,
    })
}

/// Resolves the section header string table.
///
/// The table index comes from `e_shstrndx`, which may be an escape value
/// pointing at the first section when there are more than 65 280 sections.
///
/// # Errors
///
/// Returns [`CoreError::InvalidElf`] when the table cannot be located or read.
fn section_names<'a>(
    elf: &Elf<'a>,
    headers: &[SectionHeader],
    image: &'a [u8],
) -> CoreResult<Strtab<'a>> {
    use goblin::elf::section_header::{SHN_UNDEF, SHN_XINDEX};

    let mut index = usize::from(elf.header.e_shstrndx);
    if elf.header.e_shstrndx == SHN_XINDEX as u16 {
        let first = headers.first().ok_or_else(|| {
            CoreError::InvalidElf("extended section index without a section table".to_string())
        })?;
        index = first.sh_link as usize;
    }

    if index == SHN_UNDEF as usize {
        return Err(CoreError::InvalidElf(
            "the library has no section header string table".to_string(),
        ));
    }

    let header = headers.get(index).ok_or_else(|| {
        CoreError::InvalidElf(format!("section name table index {index} is out of range"))
    })?;

    if header.sh_type != SHT_STRTAB {
        return Err(CoreError::InvalidElf(
            "the section name table is not a string table".to_string(),
        ));
    }

    Strtab::parse(
        image,
        header.sh_offset as usize,
        header.sh_size as usize,
        0x0,
    )
    .map_err(|error| CoreError::InvalidElf(format!("cannot read section names: {error}")))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn kind_classification_covers_the_scanned_sections() {
        assert_eq!(SectionKind::from_name(".text"), SectionKind::Text);
        assert_eq!(SectionKind::from_name(".rodata"), SectionKind::Rodata);
        assert_eq!(
            SectionKind::from_name(".data.rel.ro"),
            SectionKind::DataRelRo
        );
        assert_eq!(SectionKind::from_name(".bss"), SectionKind::Other);
        assert_eq!(SectionKind::from_name(""), SectionKind::Other);
    }

    #[test]
    fn offset_to_address_uses_the_load_delta() {
        let section = SectionInfo {
            name: ".text".to_string(),
            kind: SectionKind::Text,
            address: 0x1000,
            size: 0x2000,
            offset: 0x800,
            align: 4,
            delta: 0x800,
            allocated: true,
            executable: true,
            writable: false,
            occupies_file: true,
        };

        assert_eq!(section.address_of(0x800), Some(0x1000));
        assert_eq!(section.address_of(0x1000), Some(0x1800));
        assert_eq!(section.offset_of(0x1000), Some(0x800));
        assert_eq!(section.offset_of(0x2FFF), Some(0x27FF));
        assert_eq!(section.offset_of(0x3000), None);
        assert_eq!(section.offset_of(0xFFF), None);
    }

    #[test]
    fn a_section_without_an_address_has_no_mapping() {
        let section = SectionInfo {
            name: ".debug".to_string(),
            kind: SectionKind::Other,
            address: 0,
            size: 16,
            offset: 16,
            align: 1,
            delta: 0,
            allocated: false,
            executable: false,
            writable: false,
            occupies_file: true,
        };

        assert_eq!(section.address_of(64), None);
    }

    #[test]
    fn missing_text_is_reported_as_a_missing_section() {
        let mut bytes = vec![0u8; 64];
        bytes[..4].copy_from_slice(&[0x7f, b'E', b'L', b'F']);
        bytes[4] = 2;
        bytes[5] = 1;
        bytes[18..20].copy_from_slice(&183u16.to_le_bytes());

        let error = build_section_map(&bytes).expect_err("no sections at all");
        assert!(matches!(error, CoreError::MissingSection(_)), "{error}");
    }
}
