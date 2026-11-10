package com.chimerateam.offsetextractor.data.native

import com.google.gson.annotations.SerializedName

/**
 * One section of the mapped library, resolved to both address spaces.
 *
 * @property name section name from the header string table.
 * @property kind role the section plays for the scanner.
 * @property address virtual address the section is loaded at.
 * @property size size of the section in bytes.
 * @property offset offset of the section body inside the file.
 * @property align required alignment.
 * @property delta `address - offset` for this section.
 * @property allocated whether the section is mapped at run time.
 * @property executable whether the section is executable.
 * @property writable whether the section is writable.
 * @property occupiesFile whether the section occupies file space.
 */
data class SectionInfo(
    val name: String,
    val kind: String,
    val address: Long,
    val size: Long,
    val offset: Long,
    val align: Long,
    val delta: Long,
    val allocated: Boolean,
    val executable: Boolean,
    val writable: Boolean,
    @SerializedName("occupies_file") val occupiesFile: Boolean
) {
    /** Converts a file offset inside this section to a virtual address. */
    fun addressOf(fileOffset: Long): Long? = if (address == 0L) null else fileOffset + delta

    /** Converts a virtual address back to a file offset. */
    fun offsetOf(address: Long): Long? =
        if (this.address == 0L || address < this.address || address >= this.address + size) {
            null
        } else {
            address - delta
        }

    /** Formats [value] as a `0x`-prefixed hexadecimal string. */
    fun hex(value: Long): String = "0x%x".format(value)
}

/**
 * Every section of a mapped library, plus the derived load base.
 *
 * @property imageBase lowest load address of any allocated section.
 * @property text the executable section the scanner walks.
 * @property sections every section, in header order.
 */
data class SectionMap(
    @SerializedName("image_base") val imageBase: Long,
    val text: SectionInfo,
    val sections: List<SectionInfo>
) {
    /** Returns the first section with [name], or `null` when it is absent. */
    fun find(name: String): SectionInfo? = sections.firstOrNull { it.name == name }
}

/** JSON payload wrapper matching the engine's `SectionMap` serialisation. */
internal data class SectionMapPayload(
    @SerializedName("image_base") val imageBase: Long?,
    val text: SectionInfo?,
    val sections: List<SectionInfo>?
)
