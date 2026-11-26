//! The load base must come from `PT_LOAD`, not from the first allocated section.
//!
//! A library whose first section starts after the header area (the 26.60
//! `libminecraftpe.so` starts at `.gnu.hash`, `0x2463f0`) would otherwise
//! report a base of `0x2463f0` instead of `0`, which shifts every `sub_<hex>`
//! name and every `vtableOffset` by that amount.

use offset_core::sections::build_section_map;

const HEADER: usize = 64;
const PHDR: usize = 56;
const TEXT: usize = 16;
const SHSTRTAB: usize = 16;

fn write_u16(image: &mut [u8], offset: usize, value: u16) {
    image[offset..offset + 2].copy_from_slice(&value.to_le_bytes());
}

fn write_u32(image: &mut [u8], offset: usize, value: u32) {
    image[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
}

fn write_u64(image: &mut [u8], offset: usize, value: u64) {
    image[offset..offset + 8].copy_from_slice(&value.to_le_bytes());
}

/// Builds a 64-bit AArch64 ELF with `.text` at `text_address`.
///
/// `load_address` adds a `PT_LOAD` when it is `Some`, which is the case for a
/// real shared library.
fn elf_with_text_at(text_address: u64, load_address: Option<u64>) -> Vec<u8> {
    let phdr_size = if load_address.is_some() { PHDR } else { 0 };
    let text_offset = HEADER + phdr_size;
    let shstrtab_offset = text_offset + TEXT;
    let shoff = shstrtab_offset + SHSTRTAB;
    let shnum: u16 = 3;

    let mut image = vec![0u8; shoff + shnum as usize * 64];

    image[..4].copy_from_slice(&[0x7f, b'E', b'L', b'F']);
    image[4] = 2;
    image[5] = 1;
    image[6] = 1;
    write_u16(&mut image, 16, 3); // ET_DYN
    write_u16(&mut image, 18, 183); // EM_AARCH64
    write_u64(&mut image, 24, text_address); // e_entry
    write_u64(&mut image, 32, HEADER as u64); // e_phoff
    write_u64(&mut image, 40, shoff as u64); // e_shoff
    write_u16(&mut image, 52, HEADER as u16); // e_ehsize
    write_u16(&mut image, 54, PHDR as u16); // e_phentsize
    write_u16(&mut image, 56, if load_address.is_some() { 1 } else { 0 });
    write_u16(&mut image, 58, 64); // e_shentsize
    write_u16(&mut image, 60, shnum);
    write_u16(&mut image, 62, 2); // .shstrtab index

    let total = image.len() as u64;
    if let Some(load_address) = load_address {
        write_u32(&mut image, HEADER, 1); // PT_LOAD
        write_u32(&mut image, HEADER + 4, 5); // PF_R | PF_X
        write_u64(&mut image, HEADER + 8, 0); // p_offset
        write_u64(&mut image, HEADER + 16, load_address); // p_vaddr
        write_u64(&mut image, HEADER + 24, load_address); // p_paddr
        write_u64(&mut image, HEADER + 32, total); // p_filesz
        write_u64(&mut image, HEADER + 40, total); // p_memsz
        write_u64(&mut image, HEADER + 48, 0x1000); // p_align
    }

    image[text_offset..text_offset + 4].copy_from_slice(&[0xfd, 0x7b, 0xbf, 0xa9]);

    let names: &[u8] = b"\0.text\0.shstrtab\0";
    image[shstrtab_offset..shstrtab_offset + names.len()].copy_from_slice(names);

    let text_header = shoff + 64;
    write_u32(&mut image, text_header, 1);
    write_u32(&mut image, text_header + 4, 1); // SHT_PROGBITS
    write_u64(&mut image, text_header + 8, 0x6); // ALLOC | EXECINSTR
    write_u64(&mut image, text_header + 16, text_address);
    write_u64(&mut image, text_header + 24, text_offset as u64);
    write_u64(&mut image, text_header + 32, TEXT as u64);
    write_u64(&mut image, text_header + 48, 16);

    let strtab_header = shoff + 128;
    write_u32(&mut image, strtab_header, 7);
    write_u32(&mut image, strtab_header + 4, 3); // SHT_STRTAB
    write_u64(&mut image, strtab_header + 24, shstrtab_offset as u64);
    write_u64(&mut image, strtab_header + 32, names.len() as u64);

    image
}

#[test]
fn the_load_base_is_the_first_segment_even_when_the_first_section_is_higher() {
    // The 26.60 layout: `PT_LOAD` at 0, `.text` far above it.
    let image = elf_with_text_at(0x6834bc0, Some(0));

    let map = build_section_map(&image).expect("section map");

    assert_eq!(map.image_base, 0);
    assert_eq!(map.text.address, 0x6834bc0);
}

#[test]
fn a_library_without_program_headers_falls_back_to_the_first_section() {
    let image = elf_with_text_at(0x1000, None);

    let map = build_section_map(&image).expect("section map");

    assert_eq!(map.image_base, 0x1000);
}

#[test]
fn a_shared_object_linked_at_a_non_zero_base_keeps_that_base() {
    let image = elf_with_text_at(0x5000, Some(0x4000));

    let map = build_section_map(&image).expect("section map");

    assert_eq!(map.image_base, 0x4000);
}
