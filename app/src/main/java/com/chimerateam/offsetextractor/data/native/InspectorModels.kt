package com.chimerateam.offsetextractor.data.native

/**
 * One instruction decoded by the engine's ARM64 disassembler.
 *
 * @property address virtual address of the instruction.
 * @property bytes the four instruction bytes, hex-separated.
 * @property mnemonic the AArch64 mnemonic.
 * @property operands the formatted operand list.
 * @property annotation the call target, tail target or data reference the
 *   instruction builds, when it builds one.
 */
data class DisassembledInstruction(
    val address: Long,
    val bytes: String,
    val mnemonic: String,
    val operands: String,
    val annotation: String?
)

/**
 * A decoded `.text` range.
 *
 * @property address virtual address the range starts at.
 * @property instructions the decoded instructions in address order.
 */
data class Disassembly(
    val address: Long,
    val instructions: List<DisassembledInstruction>
)

/**
 * A vtable whose slot array contains the inspected address.
 *
 * @property className demangled class name.
 * @property vtable virtual address of the vtable.
 * @property slots the slot indices that point at the inspected address.
 * @property baseClasses the direct base classes declared by the class RTTI.
 */
data class InspectedVtable(
    val className: String,
    val vtable: Long,
    val slots: List<Int>,
    val baseClasses: List<String>
)

/**
 * Everything the engine knows about one address.
 *
 * @property address the inspected address.
 * @property functionStart start of the containing function, when one contains
 *   the address.
 * @property functionSize size of that function in bytes.
 * @property firstCall the first direct call of the function, rendered as
 *   `target@site`.
 * @property callers functions that call the inspected address.
 * @property callees functions the inspected function calls.
 * @property dataRefs addresses the function materialises.
 * @property strings printable strings reachable from those references.
 * @property vtables vtables that hold the inspected address as a slot.
 */
data class AddressInspection(
    val address: Long,
    val functionStart: Long?,
    val functionSize: Long,
    val firstCall: String?,
    val callers: List<Long>,
    val callees: List<Long>,
    val dataRefs: List<Long>,
    val strings: List<String>,
    val vtables: List<InspectedVtable>
)

/**
 * State of the right-hand inspector pane.
 *
 * @property inspection the loaded inspection, when one succeeded.
 * @property disassembly the loaded disassembly, when one succeeded.
 * @property error the failure to show, when loading failed.
 */
data class InspectorState(
    val loading: Boolean = false,
    val address: Long? = null,
    val inspection: AddressInspection? = null,
    val disassembly: Disassembly? = null,
    val error: String? = null
)
