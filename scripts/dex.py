#!/usr/bin/env python3
"""A minimal DEX reader, just enough to prove a class survived R8.

`verify_release.py` needs to know whether a class is still instantiable after
shrinking: present, not abstract, and carrying a constructor. That is a
question about the class-definition table and the class-data items, so this
module reads exactly those tables and nothing else.

Only the header, the string, type, method and class-definition tables and the
class-data items are parsed. Code, annotations and the map list are ignored.

Raises:
    DexError: if the buffer is not a DEX file or is truncated.
"""

from __future__ import annotations

import struct
from dataclasses import dataclass

# Header field offsets, all little-endian 32-bit unless noted.
_MAGIC = b"dex\n"
_STRING_IDS_SIZE = 0x38
_STRING_IDS_OFF = 0x3C
_TYPE_IDS_SIZE = 0x40
_TYPE_IDS_OFF = 0x44
_METHOD_IDS_SIZE = 0x58
_METHOD_IDS_OFF = 0x5C
_CLASS_DEFS_SIZE = 0x60
_CLASS_DEFS_OFF = 0x64

_HEADER_SIZE = 0x70

# Class access flags.
ACC_ABSTRACT = 0x0400
ACC_INTERFACE = 0x0200
ACC_ENUM = 0x4000

_CONSTRUCTOR = "<init>"


class DexError(RuntimeError):
    """Raised when the buffer is not a readable DEX file."""


@dataclass(frozen=True)
class DexClass:
    """One class-definition item, with the names of its declared methods.

    Attributes:
        descriptor: type descriptor, for example ``Lcom/example/Thing;``.
        access_flags: raw ``access_flags`` of the class definition.
        method_names: names of the methods declared by the class.
    """

    descriptor: str
    access_flags: int
    method_names: frozenset[str]

    @property
    def is_abstract(self) -> bool:
        """Whether the class is marked abstract."""
        return bool(self.access_flags & ACC_ABSTRACT)

    @property
    def is_interface(self) -> bool:
        """Whether the class is an interface."""
        return bool(self.access_flags & ACC_INTERFACE)

    @property
    def is_enum(self) -> bool:
        """Whether the class is an enum."""
        return bool(self.access_flags & ACC_ENUM)

    @property
    def has_constructor(self) -> bool:
        """Whether the class declares a constructor."""
        return _CONSTRUCTOR in self.method_names


class DexFile:
    """The class table of one ``classes.dex``."""

    def __init__(self, data: bytes) -> None:
        """Parse the class table out of ``data``.

        Args:
            data: the raw contents of a ``classes.dex`` entry.

        Raises:
            DexError: if the buffer is too small, has the wrong magic, or is
                truncated part-way through a table.
        """
        self._data = data
        if len(data) < _HEADER_SIZE:
            raise DexError("the dex file is shorter than its header")
        if not data.startswith(_MAGIC):
            raise DexError("the file does not start with the dex magic")

        self._strings = self._read_strings()
        self._types = self._read_types()
        self._methods = self._read_methods()
        self.classes = self._read_classes()

    def find(self, descriptor: str) -> DexClass | None:
        """Return the class with ``descriptor``, or ``None`` when absent.

        Args:
            descriptor: type descriptor, for example ``Lcom/example/Thing;``.

        Returns:
            The matching class, or ``None`` when the dex does not define it.
        """
        return next((entry for entry in self.classes if entry.descriptor == descriptor), None)

    def in_package(self, package: str) -> list[DexClass]:
        """Return every class whose descriptor sits directly in ``package``.

        Args:
            package: dotted package name, for example ``com.example.data``.

        Returns:
            The matching classes, in definition order.
        """
        prefix = "L" + package.replace(".", "/") + "/"
        return [
            entry
            for entry in self.classes
            if entry.descriptor.startswith(prefix) and entry.descriptor.endswith(";")
        ]

    def _u32(self, offset: int) -> int:
        if offset + 4 > len(self._data):
            raise DexError(f"a 32-bit field at {offset:#x} runs past the end of the file")
        return struct.unpack_from("<I", self._data, offset)[0]

    def _uleb128(self, offset: int) -> tuple[int, int]:
        """Read an unsigned LEB128 value, returning it and the next offset."""
        result = 0
        shift = 0
        while True:
            if offset >= len(self._data):
                raise DexError("a uleb128 value runs past the end of the file")
            byte = self._data[offset]
            offset += 1
            result |= (byte & 0x7F) << shift
            if not byte & 0x80:
                return result, offset
            shift += 7
            if shift > 35:
                raise DexError("a uleb128 value is longer than five bytes")

    def _string_at(self, index: int) -> str:
        if index < 0 or index >= len(self._strings):
            raise DexError(f"string index {index} is out of range")
        return self._strings[index]

    def _read_strings(self) -> list[str]:
        count = self._u32(_STRING_IDS_SIZE)
        offset = self._u32(_STRING_IDS_OFF)
        strings: list[str] = []
        for index in range(count):
            start = self._u32(offset + index * 4)
            _, cursor = self._uleb128(start)
            end = self._data.find(b"\x00", cursor)
            if end < 0:
                raise DexError("a string is not null-terminated")
            # MUTF-8 differs from UTF-8 only in its encoding of NUL and
            # surrogates, neither of which appears in a class descriptor.
            strings.append(self._data[cursor:end].decode("utf-8", errors="replace"))
        return strings

    def _read_types(self) -> list[str]:
        count = self._u32(_TYPE_IDS_SIZE)
        offset = self._u32(_TYPE_IDS_OFF)
        return [self._string_at(self._u32(offset + index * 4)) for index in range(count)]

    def _read_methods(self) -> list[tuple[str, str]]:
        """Return ``(class descriptor, method name)`` for every method id."""
        count = self._u32(_METHOD_IDS_SIZE)
        offset = self._u32(_METHOD_IDS_OFF)
        methods: list[tuple[str, str]] = []
        for index in range(count):
            entry = offset + index * 8
            class_index = struct.unpack_from("<H", self._data, entry)[0]
            name_index = self._u32(entry + 4)
            methods.append((self._type_at(class_index), self._string_at(name_index)))
        return methods

    def _type_at(self, index: int) -> str:
        if index < 0 or index >= len(self._types):
            raise DexError(f"type index {index} is out of range")
        return self._types[index]

    def _read_classes(self) -> list[DexClass]:
        count = self._u32(_CLASS_DEFS_SIZE)
        offset = self._u32(_CLASS_DEFS_OFF)
        classes: list[DexClass] = []
        for index in range(count):
            entry = offset + index * 32
            class_index = self._u32(entry)
            access_flags = self._u32(entry + 4)
            class_data_off = self._u32(entry + 24)
            classes.append(
                DexClass(
                    descriptor=self._type_at(class_index),
                    access_flags=access_flags,
                    method_names=self._read_class_methods(class_data_off),
                )
            )
        return classes

    def _read_class_methods(self, class_data_off: int) -> frozenset[str]:
        """Return the names of the methods declared in a class-data item."""
        if class_data_off == 0:
            return frozenset()

        cursor = class_data_off
        static_fields, cursor = self._uleb128(cursor)
        instance_fields, cursor = self._uleb128(cursor)
        direct_methods, cursor = self._uleb128(cursor)
        virtual_methods, cursor = self._uleb128(cursor)

        for _ in range(static_fields + instance_fields):
            _, cursor = self._uleb128(cursor)
            _, cursor = self._uleb128(cursor)

        names: set[str] = set()
        # Direct and virtual methods are separate lists; the first entry of each
        # is an absolute index and the rest are deltas from the previous one.
        for count in (direct_methods, virtual_methods):
            index = 0
            for position in range(count):
                method_diff, cursor = self._uleb128(cursor)
                _, cursor = self._uleb128(cursor)
                _, cursor = self._uleb128(cursor)
                index = method_diff if position == 0 else index + method_diff
                if index < 0 or index >= len(self._methods):
                    raise DexError(f"method index {index} is out of range")
                names.add(self._methods[index][1])
        return frozenset(names)
