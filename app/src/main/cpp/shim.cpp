// Native shim for the Rust engine.
//
// The engine is implemented in Rust and imported as a prebuilt shared object by
// CMakeLists.txt. This translation unit exists so the module always has one
// buildable CMake target; it deliberately contains no logic of its own.

namespace offset_core_shim {

/// Reports the ABI revision of this shim.
///
/// Bumped only if the shim ever gains behaviour that callers must detect.
int abi_revision() noexcept {
    return 1;
}

}  // namespace offset_core_shim
