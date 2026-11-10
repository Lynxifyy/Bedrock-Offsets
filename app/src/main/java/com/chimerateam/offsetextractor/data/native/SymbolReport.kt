package com.chimerateam.offsetextractor.data.native

import com.google.gson.annotations.SerializedName

/**
 * Identity of a native library, independent of its file name.
 *
 * @property buildId lower-case hex build identifier.
 * @property source where the identifier came from: `note` or `text_hash`.
 * @property textSize size of `.text`, the input to the fallback hash; 0 when
 *   the identifier came from the note.
 */
data class BuildIdentity(
    @SerializedName("build_id") val buildId: String,
    val source: String,
    @SerializedName("text_size") val textSize: Long
) {
    /** Whether the identifier was derived from a `.text` hash. */
    val isDerived: Boolean get() = source == "text_hash"
}

/**
 * An exported JNI function and its address.
 *
 * @property name symbol name as it appears in `.dynstr`.
 * @property address virtual address of the symbol.
 * @property addressHex the address rendered as `0x`-prefixed hex.
 * @property size size recorded for the symbol.
 * @property binding `st_info` binding nibble; 1 is global, 2 is weak.
 */
data class ExportedSymbol(
    val name: String,
    val address: Long,
    @SerializedName("address_hex") val addressHex: String,
    val size: Long,
    val binding: Int
)

/**
 * Every dynamic symbol of a library, plus the names exported to the JVM.
 *
 * @property total entries in `.dynsym`, including undefined ones.
 * @property defined defined global or weak symbols.
 * @property jni symbols whose name starts with `Java_`.
 * @property truncated whether the engine capped the JNI list.
 */
data class SymbolTable(
    val total: Int,
    val defined: Int,
    val jni: List<ExportedSymbol>,
    val truncated: Boolean
)

/**
 * Build identifier and symbol table of one library.
 *
 * @property identity identity of the library.
 * @property symbols symbols exported to the JVM.
 */
data class SymbolReport(
    val identity: BuildIdentity,
    val symbols: SymbolTable
) {
    /** Returns the symbol named [name], or `null` when it is not exported. */
    fun symbol(name: String): ExportedSymbol? = symbols.jni.firstOrNull { it.name == name }
}

/** JSON payload wrapper matching the engine's `SymbolReport` serialisation. */
internal data class SymbolReportPayload(
    val identity: BuildIdentity?,
    val symbols: SymbolTable?
)
