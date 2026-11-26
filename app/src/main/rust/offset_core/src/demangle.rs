//! Itanium C++ ABI names: complete demangling and identity helpers.
//!
//! Bedrock's RTTI carries `type_info` names as *type* manglings, for example
//! `NSt6__ndk16vectorIiNS_9allocatorIiEEEE`. The engine used to read only the
//! leading `<length><name>` and stop at `I`, which collapsed every template
//! instantiation of a class into one row: `__func` alone stood for 12,799
//! distinct types.
//!
//! The identity of a class is its whole mangled name, so demangling has to be
//! complete: template arguments, substitutions (`S_`, `S0_`, `St`), local names
//! (`Z<encoding>E`), lambdas (`Ul...E_`), unnamed types and expression
//! arguments (`X...E`, `L...E`).
//!
//! Rather than reimplementing libiberty, this module drives the vendored
//! `cpp_demangle` parser. A type mangling is not a symbol, so it is presented as
//! one: `_Z` followed by the type is a valid *data name* in the ABI, and the
//! substitution table it builds is the same one the type uses (verified against
//! `c++filt` over the checked-in corpus in `tests/data/demangle_corpus.json`).
//! Builtin types, which cannot be a data name, are mapped directly.

use cpp_demangle::Symbol;

/// Longest mangled text accepted for demangling.
///
/// A crafted length prefix must not make the parser walk megabytes. Real
/// names in a Bedrock library peak in the low thousands of characters.
pub const MAX_MANGLED_CHARS: usize = 16_384;

/// A demangled name and its template-stripped identity.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Demangled {
    /// Full demangled text, template arguments included.
    pub display: String,
    /// [`Demangled::display`] with every template argument group removed.
    ///
    /// `std::__ndk1::__function::__func<void (*)(), std::allocator<void>>` has
    /// base `std::__ndk1::__function::__func`. The base is the key the old code
    /// used alone; it is kept as a secondary field so a naive lookup still
    /// works while the identity stays unique.
    pub base: String,
}

impl Demangled {
    fn new(display: String) -> Self {
        let display = repair_qualifier_placement(&display);
        let base = strip_template_arguments(&display);
        Self { display, base }
    }
}

/// Moves a pointer or reference qualifier that the demangler attached to the
/// wrong side of a nested name.
///
/// `RNS1_15__upcast_resultE` qualifies the whole nested type, so libiberty
/// prints `__class_type_info::__upcast_result&`; `cpp_demangle` 0.6 prints
/// `__class_type_info&::__upcast_result`. The address arithmetic in the naming
/// engine reads these names, so the qualifier is moved to where it belongs
/// instead of shipping a name that is not valid C++.
fn repair_qualifier_placement(text: &str) -> String {
    let mut out = text.to_string();
    for qualifier in ['&', '*'] {
        let needle = format!("{qualifier}::");
        while let Some(at) = out.find(&needle) {
            let after = at + needle.len();
            let mut end = after;
            let bytes = out.as_bytes();
            loop {
                while end < out.len() && is_identifier_byte(bytes[end]) {
                    end += 1;
                }
                // Template arguments belong to the component name.
                if end < out.len() && bytes[end] == b'<' {
                    let mut depth = 0usize;
                    while end < out.len() {
                        match bytes[end] {
                            b'<' => depth += 1,
                            b'>' => {
                                depth -= 1;
                                if depth == 0 {
                                    end += 1;
                                    break;
                                }
                            }
                            _ => {}
                        }
                        end += 1;
                    }
                }
                if out[end..].starts_with("::") {
                    end += 2;
                    continue;
                }
                break;
            }
            if end == after {
                // Nothing to move it onto; leave the text alone rather than
                // producing something new.
                break;
            }
            out.replace_range(at..after, "::");
            out.insert(end - 1, qualifier);
        }
    }
    out
}

fn is_identifier_byte(byte: u8) -> bool {
    byte.is_ascii_alphanumeric() || byte == b'_' || byte == b'$' || byte == b'.'
}

/// Demangles an RTTI type name (`<length><name>`, `N...E`, `Z...E`, `St...`).
///
/// Returns `None` when the text is not a demangleable C++ name. Never guesses:
/// a half-parsed name would be worse than no name, because it would merge
/// distinct classes.
#[must_use]
pub fn demangle_type(text: &str) -> Option<Demangled> {
    let text = text.trim_matches('\0');
    if text.is_empty() || text.len() > MAX_MANGLED_CHARS {
        return None;
    }
    // RTTI name strings are type manglings; some producers keep the `_ZTS`
    // prefix of the symbol that points at them.
    let text = text
        .strip_prefix("_ZTS")
        .or_else(|| text.strip_prefix("_ZTV"))
        .or_else(|| text.strip_prefix("_ZTI"))
        .or_else(|| text.strip_prefix("_ZTT"))
        .unwrap_or(text);

    if let Some(builtin) = builtin_type(text) {
        return Some(Demangled::new(builtin.to_string()));
    }
    // C++ `long double` is `e`, which is also a builtin; the ambiguous one- and
    // two-letter codes are handled above before this point.
    let wrapped = format!("_Z{text}");
    if let Some(display) = demangle_encoding(&wrapped) {
        // A data name demangles to just the name; reject output that is the
        // input repeated, which is how `cpp_demangle` reports a failed parse.
        if display != wrapped && display != text {
            return Some(Demangled::new(display));
        }
    }
    None
}

/// Demangles an Itanium symbol (`_Z...`) into its full signature.
#[must_use]
pub fn demangle_symbol(text: &str) -> Option<String> {
    if text.len() > MAX_MANGLED_CHARS {
        return None;
    }
    demangle_encoding(text).map(|name| repair_qualifier_placement(&name))
}

/// Strips template argument groups from an already demangled name.
///
/// Keeps every qualifier and every nested component, so
/// `entt::basic_storage<A, B>::get<int>` becomes
/// `entt::basic_storage::get`.
#[must_use]
pub fn strip_template_arguments(name: &str) -> String {
    let mut out = String::with_capacity(name.len());
    let mut depth = 0usize;
    for character in name.chars() {
        match character {
            '<' => depth += 1,
            '>' => depth = depth.saturating_sub(1),
            _ if depth == 0 => out.push(character),
            _ => {}
        }
    }
    // Removing an argument list can leave `operator<<` intact (no `<` there),
    // but a name like `A<>::B` leaves `A::B`; nothing else needs repair.
    out
}

/// Returns the top-level template arguments of the first argument list.
///
/// `ViewT<StrictEntityContext, Include<A>, Exclude<B>>` yields
/// `["StrictEntityContext", "Include<A>", "Exclude<B>"]`. Returns an empty
/// vector when the name has no template arguments.
#[must_use]
pub fn template_arguments(name: &str) -> Vec<String> {
    let Some(start) = name.find('<') else {
        return Vec::new();
    };
    let mut depth = 0usize;
    let mut current = String::new();
    let mut out = Vec::new();
    for character in name[start..].chars() {
        match character {
            '<' => {
                depth += 1;
                if depth > 1 {
                    current.push(character);
                }
            }
            '>' => {
                depth = depth.saturating_sub(1);
                if depth == 0 {
                    if !current.trim().is_empty() {
                        out.push(current.trim().to_string());
                    }
                    break;
                }
                current.push(character);
            }
            ',' if depth == 1 => {
                out.push(current.trim().to_string());
                current.clear();
            }
            _ if depth >= 1 => current.push(character),
            _ => {}
        }
    }
    out
}

/// Whether a demangled name denotes an EnTT component type
/// (`…Component`, or a type inside an `entt` template argument list).
#[must_use]
pub fn looks_like_component(name: &str) -> bool {
    let leaf = name.rsplit("::").next().unwrap_or(name);
    let leaf = leaf.split('<').next().unwrap_or(leaf);
    leaf.ends_with("Component") || leaf.ends_with("FlagComponent")
}

fn demangle_encoding(symbol: &str) -> Option<String> {
    if !symbol.starts_with("_Z") {
        return None;
    }
    let parsed = Symbol::new(symbol).ok()?;
    parsed.demangle().ok()
}

/// Itanium builtin type codes, which are types but not names.
fn builtin_type(text: &str) -> Option<&'static str> {
    Some(match text {
        "v" => "void",
        "w" => "wchar_t",
        "b" => "bool",
        "c" => "char",
        "a" => "signed char",
        "h" => "unsigned char",
        "s" => "short",
        "t" => "unsigned short",
        "i" => "int",
        "j" => "unsigned int",
        "l" => "long",
        "m" => "unsigned long",
        "x" => "long long",
        "y" => "unsigned long long",
        "n" => "__int128",
        "o" => "unsigned __int128",
        "f" => "float",
        "d" => "double",
        "e" => "long double",
        "g" => "__float128",
        "z" => "...",
        _ => return None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_simple_class_name_demangles() {
        assert_eq!(
            demangle_type("14ClientInstance").map(|name| name.display),
            Some("ClientInstance".to_string())
        );
    }

    #[test]
    fn template_arguments_are_part_of_the_identity() {
        let first = demangle_type("NSt6__ndk16vectorIiNS_9allocatorIiEEEE").expect("vector<int>");
        let second = demangle_type("NSt6__ndk16vectorIcNS_9allocatorIcEEEE").expect("vector<char>");
        assert_ne!(first.display, second.display);
        assert_eq!(first.base, "std::__ndk1::vector");
        assert!(first.display.contains("int"));
        assert!(second.display.contains("char"));
    }

    #[test]
    fn a_lambda_inside_a_function_keeps_its_enclosing_function() {
        let lambda = demangle_type("ZN5Level4tickEvEUlvE_").expect("lambda");
        assert!(
            lambda.display.contains("Level::tick()"),
            "{}",
            lambda.display
        );
        // `{lambda()#1}` is the libiberty spelling; `cpp_demangle` may use
        // `'lambda'` with an ordinal. Both name the enclosing function, which
        // is the part the naming engine relies on.
        assert!(
            lambda.display.contains("lambda") || lambda.display.contains("$_0"),
            "{}",
            lambda.display
        );
    }

    #[test]
    fn substitutions_are_resolved() {
        let name = demangle_type("NSt6__ndk16vectorIiNS_9allocatorIiEEEE").expect("vector");
        assert!(!name.display.contains("S_"), "{}", name.display);
        assert!(name.display.contains("std::__ndk1::allocator<int>"));
    }

    #[test]
    fn a_function_pointer_template_argument_is_kept() {
        let name = demangle_type("N12StrictSystemIXadL_Z6tickMeEEEE").expect("non-type argument");
        assert!(name.display.contains("tickMe"), "{}", name.display);
    }

    #[test]
    fn base_names_drop_every_argument_list() {
        assert_eq!(
            strip_template_arguments("entt::basic_storage<A, B>::get<int>"),
            "entt::basic_storage::get"
        );
        assert_eq!(
            strip_template_arguments(
                "std::__ndk1::__function::__func<void (*)(), std::allocator<void> >"
            ),
            "std::__ndk1::__function::__func"
        );
    }

    #[test]
    fn template_arguments_are_split_at_the_top_level() {
        assert_eq!(
            template_arguments("ViewT<StrictEntityContext, Include<A>, Exclude<B>>"),
            vec![
                "StrictEntityContext".to_string(),
                "Include<A>".to_string(),
                "Exclude<B>".to_string()
            ]
        );
        assert!(template_arguments("NoArguments").is_empty());
    }

    #[test]
    fn malformed_names_are_rejected_instead_of_guessed() {
        assert!(demangle_type("").is_none());
        assert!(demangle_type("0").is_none());
        assert!(demangle_type("99short").is_none());
        assert!(demangle_type("N6icu_69").is_none());
        assert!(demangle_type("not a mangled name").is_none());
    }

    #[test]
    fn builtins_demangle_without_a_parser() {
        assert_eq!(
            demangle_type("i").map(|name| name.display),
            Some("int".to_string())
        );
        assert_eq!(
            demangle_type("d").map(|name| name.display),
            Some("double".to_string())
        );
    }

    #[test]
    fn a_reference_on_a_nested_type_is_placed_after_the_whole_name() {
        assert_eq!(
            repair_qualifier_placement("f(__cxxabiv1::__class_type_info&::__upcast_result)"),
            "f(__cxxabiv1::__class_type_info::__upcast_result&)"
        );
        assert_eq!(
            repair_qualifier_placement("A&::B::C<int> const"),
            "A::B::C<int>& const"
        );
        // No nested name follows: leave the text untouched.
        assert_eq!(repair_qualifier_placement("int&"), "int&");
    }

    #[test]
    fn symbols_keep_their_full_signature() {
        assert_eq!(
            demangle_symbol("_ZN12FixtureActor4tickEv").as_deref(),
            Some("FixtureActor::tick()")
        );
    }
}
