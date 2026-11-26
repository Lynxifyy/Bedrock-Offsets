//! A synthetic AArch64 shared object with every table the engine reads.
//!
//! The fixture is built here rather than checked in as a binary so the planted
//! addresses, symbol names, vtable slots and `.eh_frame` ranges are readable in
//! one place. Every test that needs ground truth ("the engine must recover
//! exactly these addresses") builds it and asserts against the constants.
//!
//! Layout, all in one `ET_DYN` image:
//!
//! ```text
//! .text        0x10000   FUNCTIONS functions, each FRAME_BYTES long
//! .rodata      0x40000   length-prefixed RTTI names
//! .data.rel.ro 0x50000   CLASSES vtables and their type_info objects
//! .rela.dyn    0x60000   one R_AARCH64_RELATIVE per pointer above
//! .dynsym      0x68000   a function symbol, a data symbol and a Java_ export
//! .dynstr      0x69000
//! .eh_frame    0x70000   one FDE per planted function
//! ```

#![allow(dead_code)]

/// Number of functions planted in `.text`.
pub const FUNCTIONS: usize = 8;

/// Bytes per planted function.
pub const FRAME_BYTES: usize = 0x40;

/// Base address of `.text`.
pub const TEXT_ADDRESS: u64 = 0x10000;

/// Base address of `.rodata`.
pub const RODATA_ADDRESS: u64 = 0x40000;

/// Base address of `.data.rel.ro`.
pub const DATA_REL_RO_ADDRESS: u64 = 0x50000;

/// Base address of `.rela.dyn`.
pub const RELA_DYN_ADDRESS: u64 = 0x60000;

/// Base address of `.dynsym`.
pub const DYNSYM_ADDRESS: u64 = 0x68000;

/// Base address of `.dynstr`.
pub const DYNSTR_ADDRESS: u64 = 0x69000;

/// Base address of `.eh_frame`.
pub const EH_FRAME_ADDRESS: u64 = 0x70000;

/// Number of RTTI classes planted.
pub const CLASSES: usize = 2;

/// Virtual slots per planted vtable.
pub const SLOTS_PER_CLASS: usize = 3;

/// Address of the first planted function.
pub const FIRST_FUNCTION: u64 = TEXT_ADDRESS;

/// Demangled name of the first planted class.
pub const FIRST_CLASS: &str = "FixtureActor";

/// Name of the exported function symbol, which must land on [`FIRST_FUNCTION`].
pub const SYMBOL_NAME: &str = "_ZN12FixtureActor4tickEv";

/// Demangled form of [`SYMBOL_NAME`], as the engine reports it.
pub const SYMBOL_DEMANGLED: &str = "FixtureActor::tick()";

/// Name of the exported JNI function.
pub const JNI_NAME: &str = "Java_com_example_Fixture_nativeTick";

/// Address of the JNI export, one function into `.text`.
pub const JNI_ADDRESS: u64 = TEXT_ADDRESS + FRAME_BYTES as u64;

/// Address of the planted data symbol.
pub const DATA_SYMBOL_ADDRESS: u64 = DATA_REL_RO_ADDRESS + 0x200;

/// `stp x29, x30, [sp, #-0x10]!` followed by `mov x29, sp`.
const PROLOGUE: [u32; 2] = [0xA9BF_7BFD, 0x9100_03FD];

const SHT_PROGBITS: u32 = 1;
const SHT_SYMTAB: u32 = 2;
const SHT_STRTAB: u32 = 3;
const SHT_RELA: u32 = 4;
const SHT_NOTE: u32 = 7;
const SHT_NOBITS: u32 = 8;

const SHF_WRITE: u64 = 0x1;
const SHF_ALLOC: u64 = 0x2;
const SHF_EXECINSTR: u64 = 0x4;

const STB_GLOBAL: u8 = 1;
const STT_OBJECT: u8 = 1;
const STT_FUNC: u8 = 2;

const R_AARCH64_RELATIVE: u32 = 1027;

const EHDR_BYTES: usize = 64;
const SHDR_BYTES: usize = 64;
const SYMENT_BYTES: usize = 24;
const FILE_BASE: usize = 0x100;

/// One section to lay out.
pub struct SectionPlan {
    pub name: &'static str,
    pub kind: u32,
    pub address: u64,
    pub flags: u64,
    pub body: Vec<u8>,
    pub size_override: Option<u64>,
}

impl SectionPlan {
    fn new(name: &'static str, kind: u32, address: u64, flags: u64, body: Vec<u8>) -> Self {
        Self {
            name,
            kind,
            address,
            flags,
            body,
            size_override: None,
        }
    }
}

/// Virtual address of planted function `index`.
pub fn function_address(index: usize) -> u64 {
    TEXT_ADDRESS + (index * FRAME_BYTES) as u64
}

/// Virtual address of the `slot`-th function pointer of planted class `index`.
pub fn vtable_slot_address(class: usize, slot: usize) -> u64 {
    DATA_REL_RO_ADDRESS + (class * 0x40 + 0x10 + slot * 8) as u64
}

/// Virtual address of the header of planted class `index`.
pub fn vtable_address(class: usize) -> u64 {
    DATA_REL_RO_ADDRESS + (class * 0x40) as u64
}

/// Virtual address of the `type_info` object of planted class `index`.
pub fn type_info_address(class: usize) -> u64 {
    DATA_REL_RO_ADDRESS + (0x100 + class * 0x20) as u64
}

fn rela(offset: u64, kind: u32, addend: u64) -> Vec<u8> {
    let mut bytes = Vec::with_capacity(24);
    bytes.extend_from_slice(&offset.to_le_bytes());
    bytes.extend_from_slice(&u64::from(kind).to_le_bytes());
    bytes.extend_from_slice(&addend.to_le_bytes());
    bytes
}

fn sym(name_offset: u32, info: u8, section_index: u16, value: u64, size: u64) -> Vec<u8> {
    let mut bytes = Vec::with_capacity(SYMENT_BYTES);
    bytes.extend_from_slice(&name_offset.to_le_bytes());
    bytes.push(info);
    bytes.push(0);
    bytes.extend_from_slice(&section_index.to_le_bytes());
    bytes.extend_from_slice(&value.to_le_bytes());
    bytes.extend_from_slice(&size.to_le_bytes());
    bytes
}

fn build_id_note(identifier: &[u8]) -> Vec<u8> {
    const NAME: &[u8] = b"GNU\0";
    let mut body = Vec::new();
    body.extend_from_slice(&(NAME.len() as u32).to_le_bytes());
    body.extend_from_slice(&(identifier.len() as u32).to_le_bytes());
    body.extend_from_slice(&3u32.to_le_bytes());
    body.extend_from_slice(NAME);
    body.extend_from_slice(identifier);
    while body.len() % 4 != 0 {
        body.push(0);
    }
    body
}

/// A `.eh_frame` with one CIE and one FDE per planted function.
///
/// Encoding is `DW_EH_PE_pcrel | DW_EH_PE_sdata4` (`0x1B`), which is what the
/// NDK emits and what the engine's parser expects.
fn eh_frame() -> Vec<u8> {
    let mut record = Vec::new();
    let mut cie_body = vec![1u8];
    cie_body.extend_from_slice(b"zR\0");
    cie_body.push(1); // code alignment
    cie_body.push(0x78); // data alignment -8
    cie_body.push(30); // return register x30
    cie_body.push(1); // augmentation length
    cie_body.push(0x1B); // R: pcrel | sdata4
    record.extend_from_slice(&(4 + cie_body.len() as u32).to_le_bytes());
    record.extend_from_slice(&0u32.to_le_bytes());
    record.extend_from_slice(&cie_body);
    let cie_start = 0usize;

    for index in 0..FUNCTIONS {
        let fde_start = record.len();
        let content_start = fde_start + 4;
        let id = (content_start - cie_start) as u32;
        let initial_position = content_start + 4;
        let field_address = EH_FRAME_ADDRESS + initial_position as u64;
        let initial = (function_address(index) as i64 - field_address as i64) as i32;
        let mut body = Vec::new();
        body.extend_from_slice(&initial.to_le_bytes());
        body.extend_from_slice(&(FRAME_BYTES as i32).to_le_bytes());
        body.push(0); // the `z` augmentation length
        record.extend_from_slice(&(4 + body.len() as u32).to_le_bytes());
        record.extend_from_slice(&id.to_le_bytes());
        record.extend_from_slice(&body);
    }
    record
}

/// Builds the synthetic library.
pub fn build_library() -> Vec<u8> {
    // `.text`: every function starts with the canonical frame push, then a
    // per-function immediate so no two signatures collide.
    let mut text = Vec::with_capacity(FUNCTIONS * FRAME_BYTES);
    for index in 0..FUNCTIONS {
        for word in PROLOGUE {
            text.extend_from_slice(&word.to_le_bytes());
        }
        while text.len() % FRAME_BYTES != 0 {
            if text.len() % FRAME_BYTES == 8 {
                let immediate = ((index as u32) & 0xfff) << 10;
                text.extend_from_slice(&(0x9100_0000u32 | immediate).to_le_bytes());
            } else {
                text.extend_from_slice(&0xD503_201Fu32.to_le_bytes());
            }
        }
    }

    // `.rodata`: length-prefixed type names.
    let mut rodata = vec![0u8; 0x80];
    let mut cursor = 0x10;
    let mut name_addresses = Vec::new();
    for index in 0..CLASSES {
        let name = format!("Fixture{}", if index == 0 { "Actor" } else { "Mob" });
        let encoded = format!("{}{}", name.len(), name);
        name_addresses.push(RODATA_ADDRESS + cursor as u64);
        rodata[cursor..cursor + encoded.len()].copy_from_slice(encoded.as_bytes());
        cursor += encoded.len() + 1;
    }

    // `.data.rel.ro`: CLASSES vtables of SLOTS_PER_CLASS slots, then the
    // type_info objects. Slot `n` of class `c` points at function
    // `(c + n) % FUNCTIONS`, so every planted slot address is known.
    let mut data_rel_ro = vec![0u8; 0x300];
    let mut relocations = Vec::new();
    for class in 0..CLASSES {
        let base = class * 0x40;
        data_rel_ro[base + 8..base + 16].copy_from_slice(&type_info_address(class).to_le_bytes());
        relocations.extend_from_slice(&rela(
            vtable_address(class) + 8,
            R_AARCH64_RELATIVE,
            type_info_address(class),
        ));
        for slot in 0..SLOTS_PER_CLASS {
            let target = function_address((class + slot) % FUNCTIONS);
            let at = base + 0x10 + slot * 8;
            data_rel_ro[at..at + 8].copy_from_slice(&target.to_le_bytes());
            relocations.extend_from_slice(&rela(
                vtable_address(class) + 0x10 + slot as u64 * 8,
                R_AARCH64_RELATIVE,
                target,
            ));
        }
    }
    for (class, name_address) in name_addresses.iter().enumerate().take(CLASSES) {
        let at = 0x100 + class * 0x20;
        // type_info[0] is the type_info vtable pointer, which must not look
        // like a name pointer; type_info[1] is the name.
        data_rel_ro[at..at + 8].copy_from_slice(&(TEXT_ADDRESS + 0x10).to_le_bytes());
        data_rel_ro[at + 8..at + 16].copy_from_slice(&name_address.to_le_bytes());
        relocations.extend_from_slice(&rela(
            type_info_address(class) + 8,
            R_AARCH64_RELATIVE,
            *name_address,
        ));
    }

    // `.dynsym`: null, the tick symbol, the JNI export, the data symbol.
    let mut dynstr = vec![0u8];
    let mut names = Vec::new();
    for text in [SYMBOL_NAME, JNI_NAME, "_ZN12FixtureActor5stateE"] {
        let offset = dynstr.len() as u32;
        dynstr.extend_from_slice(text.as_bytes());
        dynstr.push(0);
        names.push(offset);
    }
    let (tick_offset, jni_offset, data_offset) = (names[0], names[1], names[2]);

    let mut dynsym = sym(0, 0, 0, 0, 0);
    dynsym.extend_from_slice(&sym(
        tick_offset,
        (STB_GLOBAL << 4) | STT_FUNC,
        1,
        FIRST_FUNCTION,
        FRAME_BYTES as u64,
    ));
    dynsym.extend_from_slice(&sym(
        jni_offset,
        (STB_GLOBAL << 4) | STT_FUNC,
        1,
        JNI_ADDRESS,
        FRAME_BYTES as u64,
    ));
    dynsym.extend_from_slice(&sym(
        data_offset,
        (STB_GLOBAL << 4) | STT_OBJECT,
        3,
        DATA_SYMBOL_ADDRESS,
        8,
    ));

    let mut bss = SectionPlan::new(
        ".bss",
        SHT_NOBITS,
        0x80000,
        SHF_ALLOC | SHF_WRITE,
        Vec::new(),
    );
    bss.size_override = Some(0x1000);

    let sections = vec![
        SectionPlan::new(
            ".text",
            SHT_PROGBITS,
            TEXT_ADDRESS,
            SHF_ALLOC | SHF_EXECINSTR,
            text,
        ),
        SectionPlan::new(".rodata", SHT_PROGBITS, RODATA_ADDRESS, SHF_ALLOC, rodata),
        SectionPlan::new(
            ".data.rel.ro",
            SHT_PROGBITS,
            DATA_REL_RO_ADDRESS,
            SHF_ALLOC | SHF_WRITE,
            data_rel_ro,
        ),
        SectionPlan::new(
            ".rela.dyn",
            SHT_RELA,
            RELA_DYN_ADDRESS,
            SHF_ALLOC,
            relocations,
        ),
        SectionPlan::new(".dynsym", SHT_SYMTAB, DYNSYM_ADDRESS, SHF_ALLOC, dynsym),
        SectionPlan::new(".dynstr", SHT_STRTAB, DYNSTR_ADDRESS, SHF_ALLOC, dynstr),
        SectionPlan::new(
            ".eh_frame",
            SHT_PROGBITS,
            EH_FRAME_ADDRESS,
            SHF_ALLOC,
            eh_frame(),
        ),
        SectionPlan::new(
            ".note.gnu.build-id",
            SHT_NOTE,
            EH_FRAME_ADDRESS + 0x1000,
            SHF_ALLOC,
            build_id_note(&[0xde, 0xad, 0xbe, 0xef]),
        ),
        bss,
    ];

    assemble(&sections)
}

fn assemble(sections: &[SectionPlan]) -> Vec<u8> {
    let mut names = vec![0u8];
    let mut name_offsets = Vec::new();
    for section in sections {
        name_offsets.push(names.len() as u32);
        names.extend_from_slice(section.name.as_bytes());
        names.push(0);
    }
    let shstrtab_name_offset = names.len() as u32;
    names.extend_from_slice(b".shstrtab\0");

    let section_count = sections.len() + 2;
    let shstrtab_index = (section_count - 1) as u16;

    let mut cursor = FILE_BASE;
    let mut body_offsets = Vec::new();
    for section in sections {
        body_offsets.push(cursor);
        cursor += section.body.len();
        cursor = (cursor + 7) & !7;
    }
    let shstrtab_offset = cursor;
    cursor += names.len();
    let section_header_offset = (cursor + 7) & !7;
    let total = section_header_offset + section_count * SHDR_BYTES;

    let mut image = vec![0u8; total];

    image[..4].copy_from_slice(&[0x7f, b'E', b'L', b'F']);
    image[4] = 2;
    image[5] = 1;
    image[6] = 1;
    image[16..18].copy_from_slice(&3u16.to_le_bytes());
    image[18..20].copy_from_slice(&183u16.to_le_bytes());
    image[20..24].copy_from_slice(&1u32.to_le_bytes());
    image[24..32].copy_from_slice(&FIRST_FUNCTION.to_le_bytes());
    image[32..40].copy_from_slice(&(EHDR_BYTES as u64).to_le_bytes());
    image[40..48].copy_from_slice(&(section_header_offset as u64).to_le_bytes());
    image[52..54].copy_from_slice(&(EHDR_BYTES as u16).to_le_bytes());
    image[58..60].copy_from_slice(&(SHDR_BYTES as u16).to_le_bytes());
    image[60..62].copy_from_slice(&(section_count as u16).to_le_bytes());
    image[62..64].copy_from_slice(&shstrtab_index.to_le_bytes());

    for (index, section) in sections.iter().enumerate() {
        let start = body_offsets[index];
        image[start..start + section.body.len()].copy_from_slice(&section.body);
    }
    image[shstrtab_offset..shstrtab_offset + names.len()].copy_from_slice(&names);

    for (index, section) in sections.iter().enumerate() {
        write_section_header(
            &mut image,
            section_header_offset,
            index + 1,
            name_offsets[index],
            section.kind,
            section.flags,
            section.address,
            body_offsets[index] as u64,
            section.size_override.unwrap_or(section.body.len() as u64),
        );
    }
    write_section_header(
        &mut image,
        section_header_offset,
        sections.len() + 1,
        shstrtab_name_offset,
        SHT_STRTAB,
        0,
        0,
        shstrtab_offset as u64,
        names.len() as u64,
    );

    image
}

#[allow(clippy::too_many_arguments)]
fn write_section_header(
    image: &mut [u8],
    table_offset: usize,
    index: usize,
    name: u32,
    kind: u32,
    flags: u64,
    address: u64,
    offset: u64,
    size: u64,
) {
    let mut cursor = table_offset + index * SHDR_BYTES;
    put_u32(image, &mut cursor, name);
    put_u32(image, &mut cursor, kind);
    put_u64(image, &mut cursor, flags);
    put_u64(image, &mut cursor, address);
    put_u64(image, &mut cursor, offset);
    put_u64(image, &mut cursor, size);
    put_u32(image, &mut cursor, 0); // sh_link
    put_u32(image, &mut cursor, 0); // sh_info
    put_u64(image, &mut cursor, 8); // sh_addralign
    put_u64(image, &mut cursor, 0); // sh_entsize
}

fn put_u32(image: &mut [u8], cursor: &mut usize, value: u32) {
    image[*cursor..*cursor + 4].copy_from_slice(&value.to_le_bytes());
    *cursor += 4;
}

fn put_u64(image: &mut [u8], cursor: &mut usize, value: u64) {
    image[*cursor..*cursor + 8].copy_from_slice(&value.to_le_bytes());
    *cursor += 8;
}
