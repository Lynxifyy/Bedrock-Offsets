package com.chimerateam.offsetextractor.data.native

import com.google.gson.annotations.SerializedName

/**
 * One resolved RTTI vtable.
 *
 * @property name the full demangled identity, template arguments included.
 * @property baseName [name] with every template argument group removed, for
 *   example `std::__ndk1::__function::__func`. A family label, never an
 *   identity.
 * @property mangled the mangled name as it appears in `.rodata`, which is the
 *   identity and the key of this map.
 * @property typeInfoKind which `type_info` subclass the object instantiates:
 *   `class`, `si_class`, `vmi_class` or `unknown`.
 * @property offsetToTop distance from this subobject to the most-derived
 *   object, as stored in the vtable header.
 * @property vtableAddress virtual address of the first virtual function pointer.
 * @property vtableHex [vtableAddress] rendered as `0x`-prefixed hex.
 * @property vtableOffset offset of the vtable from the image base, which
 *   survives rebasing.
 * @property typeInfoAddress virtual address of the `type_info` object.
 */
data class TypeVTable(
    val name: String,
    @SerializedName("base_name") val baseName: String = "",
    val mangled: String,
    @SerializedName("vtable_address") val vtableAddress: Long,
    @SerializedName("vtable_hex") val vtableHex: String,
    @SerializedName("vtable_offset") val vtableOffset: Long,
    @SerializedName("type_info_address") val typeInfoAddress: Long,
    @SerializedName("type_info_kind") val typeInfoKind: String = "unknown",
    @SerializedName("offset_to_top") val offsetToTop: Long = 0
)

/**
 * Every vtable the engine resolved in one library.
 *
 * @property source how the vtables were found: `relocations` or `direct_scan`.
 * @property typeInfoCandidates number of `type_info` name pointers discovered.
 * @property relocationsExamined number of relocations read from `.rela.dyn`.
 * @property entries resolved vtables keyed by mangled name, one per vtable
 *   group, so no template instantiation or secondary base group collapses
 *   into another row.
 */
data class VtableMap(
    val source: String,
    @SerializedName("type_info_candidates") val typeInfoCandidates: Int,
    @SerializedName("relocations_examined") val relocationsExamined: Int,
    val entries: Map<String, TypeVTable>
) {
    /** Number of resolved vtables. */
    val size: Int get() = entries.size

    /** Whether nothing was resolved. */
    val isEmpty: Boolean get() = entries.isEmpty()

    /** Whether the vtables came from the relocation table. */
    val usedRelocations: Boolean get() = source == SOURCE_RELOCATIONS

    companion object {
        /** The vtables were located through `.rela.dyn`. */
        const val SOURCE_RELOCATIONS = "relocations"

        /** Relocations were unusable, so `.data.rel.ro` was scanned directly. */
        const val SOURCE_DIRECT_SCAN = "direct_scan"

        /** An empty map, used when a library carries no RTTI. */
        val EMPTY = VtableMap(
            source = SOURCE_DIRECT_SCAN,
            typeInfoCandidates = 0,
            relocationsExamined = 0,
            entries = emptyMap()
        )
    }
}

/** JSON payload wrapper matching the engine's `VtableMap` serialisation. */
internal data class VtableMapPayload(
    val source: String?,
    @SerializedName("type_info_candidates") val typeInfoCandidates: Int?,
    @SerializedName("relocations_examined") val relocationsExamined: Int?,
    val entries: Map<String, TypeVTable>?
)
