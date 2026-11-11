//! Function prologue verification with Capstone.
//!
//! A signature match only says "these bytes occur here". It says nothing about
//! whether the position is the *start of a function*. AArch64 has a very
//! regular calling convention, so the first instruction of a compiled function
//! is almost always one of a handful of stack-frame setup forms:
//!
//! ```text
//! stp x29, x30, [sp, #-16]!   ; frame push
//! sub sp, sp, #0x40           ; frame allocation
//! mov x29, sp                 ; frame pointer
//! pacibsp                     ; pointer authentication prologue
//! stp x19, x20, [sp, #-32]!   ; callee-saved register save
//! ```
//!
//! The first instruction is decoded and checked against those forms. A match
//! whose bytes decode to something else is discarded, which removes the false
//! positives that a byte pattern alone always produces.

use capstone::arch::arm64::{ArchMode, Arm64Insn, Arm64OperandType, Arm64Reg};
use capstone::arch::{BuildsCapstone, BuildsCapstoneEndian};
use capstone::{Capstone, Endian, Insn};
use serde::Serialize;

use crate::error::{CoreError, CoreResult};

/// Number of bytes decoded after a match.
///
/// Four instructions is enough to see the frame push and, when present, the
/// allocation and frame-pointer setup that follow it.
pub const PROLOGUE_WINDOW_BYTES: usize = 16;

/// AArch64 `x29`, the frame pointer.
const REG_FRAME_POINTER: u32 = Arm64Reg::ARM64_REG_FP;

/// AArch64 `x30`, the link register.
const REG_LINK_REGISTER: u32 = Arm64Reg::ARM64_REG_LR;

/// AArch64 `sp`, the stack pointer.
const REG_STACK_POINTER: u32 = Arm64Reg::ARM64_REG_SP;

// Capstone's register numbers are part of the disassembler's ABI: `stp x29, x30`
// decodes with `reg == ARM64_REG_FP`/`ARM64_REG_LR`. Pinning the numbers here
// turns a Capstone upgrade that renumbers them into a build failure instead of
// a silently wrong prologue check.
const _: () = assert!(REG_FRAME_POINTER == 2);
const _: () = assert!(REG_LINK_REGISTER == 3);
const _: () = assert!(REG_STACK_POINTER == 5);

/// Why a candidate was accepted or rejected.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum PrologueKind {
    /// `stp x29, x30, [sp, #-imm]!` — the canonical frame push.
    FramePush,
    /// `stp <any two regs>, [sp, #-imm]!` — a callee-saved register save.
    RegisterSave,
    /// `sub sp, sp, #imm` — a frame allocation with no frame push.
    StackAllocation,
    /// `mov x29, sp` — a frame pointer set on its own.
    FramePointerSetup,
    /// `pacibsp` or `paciasp` — a pointer-authentication prologue.
    PointerAuthentication,
    /// `stp x29, x30, [sp, #imm]` — a frame push with a positive offset.
    OffsetFramePush,
    /// `bti c` / `bti j` / `bti jc` — a branch-target identification landing pad.
    BranchTargetLanding,
    /// The first instruction was not recognisable as a prologue.
    NotAPrologue,
}

/// A verified prologue.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct PrologueVerdict {
    /// Whether the bytes look like the start of a function.
    pub accepted: bool,
    /// Which form was recognised.
    pub kind: PrologueKind,
    /// The first instruction, rendered by Capstone.
    pub instruction: String,
    /// Number of instructions decoded inside the window.
    pub decoded: usize,
}

/// Capstone handle kept alive for the duration of a verification pass.
///
/// Building the handle allocates inside Capstone, so it is created once per
/// scan rather than per candidate. Dropping it frees the handle.
pub struct PrologueVerifier {
    capstone: Capstone,
}

impl PrologueVerifier {
    /// Creates a verifier for 64-bit little-endian ARM.
    ///
    /// # Errors
    ///
    /// Returns [`CoreError::InvalidElf`] when Capstone cannot be initialised,
    /// which would indicate a broken build rather than bad input.
    pub fn new() -> CoreResult<Self> {
        let capstone = Capstone::new()
            .arm64()
            .mode(ArchMode::Arm)
            .endian(Endian::Little)
            .detail(true)
            .build()
            .map_err(|error| {
                CoreError::InvalidElf(format!("cannot initialise the disassembler: {error}"))
            })?;
        Ok(Self { capstone })
    }

    /// Decodes up to [`PROLOGUE_WINDOW_BYTES`] bytes at `offset` and judges them.
    ///
    /// # Returns
    ///
    /// A verdict; malformed bytes are reported as [`PrologueKind::NotAPrologue`]
    /// rather than as an error, because a bad candidate is expected input.
    pub fn verify(&self, text: &[u8], offset: usize) -> PrologueVerdict {
        let end = offset.saturating_add(PROLOGUE_WINDOW_BYTES).min(text.len());
        if offset >= end {
            return PrologueVerdict {
                accepted: false,
                kind: PrologueKind::NotAPrologue,
                instruction: String::new(),
                decoded: 0,
            };
        }

        let window = &text[offset..end];
        let instructions = match self.capstone.disasm_all(window, 0) {
            Ok(instructions) => instructions,
            Err(_) => {
                return PrologueVerdict {
                    accepted: false,
                    kind: PrologueKind::NotAPrologue,
                    instruction: String::new(),
                    decoded: 0,
                };
            }
        };

        let Some(first) = instructions.iter().next() else {
            return PrologueVerdict {
                accepted: false,
                kind: PrologueKind::NotAPrologue,
                instruction: String::new(),
                decoded: 0,
            };
        };

        let instruction = render(first);
        let kind = self.classify(first, &instructions);
        PrologueVerdict {
            accepted: kind != PrologueKind::NotAPrologue,
            kind,
            instruction,
            decoded: instructions.len(),
        }
    }

    /// Decides which prologue form, if any, the window contains.
    fn classify(
        &self,
        first: &Insn<'_>,
        instructions: &capstone::Instructions<'_>,
    ) -> PrologueKind {
        let id = first.id();

        match id.0 {
            value
                if value == Arm64Insn::ARM64_INS_PACIBSP as u32
                    || value == Arm64Insn::ARM64_INS_PACIASP as u32 =>
            {
                PrologueKind::PointerAuthentication
            }
            value if value == Arm64Insn::ARM64_INS_STP as u32 => self.classify_store_pair(first),
            value if value == Arm64Insn::ARM64_INS_SUB as u32 => {
                self.classify_stack_subtract(first)
            }
            value if value == Arm64Insn::ARM64_INS_MOV as u32 => {
                self.classify_frame_pointer_move(first)
            }
            value if value == Arm64Insn::ARM64_INS_BTI as u32 => PrologueKind::BranchTargetLanding,
            // A `nop` where the linker placed a landing pad, followed by a real
            // prologue, is also a valid function start.
            value if value == Arm64Insn::ARM64_INS_NOP as u32 => {
                self.classify_after_nop(instructions)
            }
            _ => PrologueKind::NotAPrologue,
        }
    }

    /// Classifies an `stp` as a frame push, a register save, or neither.
    fn classify_store_pair(&self, instruction: &Insn<'_>) -> PrologueKind {
        let operands = self.operands(instruction);

        let registers: Vec<u32> = operands
            .iter()
            .filter_map(|operand| match operand {
                Arm64OperandType::Reg(reg) => Some(reg.0 as u32),
                _ => None,
            })
            .collect();
        let memory = operands.iter().find_map(|operand| match operand {
            Arm64OperandType::Mem(mem) => Some((mem.base().0 as u32, mem.disp())),
            _ => None,
        });

        let Some((base, displacement)) = memory else {
            return PrologueKind::NotAPrologue;
        };
        if base != REG_STACK_POINTER {
            return PrologueKind::NotAPrologue;
        }

        let pushes_frame =
            registers.contains(&REG_FRAME_POINTER) && registers.contains(&REG_LINK_REGISTER);

        if pushes_frame && displacement < 0 {
            PrologueKind::FramePush
        } else if pushes_frame {
            // A positive offset stores *into* an existing frame, which is a
            // body instruction rather than a prologue.
            PrologueKind::NotAPrologue
        } else if displacement < 0 && registers.len() == 2 {
            PrologueKind::RegisterSave
        } else {
            PrologueKind::NotAPrologue
        }
    }

    /// Classifies a `sub` as a stack allocation when it targets `sp` twice.
    fn classify_stack_subtract(&self, instruction: &Insn<'_>) -> PrologueKind {
        let registers: Vec<u32> = self
            .operands(instruction)
            .into_iter()
            .filter_map(|operand| match operand {
                Arm64OperandType::Reg(reg) => Some(reg.0 as u32),
                _ => None,
            })
            .collect();

        if registers.len() >= 2
            && registers[0] == REG_STACK_POINTER
            && registers[1] == REG_STACK_POINTER
        {
            PrologueKind::StackAllocation
        } else {
            PrologueKind::NotAPrologue
        }
    }

    /// Classifies a `mov` as a frame-pointer setup when it moves `sp` into `x29`.
    fn classify_frame_pointer_move(&self, instruction: &Insn<'_>) -> PrologueKind {
        let registers: Vec<u32> = self
            .operands(instruction)
            .into_iter()
            .filter_map(|operand| match operand {
                Arm64OperandType::Reg(reg) => Some(reg.0 as u32),
                _ => None,
            })
            .collect();

        if registers.len() >= 2
            && registers[0] == REG_FRAME_POINTER
            && registers[1] == REG_STACK_POINTER
        {
            PrologueKind::FramePointerSetup
        } else {
            PrologueKind::NotAPrologue
        }
    }

    /// Looks past a leading `nop` for a recognisable prologue.
    fn classify_after_nop(&self, instructions: &capstone::Instructions<'_>) -> PrologueKind {
        let Some(second) = instructions.get(1) else {
            return PrologueKind::NotAPrologue;
        };
        let kind = self.classify(second, instructions);
        if kind == PrologueKind::NotAPrologue {
            PrologueKind::NotAPrologue
        } else {
            // The leading `nop` is a landing pad; the function still starts here.
            PrologueKind::BranchTargetLanding
        }
    }

    /// Returns the ARM64 operand types of an instruction, in order.
    ///
    /// The architecture-independent view is used so no lifetime has to be tied
    /// to the detail structure, which is created and dropped inside this call.
    fn operands(&self, instruction: &Insn<'_>) -> Vec<Arm64OperandType> {
        let Ok(detail) = self.capstone.insn_detail(instruction) else {
            return Vec::new();
        };
        // Only the arm64 backend is compiled in, so every operand is an
        // Arm64Operand and no fallback arm is needed.
        detail
            .arch_detail()
            .operands()
            .into_iter()
            .map(|operand| match operand {
                capstone::arch::ArchOperand::Arm64Operand(operand) => operand.op_type,
            })
            .collect()
    }
}

/// Renders an instruction as `mnemonic operands`.
fn render(instruction: &Insn<'_>) -> String {
    match (instruction.mnemonic(), instruction.op_str()) {
        (Some(mnemonic), Some(operands)) if !operands.is_empty() => {
            format!("{mnemonic} {operands}")
        }
        (Some(mnemonic), _) => mnemonic.to_string(),
        _ => String::new(),
    }
}

/// Whether a verdict kind means "this is a function start".
pub fn is_prologue(kind: PrologueKind) -> bool {
    kind != PrologueKind::NotAPrologue
}

/// Machine code used by the tests: `stp x29, x30, [sp, #-16]!`.
pub const PROLOGUE_FRAME_PUSH: [u8; 4] = [0xfd, 0x7b, 0xbf, 0xa9];

/// Machine code used by the tests: `ret`.
pub const PROLOGUE_RET: [u8; 4] = [0xc0, 0x03, 0x5f, 0xd6];

/// Machine code used by the tests: `mov x0, x1`.
pub const NOT_A_PROLOGUE_MOV: [u8; 4] = [0xe0, 0x03, 0x01, 0xaa];

#[cfg(test)]
mod tests {
    use super::*;

    fn verifier() -> PrologueVerifier {
        PrologueVerifier::new().expect("capstone must initialise")
    }

    #[test]
    fn a_frame_push_is_accepted() {
        let verdict = verifier().verify(&PROLOGUE_FRAME_PUSH, 0);
        assert!(verdict.accepted, "{}", verdict.instruction);
        assert_eq!(verdict.kind, PrologueKind::FramePush);
        assert!(
            verdict.instruction.contains("stp"),
            "{}",
            verdict.instruction
        );
    }

    #[test]
    fn a_stack_allocation_is_accepted() {
        // `sub sp, sp, #0x40` is 0xD10043FF little-endian.
        let code = [0xff, 0x43, 0x00, 0xd1];
        let verdict = verifier().verify(&code, 0);
        assert!(verdict.accepted, "{}", verdict.instruction);
        assert_eq!(verdict.kind, PrologueKind::StackAllocation);
    }

    #[test]
    fn a_frame_pointer_move_is_accepted() {
        // `mov x29, sp` is 0x910003FD little-endian.
        let code = [0xfd, 0x03, 0x00, 0x91];
        let verdict = verifier().verify(&code, 0);
        assert!(verdict.accepted, "{}", verdict.instruction);
        assert_eq!(verdict.kind, PrologueKind::FramePointerSetup);
    }

    #[test]
    fn a_pointer_authentication_prologue_is_accepted() {
        // `pacibsp` is 0xD503237F little-endian.
        let code = [0x7f, 0x23, 0x03, 0xd5];
        let verdict = verifier().verify(&code, 0);
        assert!(verdict.accepted, "{}", verdict.instruction);
        assert_eq!(verdict.kind, PrologueKind::PointerAuthentication);
    }

    #[test]
    fn an_ordinary_register_move_is_rejected() {
        let verdict = verifier().verify(&NOT_A_PROLOGUE_MOV, 0);
        assert!(!verdict.accepted, "{}", verdict.instruction);
        assert_eq!(verdict.kind, PrologueKind::NotAPrologue);
    }

    #[test]
    fn a_return_is_rejected() {
        let verdict = verifier().verify(&PROLOGUE_RET, 0);
        assert!(!verdict.accepted, "{}", verdict.instruction);
    }

    #[test]
    fn garbage_bytes_are_rejected_without_panicking() {
        let garbage = [0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff];
        let verdict = verifier().verify(&garbage, 0);
        assert!(!verdict.accepted);
    }

    #[test]
    fn an_offset_past_the_end_is_rejected() {
        let verdict = verifier().verify(&PROLOGUE_FRAME_PUSH, 64);
        assert!(!verdict.accepted);
        assert_eq!(verdict.decoded, 0);
    }

    #[test]
    fn a_truncated_window_still_decodes_what_is_there() {
        // Only the first instruction is present.
        let verdict = verifier().verify(&PROLOGUE_FRAME_PUSH, 0);
        assert!(verdict.accepted);
        assert_eq!(verdict.decoded, 1);
    }

    #[test]
    fn a_nop_landing_pad_before_a_frame_push_is_accepted() {
        // `nop` (0xD503201F) then `stp x29, x30, [sp, #-16]!`
        let mut code = Vec::new();
        code.extend_from_slice(&[0x1f, 0x20, 0x03, 0xd5]);
        code.extend_from_slice(&PROLOGUE_FRAME_PUSH);
        let verdict = verifier().verify(&code, 0);
        assert!(verdict.accepted, "{}", verdict.instruction);
        assert_eq!(verdict.kind, PrologueKind::BranchTargetLanding);
    }

    #[test]
    fn a_store_pair_into_an_existing_frame_is_rejected() {
        // `stp x29, x30, [sp, #16]` — a positive offset, so not a prologue.
        // Encoding: 0xA9017BFD little-endian.
        let code = [0xfd, 0x7b, 0x01, 0xa9];
        let verdict = verifier().verify(&code, 0);
        assert!(!verdict.accepted, "{}", verdict.instruction);
    }

    #[test]
    fn every_recognised_kind_counts_as_a_prologue() {
        assert!(is_prologue(PrologueKind::FramePush));
        assert!(is_prologue(PrologueKind::RegisterSave));
        assert!(is_prologue(PrologueKind::StackAllocation));
        assert!(is_prologue(PrologueKind::FramePointerSetup));
        assert!(is_prologue(PrologueKind::PointerAuthentication));
        assert!(is_prologue(PrologueKind::BranchTargetLanding));
        assert!(!is_prologue(PrologueKind::NotAPrologue));
    }
}
