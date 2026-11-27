#!/usr/bin/env python3
"""Generates an AArch64 shared object shaped like a real Minecraft Bedrock library.

The engine's signature stage was only ever measured on a 1,591-function
`libc++_shared.so`, which is 500 times smaller than the 791,952 functions a real
`libminecraftpe.so` has. This script builds a target at that scale so the
performance work can be measured instead of guessed.

What it plants, and why:

* `--functions 800000` functions, each with an `.eh_frame` FDE, so the engine's
  function enumeration sees exactly that many.
* thunk families: `--thunk-families 12799` families of 8 *byte-identical*
  members. This is the `std::function::__func` pathology: eight slots of the
  same wrapper, so eight functions with the same body and no way to tell them
  apart by bytes.
* `--template-group 1406` instantiations of one identical body, the
  `entt::basic_storage` shape.
* 300,000 small functions (4 to 6 instructions), which is where the real
  library's unnamed tail lives.
* every function carries a common `stp x29, x30, [sp, #-16]!` prologue, and the
  medium ones carry `bl`, `adrp`+`add`, `ldr`/`str` with struct offsets and
  `movz`/`movk` constants, so the masker has something real to work on.
* `.rodata` strings, `.data.rel.ro` vtables with real Itanium `type_info`
  objects, and `.dynsym` names for a share of the functions.

`--layout 1` produces the *same program* at different addresses: the functions
are reordered, so every `bl` offset and every `adrp` page changes. A signature
that keeps those immediates dies between the two builds, which is exactly the
cross-version test the engine was missing.

Usage:

    python3 scripts/scale_fixture.py --out /tmp/scale.so --functions 800000
    python3 scripts/scale_fixture.py --out /tmp/scale_b.so --functions 800000 --layout 1
    python3 scripts/scale_fixture.py --out /tmp/small.so --functions 4000 --small
"""

from __future__ import annotations

import argparse
import hashlib
import random
import struct
import sys

EM_AARCH64 = 183

# --------------------------------------------------------------------------
# instruction encoders
# --------------------------------------------------------------------------


def stp_fp_lr() -> int:
    return 0xA9BF7BFD  # stp x29, x30, [sp, #-16]!


def mov_fp_sp() -> int:
    return 0x910003FD  # mov x29, sp


def ldp_fp_lr() -> int:
    return 0xA8C17BFD  # ldp x29, x30, [sp], #16


def ret() -> int:
    return 0xD65F03C0


def sub_sp(size: int) -> int:
    return 0xD10003FF | ((size // 16) & 0xFFF) << 10


def bl(offset: int) -> int:
    return 0x94000000 | ((offset >> 2) & 0x3FFFFFF)


def b(offset: int) -> int:
    return 0x14000000 | ((offset >> 2) & 0x3FFFFFF)


def adrp(reg: int, target: int, pc: int) -> int:
    delta = (target & ~0xFFF) - (pc & ~0xFFF)
    pages = delta >> 12
    immlo = pages & 0x3
    immhi = (pages >> 2) & 0x7FFFF
    return 0x90000000 | (immlo << 29) | (immhi << 5) | reg


def add_imm(rd: int, rn: int, imm: int) -> int:
    return 0x91000000 | ((imm & 0xFFF) << 10) | (rn << 5) | rd


def ldr_x(rd: int, rn: int, imm: int) -> int:
    return 0xF9400000 | (((imm // 8) & 0xFFF) << 10) | (rn << 5) | rd


def str_w(rd: int, rn: int, imm: int) -> int:
    return 0xB9000000 | (((imm // 4) & 0xFFF) << 10) | (rn << 5) | rd


def ldr_w(rd: int, rn: int, imm: int) -> int:
    return 0xB9400000 | (((imm // 4) & 0xFFF) << 10) | (rn << 5) | rd


def movz(rd: int, imm: int, shift: int = 0) -> int:
    return 0xD2800000 | ((shift // 16) << 21) | ((imm & 0xFFFF) << 5) | rd


def movk(rd: int, imm: int, shift: int) -> int:
    return 0xF2800000 | ((shift // 16) << 21) | ((imm & 0xFFFF) << 5) | rd


def cbz(rt: int, offset: int) -> int:
    return 0xB4000000 | (((offset >> 2) & 0x7FFFF) << 5) | rt


def cbnz_w(rt: int, offset: int) -> int:
    return 0x35000000 | (((offset >> 2) & 0x7FFFF) << 5) | rt


def blr(rn: int) -> int:
    return 0xD63F0000 | (rn << 5)


def add_reg(rd: int, rn: int, rm: int) -> int:
    return 0x8B000000 | (rm << 16) | (rn << 5) | rd


def ldr_literal(rt: int, offset: int) -> int:
    return 0x58000000 | (((offset >> 2) & 0x7FFFF) << 5) | rt


def b_cond(cond: int, offset: int) -> int:
    return 0x54000000 | (((offset >> 2) & 0x7FFFF) << 5) | cond


# --------------------------------------------------------------------------
# logical program model
# --------------------------------------------------------------------------


class Spec:
    """One function, described independently of where it ends up."""

    __slots__ = ("ident", "kind", "words", "size_class")

    def __init__(self, ident: str, kind: str, words: int) -> None:
        self.ident = ident
        self.kind = kind
        self.words = words


def build_specs(
    total: int,
    thunk_families: int,
    thunk_members: int,
    template_group: int,
    small: int,
) -> list[Spec]:
    specs: list[Spec] = []
    specs.append(Spec("entry", "entry", 8))
    # thunk families: every member of a family has the same body
    for family in range(thunk_families):
        for member in range(thunk_members):
            specs.append(Spec(f"thunk{family}_{member}", f"thunk{family}", 8))
    for index in range(template_group):
        specs.append(Spec(f"tpl{index}", "template", 12))
    for index in range(small):
        specs.append(Spec(f"small{index}", f"small{index}", 5))
    while len(specs) < total:
        index = len(specs)
        specs.append(Spec(f"fn{index}", f"fn{index}", 10 + (index % 9)))
    del specs[total:]
    return specs


def body_words(spec: Spec, resolver, pc: int) -> list[int]:
    """The instruction words of one function at a given address.

    The variety matters: after normalisation only the register-only operations
    keep all their bits, so a body built from one template collapses into a
    handful of identical groups and the fixture stops resembling real code.
    """
    rng = random.Random(int.from_bytes(hashlib.sha256(spec.kind.encode()).digest()[:8], "little"))
    words = [stp_fp_lr(), mov_fp_sp()]

    def reg() -> int:
        return rng.randrange(0, 29)

    def body_op() -> int:
        choice = rng.randrange(0, 12)
        rd, rn, rm = reg(), reg(), reg()
        if choice == 0:
            return add_reg(rd, rn, rm)
        if choice == 1:
            return 0xCB000000 | (rm << 16) | (rn << 5) | rd  # sub
        if choice == 2:
            return 0x8A000000 | (rm << 16) | (rn << 5) | rd  # and
        if choice == 3:
            return 0xAA000000 | (rm << 16) | (rn << 5) | rd  # orr
        if choice == 4:
            return 0xCA000000 | (rm << 16) | (rn << 5) | rd  # eor
        if choice == 5:
            shift = rng.randrange(0, 64)
            return 0x8B000000 | (rm << 16) | (shift << 10) | (rn << 5) | rd  # add lsl
        if choice == 6:
            return 0x9B007C00 | (rm << 16) | (rn << 5) | rd  # mul
        if choice == 7:
            return 0x9A800000 | (rm << 16) | (rn << 5) | rd  # csel
        if choice == 8:
            return 0xEB00001F | (rm << 16) | (rn << 5)  # cmp
        if choice == 9:
            return 0xAA2003E0 | (rm << 16) | rd  # mvn
        if choice == 10:
            return 0xDAC00000 | (rm << 16) | (rn << 5) | rd  # rbit
        return 0xF8606800 | (rm << 16) | (rn << 5) | rd  # ldr x, [x, x]

    def masked_op() -> int:
        choice = rng.randrange(0, 4)
        if choice == 0:
            return bl(resolver.call(rng.randrange(1, 6), pc))
        if choice == 1:
            return adrp(9, resolver.data(rng.randrange(0, 64)), pc)
        if choice == 2:
            return ldr_w(reg(), reg(), 0x8 * rng.randrange(0, 16))
        return str_w(reg(), reg(), 0x8 * rng.randrange(0, 16))

    if spec.kind == "entry":
        words += [
            adrp(8, resolver.data(3), pc),
            add_imm(8, 8, 0x20),
            bl(resolver.call(1, pc)),
            movz(0, 0),
        ]
    elif spec.kind.startswith("thunk"):
        # Byte-identical across a family: same registers, same callee.
        words += [
            ldr_x(8, 0, 8),
            bl(resolver.call(2, pc)),
            ldr_w(0, 8, 0x10),
        ]
    elif spec.kind == "template":
        words += [
            ldr_x(9, 0, 0),
            ldr_w(10, 9, 0x18),
            cbz(10, 0x20),
            bl(resolver.call(3, pc)),
        ]
    else:
        count = spec.words
        for index in range(count):
            if index % 3 == 2:
                words.append(masked_op())
            else:
                words.append(body_op())
    words += [ldp_fp_lr(), ret()]
    return words


# --------------------------------------------------------------------------
# ELF assembly
# --------------------------------------------------------------------------


def align(value: int, to: int) -> int:
    return (value + to - 1) // to * to


class Section:
    def __init__(self, name: str, sh_type: int, flags: int, data: bytes = b"") -> None:
        self.name = name
        self.type = sh_type
        self.flags = flags
        self.data = bytearray(data)
        self.addr = 0
        self.offset = 0
        self.link = 0
        self.info = 0
        self.alignment = 8
        self.entsize = 0


def uleb(value: int) -> bytes:
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def sleb(value: int) -> bytes:
    out = bytearray()
    more = True
    while more:
        byte = value & 0x7F
        value >>= 7
        if (value == 0 and not byte & 0x40) or (value == -1 and byte & 0x40):
            more = False
        else:
            byte |= 0x80
        out.append(byte)
    return bytes(out)


def build(args) -> bytes:
    rng = random.Random(args.seed)
    specs = build_specs(
        args.functions, args.thunk_families, args.thunk_members, args.template_group, args.small
    )
    count = len(specs)

    # layout: order the functions in .text. Layout 1 shuffles them, which moves
    # every callee and every page.
    order = list(range(count))
    if args.layout:
        rng.shuffle(order)
    position = [0] * count
    for slot, index in enumerate(order):
        position[index] = slot

    # first pass: size every body with placeholder addresses
    sizes = []
    for spec in specs:
        words = body_words(spec, _Resolver(None, 0, 0), 0)
        sizes.append(len(words) * 4)

    # assign text addresses in the chosen order
    text_base = 0x1000
    addr_of = [0] * count
    cursor = text_base
    for slot, index in enumerate(order):
        addr_of[index] = cursor
        cursor += align(sizes[index], 16)
    text_size = cursor - text_base

    # .rodata strings: one per group of functions, referenced by adrp+add
    strings = [f"minecraft::gameplay::anchor::{i:06d}\0".encode() for i in range(args.strings)]
    rodata = bytearray()
    string_addr = []
    for text in strings:
        string_addr.append(text_base + text_size + len(rodata))
        rodata += text
        while len(rodata) % 8:
            rodata.append(0)
    rodata_addr = text_base + text_size

    # .data.rel.ro: a real Itanium RTTI graph.
    #
    # `__class_type_info` has its own vtable, and every class `type_info` points
    # at it; that is how the engine tells a class `type_info` from any other
    # two-word structure.
    data_addr = align(rodata_addr + len(rodata), 16)
    data = bytearray()

    def place(payload: bytes, alignment: int = 8) -> int:
        while len(data) % alignment:
            data.append(0)
        address = data_addr + len(data)
        data.extend(payload)
        return address

    base_name = b"N10__cxxabiv117__class_type_infoE\0"
    base_name_addr = place(base_name, 1)
    # The base type_info vtable: offset-to-top, its own type_info, two slots.
    base_vtable = place(b"\0" * 32)
    base_type_info = place(struct.pack("<QQ", base_vtable + 16, base_name_addr))
    data[base_vtable - data_addr : base_vtable - data_addr + 16] = struct.pack(
        "<QQ", 0, base_type_info
    )

    class_names = [f"N5Actor{len(f'Item{i}')}Item{i}E" for i in range(args.classes)]
    name_addr = [place(name.encode() + b"\0", 1) for name in class_names]
    type_infos = [
        place(struct.pack("<QQ", base_vtable + 16, name_addr[index]))
        for index in range(args.classes)
    ]
    vtable_addr = []
    for index in range(args.classes):
        slots = 4 + (index % 6)
        members = [addr_of[(index * 13 + slot * 7) % count] for slot in range(slots)]
        vtable_addr.append(
            place(
                struct.pack("<QQ", 0, type_infos[index])
                + b"".join(struct.pack("<Q", member) for member in members)
            )
        )
    data_size = len(data)

    # second pass: encode the bodies with real addresses
    resolver = _Resolver(addr_of, text_base, data_addr)
    text = bytearray()
    for index, spec in enumerate(specs):
        pc = addr_of[index]
        assert pc - text_base == len(text), (pc, len(text))
        words = body_words(spec, resolver, pc)
        encoded = b"".join(struct.pack("<I", word & 0xFFFFFFFF) for word in words)
        encoded = encoded[: sizes[index]]
        text += encoded
        while len(text) < addr_of[index] - text_base + align(sizes[index], 16):
            text += struct.pack("<I", 0xD503201F)  # nop

    # .eh_frame
    eh_addr = align(data_addr + data_size, 16)
    eh = bytearray()
    cie_start = len(eh)
    cie_body = (
        struct.pack("<I", 0)  # CIE id
        + bytes([1])  # version
        + b"zR\0"  # augmentation
        + uleb(1)  # code alignment
        + sleb(-8)  # data alignment
        + uleb(30)  # return register
        + uleb(1)  # augmentation length
        + bytes([0x1B])  # pcrel | sdata4
        + bytes([0x0C, 0x1F, 0x00])  # DW_CFA_def_cfa r31, 0
    )
    while (len(cie_body) + 4) % 8:
        cie_body += b"\0"
    eh += struct.pack("<I", len(cie_body)) + cie_body
    for index, spec in enumerate(specs):
        field_address = eh_addr + len(eh) + 8
        start = addr_of[index]
        fde_body = (
            struct.pack("<i", (len(eh) + 4) - cie_start)
            + struct.pack("<i", start - field_address)
            + struct.pack("<i", sizes[index])
            + b"\x00"  # augmentation length: a `z` CIE gives every FDE one
        )
        # A record must be a multiple of four bytes; the padding is a run of
        # DW_CFA_nop, which every unwinder ignores.
        while len(fde_body) % 4:
            fde_body += b"\x00"
        eh += struct.pack("<I", len(fde_body)) + fde_body

    # .dynsym / .dynstr: name a share of the functions
    dynstr = bytearray(b"\0")
    names: list[tuple[int, int]] = []

    def intern(name: str) -> int:
        offset = len(dynstr)
        dynstr.extend(name.encode() + b"\0")
        return offset

    for index, spec in enumerate(specs):
        if index % 100 >= args.named_percent:
            continue
        names.append((intern(mangled_name(index, spec)), addr_of[index], sizes[index]))
    symbols = [struct.pack("<IBBHQQ", 0, 0, 0, 0, 0, 0)]
    for name_offset, address, size in names:
        symbols.append(struct.pack("<IBBHQQ", name_offset, 0x12, 0, 1, address, size))
    dynsym = b"".join(symbols)

    sections = [
        Section("", 0, 0),
        Section(".text", 1, 0x6, bytes(text)),
        Section(".rodata", 1, 0x2, bytes(rodata)),
        Section(".data.rel.ro", 1, 0x3, bytes(data)),
        Section(".eh_frame", 1, 0x2, bytes(eh)),
        Section(".dynsym", 11, 0x2, dynsym),
        Section(".dynstr", 3, 0x2, bytes(dynstr)),
        Section(".shstrtab", 3, 0, b""),
    ]
    sections[5].link = 6
    sections[5].info = 1
    sections[5].entsize = 24
    sections[5].alignment = 8

    # file layout: allocated sections keep offset == address
    cursor = 0
    for section in sections[1:5]:
        if section.name == ".text":
            section.addr = text_base
            section.offset = text_base
            cursor = text_base + len(section.data)
        elif section.name == ".rodata":
            section.addr = rodata_addr
            section.offset = rodata_addr
            cursor = rodata_addr + len(section.data)
        elif section.name == ".data.rel.ro":
            section.addr = data_addr
            section.offset = data_addr
            cursor = data_addr + len(section.data)
        else:
            section.addr = eh_addr
            section.offset = eh_addr
            cursor = eh_addr + len(section.data)
    cursor = align(cursor, 8)
    for section in sections[5:]:
        section.offset = cursor
        cursor = align(cursor + len(section.data), 8)

    shstr = bytearray(b"\0")
    name_offsets = {}
    for section in sections[1:]:
        name_offsets[section.name] = len(shstr)
        shstr.extend(section.name.encode() + b"\0")
    sections[7].data = bytes(shstr)

    total = cursor + len(shstr) + 64 * len(sections)
    image = bytearray(total)
    for section in sections[1:]:
        if section.name == ".shstrtab":
            continue
        image[section.offset : section.offset + len(section.data)] = section.data
    image[sections[7].offset : sections[7].offset + len(sections[7].data)] = sections[7].data

    # ELF header
    shoff = align(total - 64 * len(sections), 8)
    phoff = 64
    phnum = 3
    ehsize = 64
    shoff = align(ehsize + phnum * 56, 8)
    body_end = sections[7].offset + len(sections[7].data)
    shoff = align(body_end, 8)
    total = shoff + 64 * len(sections)
    image = image[:body_end] + b"\0" * (total - body_end)

    ident = b"\x7fELF" + bytes([2, 1, 1, 0]) + b"\0" * 8
    header = ident + struct.pack(
        "<HHIQQQIHHHHHH",
        3,  # ET_DYN
        EM_AARCH64,
        1,
        0,  # entry
        phoff,
        shoff,
        0,
        ehsize,
        56,
        phnum,
        64,
        len(sections),
        len(sections) - 1,
    )
    image[0:64] = header

    # program headers: one per loadable section so the segment map is honest
    phdrs = b""
    phdrs += struct.pack("<IIQQQQQQ", 1, 0x5, 0, text_base, text_base, len(sections[1].data), len(sections[1].data), 0x1000)
    phdrs += struct.pack("<IIQQQQQQ", 1, 0x4, rodata_addr, rodata_addr, rodata_addr, len(sections[2].data), len(sections[2].data), 0x1000)
    phdrs += struct.pack("<IIQQQQQQ", 1, 0x6, data_addr, data_addr, data_addr, len(sections[3].data), len(sections[3].data), 0x1000)
    image[64 : 64 + len(phdrs)] = phdrs

    # section headers
    for index, section in enumerate(sections):
        name = name_offsets.get(section.name, 0) if index else 0
        image[shoff + 64 * index : shoff + 64 * (index + 1)] = struct.pack(
            "<IIQQQQIIQQ",
            name,
            section.type,
            section.flags,
            section.addr,
            section.offset,
            len(section.data),
            section.link,
            section.info,
            section.alignment,
            section.entsize,
        )
    return bytes(image)


class _Resolver:
    def __init__(self, addr_of, text_base: int, data_addr: int) -> None:
        self.addr_of = addr_of
        self.text_base = text_base
        self.data_addr = data_addr

    def call(self, which: int, pc: int) -> int:
        if not self.addr_of:
            return 0
        target = self.addr_of[(which * 7919) % len(self.addr_of)]
        return target - pc

    def data(self, which: int) -> int:
        return self.data_addr + 8 * (which + 1)


def mangled_name(index: int, spec: Spec) -> str:
    if index % 5 == 0:
        return f"_ZN5Level{len('tick')}tickEv{index}"
    if index % 5 == 1:
        return f"_ZNSt6__ndk18functionIFvvEE6__funcIZN5Level4tickEvE3$_0NS_9allocatorIS6_EEEE{index}"
    if index % 5 == 2:
        return f"_ZN4entt14basic_storageIN3ECS{len('StateVectorComponent')}StateVectorComponentEvE{index}"
    if index % 5 == 3:
        return f"_ZN5Actor{len('getPos')}getPosEv{index}"
    return f"_ZN12_GLOBAL__N_1{len(spec.ident)}{spec.ident}E{index}"


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", required=True)
    parser.add_argument("--functions", type=int, default=800_000)
    parser.add_argument("--thunk-families", type=int, default=12_799)
    parser.add_argument("--thunk-members", type=int, default=8)
    parser.add_argument("--template-group", type=int, default=1_406)
    parser.add_argument("--small", type=int, default=300_000)
    parser.add_argument("--strings", type=int, default=100_000)
    parser.add_argument("--classes", type=int, default=6_386)
    parser.add_argument("--named-percent", type=int, default=60)
    parser.add_argument("--layout", type=int, default=0)
    parser.add_argument("--seed", type=int, default=1)
    args = parser.parse_args(argv)

    image = build(args)
    with open(args.out, "wb") as handle:
        handle.write(image)
    digest = hashlib.sha256(image).hexdigest()
    print(f"wrote {args.out}: {len(image)} bytes, sha256 {digest}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
