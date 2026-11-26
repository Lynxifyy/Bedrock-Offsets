//! Category rules shared with the Kotlin UI.
//!
//! The engine classifies every record as it writes it, so the export never has
//! to parse the table a second time. The rules are the same `categories.json`
//! the app ships and the Kotlin [`Classifier`](crate::internal) uses, and the
//! matching semantics are the same: a class rule is a full match against the
//! class name or one of its bases, a function rule is a search inside the
//! demangled name, and a namespace rule is a prefix comparison.

use crate::{CoreError, CoreResult};
use regex::{Regex, RegexBuilder};
use serde::Deserialize;

/// Category a named record falls back to when nothing matched.
pub const OTHER_NAMED: &str = "other_named";

/// Category for `sub_*` records.
pub const UNNAMED: &str = "unnamed";

/// One ordered classification rule.
#[derive(Debug, Clone)]
pub struct CategoryRule {
    /// Stable identifier used in file names.
    pub id: String,
    /// Human-readable label.
    pub label: String,
    /// Full-match patterns for a class name or its bases.
    pub classes: Vec<Regex>,
    /// Search patterns for a demangled function name.
    pub functions: Vec<Regex>,
    /// Namespace prefixes.
    pub namespaces: Vec<String>,
    /// Whether a base class may satisfy a class rule.
    pub include_bases: bool,
}

/// The ordered rule set.
#[derive(Debug, Clone, Default)]
pub struct CategoryRules {
    rules: Vec<CategoryRule>,
}

#[derive(Debug, Deserialize)]
struct RawRule {
    #[serde(default)]
    category: Option<String>,
    #[serde(default)]
    id: Option<String>,
    #[serde(default)]
    label: Option<String>,
    #[serde(default)]
    classes: Vec<String>,
    #[serde(default)]
    functions: Vec<String>,
    #[serde(default)]
    namespaces: Vec<String>,
    #[serde(default = "default_include_bases", rename = "includeBases")]
    include_bases: bool,
}

fn default_include_bases() -> bool {
    true
}

#[derive(Debug, Deserialize)]
struct RawDocument {
    #[serde(default)]
    categories: Vec<RawRule>,
}

impl CategoryRules {
    /// Parses `categories.json`.
    ///
    /// # Errors
    ///
    /// Returns [`CoreError::InvalidPattern`] when the document is not valid
    /// JSON or holds no rule.
    pub fn parse(json: &str) -> CoreResult<Self> {
        let document: RawDocument = serde_json::from_str(json).map_err(|error| {
            CoreError::InvalidPattern(format!("cannot parse the category rules: {error}"))
        })?;
        let mut rules = Vec::with_capacity(document.categories.len());
        for raw in document.categories {
            let Some(id) = raw.category.or(raw.id) else {
                continue;
            };
            // Kotlin's `Regex.matches` is a full match; wrapping the pattern
            // keeps the two implementations byte-for-byte compatible.
            let classes = raw
                .classes
                .iter()
                .filter_map(|pattern| {
                    RegexBuilder::new(&format!("^(?:{pattern})$"))
                        .case_insensitive(true)
                        .build()
                        .ok()
                })
                .collect();
            let functions = raw
                .functions
                .iter()
                .filter_map(|pattern| {
                    RegexBuilder::new(pattern)
                        .case_insensitive(true)
                        .build()
                        .ok()
                })
                .collect();
            rules.push(CategoryRule {
                label: raw.label.unwrap_or_else(|| id.clone()),
                id,
                classes,
                functions,
                namespaces: raw.namespaces,
                include_bases: raw.include_bases,
            });
        }
        if rules.is_empty() {
            return Err(CoreError::InvalidPattern(
                "the category rules hold no usable entry".to_string(),
            ));
        }
        Ok(Self { rules })
    }

    /// Whether the rule set is empty.
    pub fn is_empty(&self) -> bool {
        self.rules.is_empty()
    }

    /// Category identifiers, in rule order.
    pub fn ids(&self) -> impl Iterator<Item = &str> {
        self.rules.iter().map(|rule| rule.id.as_str())
    }

    /// Label of `id`, falling back to the identifier itself.
    pub fn label_of(&self, id: &str) -> String {
        self.rules
            .iter()
            .find(|rule| rule.id == id)
            .map(|rule| rule.label.clone())
            .unwrap_or_else(|| id.to_string())
    }

    /// Category of a class, matched against its name and its bases.
    pub fn category_for_class(&self, name: &str, bases: &[String]) -> Option<&str> {
        for rule in &self.rules {
            if rule.classes.is_empty() {
                continue;
            }
            for pattern in &rule.classes {
                if pattern.is_match(name) {
                    return Some(rule.id.as_str());
                }
                if rule.include_bases && bases.iter().any(|base| pattern.is_match(base)) {
                    return Some(rule.id.as_str());
                }
            }
        }
        None
    }

    /// Category of a function, given the category of its owning class.
    pub fn category_for_function(&self, name: &str, class_category: Option<&str>) -> String {
        if let Some(category) = class_category {
            return category.to_string();
        }
        for rule in &self.rules {
            if rule.functions.iter().any(|pattern| pattern.is_match(name)) {
                return rule.id.clone();
            }
        }
        let namespace = name.split("::").next().unwrap_or(name);
        for rule in &self.rules {
            if rule
                .namespaces
                .iter()
                .any(|prefix| namespace_has_prefix(namespace, prefix))
            {
                return rule.id.clone();
            }
        }
        if name.starts_with("sub_") {
            UNNAMED.to_string()
        } else {
            OTHER_NAMED.to_string()
        }
    }
}

/// Whether `namespace` is `prefix` or continues it at a boundary.
///
/// A bare `starts_with` made `Json` claim `JsonUtil`, `JsonDefinition` and
/// every other Mojang JSON class: 148,009 functions on 26.60 landed in
/// "Third-party libraries" and were then invisible to a modder searching for
/// game code. A namespace prefix now ends at `::` or at the end of the name.
/// A prefix that itself ends in `_` is a versioned namespace such as `icu_69`
/// and matches any continuation.
fn namespace_has_prefix(namespace: &str, prefix: &str) -> bool {
    if prefix.ends_with('_') {
        return namespace.starts_with(prefix);
    }
    match namespace.strip_prefix(prefix) {
        None => false,
        Some("") => true,
        Some(rest) => rest.starts_with("::"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rules() -> CategoryRules {
        CategoryRules::parse(
            r#"{"categories":[
                {"category":"block_placement","label":"Block placement",
                 "classes":["^(Block|BlockLegacy|GameMode)$"],
                 "functions":["useItemOn","placeBlock"],"includeBases":true},
                {"category":"actor_entity","label":"Actors",
                 "classes":["^(Actor|Mob)$"],"includeBases":true},
                {"category":"thirdparty","label":"Third party","namespaces":["std"]}
            ]}"#,
        )
        .expect("rules")
    }

    #[test]
    fn a_class_matches_its_own_name() {
        let rules = rules();
        assert_eq!(
            rules.category_for_class("Block", &[]),
            Some("block_placement")
        );
    }

    #[test]
    fn a_class_inherits_the_category_of_its_base() {
        let rules = rules();
        assert_eq!(
            rules.category_for_class("Player", &["Mob".to_string(), "Actor".to_string()]),
            Some("actor_entity")
        );
    }

    #[test]
    fn a_class_rule_is_a_full_match() {
        let rules = rules();
        assert_eq!(rules.category_for_class("BlockSourceExtra", &[]), None);
    }

    #[test]
    fn a_function_rule_is_a_search() {
        let rules = rules();
        assert_eq!(
            rules.category_for_function("GameMode::useItemOn(BlockPos)", None),
            "block_placement"
        );
    }

    #[test]
    fn the_owning_class_wins_over_a_function_rule() {
        let rules = rules();
        assert_eq!(
            rules.category_for_function("Block::placeBlock", Some("actor_entity")),
            "actor_entity"
        );
    }

    #[test]
    fn a_namespace_rule_catches_third_party_code() {
        let rules = rules();
        assert_eq!(
            rules.category_for_function("std::vector<int>::push_back", None),
            "thirdparty"
        );
    }

    #[test]
    fn a_namespace_prefix_stops_at_a_boundary() {
        let rules = CategoryRules::parse(
            r#"{"categories":[
                {"category":"thirdparty","label":"Third party",
                 "namespaces":["Json","std","icu_"]}
            ]}"#,
        )
        .expect("rules");
        assert_eq!(
            rules.category_for_function("Json::Value::asString", None),
            "thirdparty"
        );
        assert_eq!(
            rules.category_for_function("std::vector<int>::push_back", None),
            "thirdparty"
        );
        assert_eq!(
            rules.category_for_function("icu_69::UnicodeString::length", None),
            "thirdparty"
        );
        assert_eq!(
            rules.category_for_function("JsonUtil::parse", None),
            OTHER_NAMED
        );
    }

    #[test]
    fn an_unmatched_sub_is_unnamed_and_an_unmatched_name_is_other() {
        let rules = rules();
        assert_eq!(rules.category_for_function("sub_1234", None), UNNAMED);
        assert_eq!(
            rules.category_for_function("Something::else", None),
            OTHER_NAMED
        );
    }

    /// The shipped rules, compiled into the engine's own test build.
    fn shipped_rules() -> CategoryRules {
        CategoryRules::parse(include_str!("../../../assets/categories.json"))
            .expect("the shipped rules parse")
    }

    #[test]
    fn the_shipped_rules_put_the_block_classes_in_block_placement() {
        let rules = shipped_rules();
        for name in [
            "Block",
            "BlockLegacy",
            "BlockSource",
            "BlockActor",
            "BlockItem",
            "GameMode",
        ] {
            assert_eq!(
                rules.category_for_class(name, &[]),
                Some("block_placement"),
                "{name} must be in block placement"
            );
        }
    }

    #[test]
    fn the_shipped_rules_put_the_placement_functions_in_block_placement() {
        let rules = shipped_rules();
        for name in [
            "GameMode::useItemOn(BlockPos const&)",
            "BlockLegacy::buildBlock",
            "BlockSource::placeBlock",
            "Block::mayPlace",
            "Block::tryPlace",
            "Player::destroyBlock",
        ] {
            assert_eq!(
                rules.category_for_function(name, None),
                "block_placement",
                "{name} must be in block placement"
            );
        }
    }

    #[test]
    fn the_shipped_rules_agree_on_the_other_categories() {
        let rules = shipped_rules();
        assert_eq!(rules.category_for_class("Actor", &[]), Some("actor_entity"));
        assert_eq!(rules.category_for_class("Mob", &[]), Some("actor_entity"));
        assert_eq!(rules.category_for_class("Level", &[]), Some("world_level"));
        assert_eq!(
            rules.category_for_class("LevelRenderer", &[]),
            Some("rendering")
        );
        assert_eq!(rules.category_for_function("sub_1234", None), UNNAMED);
        assert_eq!(
            rules.category_for_function("SomethingElse", None),
            OTHER_NAMED
        );
    }

    #[test]
    fn a_document_without_rules_is_rejected() {
        assert!(CategoryRules::parse(r#"{"categories":[]}"#).is_err());
        assert!(CategoryRules::parse("not json").is_err());
    }

    #[test]
    fn the_label_falls_back_to_the_identifier() {
        let rules = rules();
        assert_eq!(rules.label_of("block_placement"), "Block placement");
        assert_eq!(rules.label_of("missing"), "missing");
    }
}
