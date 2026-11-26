//! Record sinks for the internal analysis.
//!
//! The analysis produces three kinds of record. Two consumers exist: the
//! NDJSON table (one line per record, used by the host tests and by callers
//! that do not pass category rules) and the category shards (one finished JSON
//! document per category, written straight into the export work directory).
//!
//! Writing the shards here is what removes the second parse in Kotlin: the
//! archive copies these files byte for byte, so a table of 890,000 rows never
//! becomes a list of Gson objects on the Java heap.

use crate::categories::{CategoryRules, OTHER_NAMED, UNNAMED};
use crate::internal::{
    ClassRecord, DataSymbolRecord, FunctionRecord, InternalSummary, ShardSummary,
};
use crate::{CoreError, CoreResult};
use std::collections::BTreeMap;
use std::fs::File;
use std::io::{BufWriter, Write};
use std::path::{Path, PathBuf};

/// Maximum records in one shard before it is split into a `_PART_NN` file.
pub const MAX_SHARD_ROWS: usize = 100_000;

/// Receives every record the analysis produces.
pub trait RecordWriter {
    /// Writes the table header with the final statistics.
    ///
    /// # Errors
    ///
    /// Propagates an I/O failure.
    fn meta(&mut self, stats: &InternalSummary) -> std::io::Result<()>;

    /// Writes one class.
    ///
    /// # Errors
    ///
    /// Propagates an I/O failure.
    fn class(&mut self, record: &ClassRecord) -> std::io::Result<()>;

    /// Writes one function.
    ///
    /// # Errors
    ///
    /// Propagates an I/O failure.
    fn function(&mut self, record: &FunctionRecord) -> std::io::Result<()>;

    /// Writes one data symbol.
    ///
    /// # Errors
    ///
    /// Propagates an I/O failure.
    fn symbol(&mut self, record: &DataSymbolRecord) -> std::io::Result<()>;

    /// Closes the output. Called once, after the last record.
    ///
    /// # Errors
    ///
    /// Propagates an I/O failure.
    fn finish(&mut self, stats: &InternalSummary) -> std::io::Result<()> {
        let _ = stats;
        Ok(())
    }
}

/// Writes the NDJSON table: one line per record, meta line first.
pub struct NdjsonWriter<'a> {
    out: &'a mut dyn Write,
}

impl<'a> NdjsonWriter<'a> {
    /// Wraps `out`.
    pub fn new(out: &'a mut dyn Write) -> Self {
        Self { out }
    }

    fn record<T: serde::Serialize>(
        &mut self,
        kind: &str,
        key: &str,
        value: &T,
    ) -> std::io::Result<()> {
        self.out.write_all(b"{\"type\":\"")?;
        self.out.write_all(kind.as_bytes())?;
        self.out.write_all(b"\",\"")?;
        self.out.write_all(key.as_bytes())?;
        self.out.write_all(b"\":")?;
        serde_json::to_writer(&mut *self.out, value)?;
        self.out.write_all(b"}\n")
    }
}

impl RecordWriter for NdjsonWriter<'_> {
    fn meta(&mut self, stats: &InternalSummary) -> std::io::Result<()> {
        writeln!(
            self.out,
            "{}",
            serde_json::json!({
                "type": "meta",
                "schemaVersion": 1,
                "kind": "internal_cpp",
                "stats": stats,
            })
        )
    }

    fn class(&mut self, record: &ClassRecord) -> std::io::Result<()> {
        self.record("class", "class", record)
    }

    fn function(&mut self, record: &FunctionRecord) -> std::io::Result<()> {
        self.record("function", "function", record)
    }

    fn symbol(&mut self, record: &DataSymbolRecord) -> std::io::Result<()> {
        self.record("symbol", "symbol", record)
    }
}

/// Which part of a category document is currently open.
#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
enum Section {
    None,
    Classes,
    Functions,
    Symbols,
}

/// One open shard file.
struct Shard {
    category: String,
    label: String,
    part: usize,
    path: PathBuf,
    writer: BufWriter<File>,
    section: Section,
    first_in_section: bool,
    classes: usize,
    functions: usize,
    symbols: usize,
}

impl Shard {
    fn open(
        dir: &Path,
        rules: &CategoryRules,
        header: &str,
        category: &str,
        part: usize,
    ) -> CoreResult<Self> {
        let id = shard_id(category, part);
        let path = dir.join(format!("{id}.json"));
        let file = File::create(&path)?;
        let mut writer = BufWriter::new(file);
        let label = rules.label_of(category);
        write!(
            writer,
            "{{\"schemaVersion\":1,\"generatedBy\":\"BedrockOffsetExtractor\",\
             \"kind\":\"internal_cpp_category\",\"category\":\"{category}\",\
             \"label\":{label_json},\"part\":{part}",
            label_json = serde_json::to_string(&label).unwrap_or_else(|_| "\"\"".to_string()),
        )?;
        if !header.is_empty() {
            write!(writer, ",{header}")?;
        }
        Ok(Self {
            category: category.to_string(),
            label,
            part,
            path,
            writer,
            section: Section::None,
            first_in_section: true,
            classes: 0,
            functions: 0,
            symbols: 0,
        })
    }

    /// Closes the open section and opens [next], emitting empty placeholders
    /// for any section that was skipped so the document stays valid.
    fn ensure(&mut self, next: Section) -> std::io::Result<()> {
        if self.section == next {
            return Ok(());
        }
        match self.section {
            Section::Classes => self.writer.write_all(b"}")?,
            Section::Functions | Section::Symbols => self.writer.write_all(b"]")?,
            Section::None => {}
        }
        for skipped in [Section::Classes, Section::Functions, Section::Symbols] {
            if skipped < next && skipped > self.section {
                match skipped {
                    Section::Classes => self.writer.write_all(b",\"classes\":{}")?,
                    Section::Functions => self.writer.write_all(b",\"functions\":[]")?,
                    Section::Symbols => self.writer.write_all(b",\"symbols\":[]")?,
                    Section::None => {}
                }
            }
        }
        match next {
            Section::Classes => self.writer.write_all(b",\"classes\":{")?,
            Section::Functions => self.writer.write_all(b",\"functions\":[")?,
            Section::Symbols => self.writer.write_all(b",\"symbols\":[")?,
            Section::None => {}
        }
        self.section = next;
        self.first_in_section = true;
        Ok(())
    }

    fn close(mut self) -> CoreResult<ShardSummary> {
        if self.section == Section::Classes {
            self.writer.write_all(b"}")?;
        } else if self.section == Section::Functions || self.section == Section::Symbols {
            self.writer.write_all(b"]")?;
        }
        for skipped in [Section::Classes, Section::Functions, Section::Symbols] {
            if skipped > self.section {
                match skipped {
                    Section::Classes => self.writer.write_all(b",\"classes\":{}")?,
                    Section::Functions => self.writer.write_all(b",\"functions\":[]")?,
                    Section::Symbols => self.writer.write_all(b",\"symbols\":[]")?,
                    Section::None => {}
                }
            }
        }
        writeln!(
            self.writer,
            ",\"stats\":{{\"classes\":{},\"functions\":{},\"symbols\":{},\
             \"entries\":{}}}}}",
            self.classes,
            self.functions,
            self.symbols,
            self.classes + self.functions + self.symbols
        )?;
        self.writer.flush()?;
        self.writer.get_ref().sync_all()?;
        // The size is read back from the closed file rather than estimated, so
        // the summary can never report a finished document as zero bytes.
        let bytes = self.writer.get_ref().metadata().map(|meta| meta.len())?;
        Ok(ShardSummary {
            id: shard_id(&self.category, self.part),
            category: self.category,
            label: self.label,
            part: self.part,
            classes: self.classes,
            functions: self.functions,
            symbols: self.symbols,
            path: self.path.to_string_lossy().into_owned(),
            bytes,
        })
    }
}

/// Identifier of one shard, `block_placement` or `unnamed_part_02`.
fn shard_id(category: &str, part: usize) -> String {
    if part <= 1 {
        category.to_string()
    } else {
        format!("{category}_part_{part:02}")
    }
}

/// Writes one finished JSON document per category.
pub struct ShardWriter {
    rules: CategoryRules,
    dir: PathBuf,
    header: String,
    shards: BTreeMap<String, Shard>,
    class_to_category: BTreeMap<String, String>,
    closed: Vec<ShardSummary>,
}

impl ShardWriter {
    /// Creates a writer that puts its files in `dir`.
    ///
    /// @param header extra top-level fields (version and source), already
    ///   serialised, or an empty string.
    pub fn new(rules: CategoryRules, dir: &Path, header: String) -> CoreResult<Self> {
        std::fs::create_dir_all(dir)?;
        Ok(Self {
            rules,
            dir: dir.to_path_buf(),
            header,
            shards: BTreeMap::new(),
            class_to_category: BTreeMap::new(),
            closed: Vec::new(),
        })
    }

    /// Category of a function from its demangled name.
    fn function_category(&self, name: &str) -> String {
        let class = name.split("::").next().filter(|_| name.contains("::"));
        let class_category = class.and_then(|class| {
            self.class_to_category
                .get(class)
                .map(String::as_str)
                .or_else(|| self.rules.category_for_class(class, &[]))
        });
        self.rules.category_for_function(name, class_category)
    }

    /// Shard for [category], opening it and rolling over at the row cap.
    fn shard_for(&mut self, category: &str, section: Section) -> CoreResult<&mut Shard> {
        let key = category.to_string();
        let needs_new = match self.shards.get(&key) {
            None => true,
            Some(shard) => {
                section != Section::Classes && shard.functions + shard.symbols >= MAX_SHARD_ROWS
            }
        };
        if needs_new {
            let part = self
                .shards
                .get(&key)
                .map(|shard| shard.part + 1)
                .unwrap_or(1);
            let rules = self.rules.clone();
            let shard = Shard::open(&self.dir, &rules, &self.header, category, part)?;
            if let Some(previous) = self.shards.insert(key.clone(), shard) {
                self.closed.push(previous.close()?);
            }
        }
        self.shards
            .get_mut(&key)
            .ok_or_else(|| CoreError::InvalidPattern("shard was not created".to_string()))
    }

    /// Files written so far, in category order.
    pub fn parts(&self) -> Vec<ShardSummary> {
        let mut parts = self.closed.clone();
        parts.extend(self.shards.values().map(|shard| {
            ShardSummary {
                id: shard_id(&shard.category, shard.part),
                category: shard.category.clone(),
                label: shard.label.clone(),
                part: shard.part,
                classes: shard.classes,
                functions: shard.functions,
                symbols: shard.symbols,
                path: shard.path.to_string_lossy().into_owned(),
                bytes: std::fs::metadata(&shard.path)
                    .map(|meta| meta.len())
                    .unwrap_or(0),
            }
        }));
        parts.sort_by(|left, right| left.id.cmp(&right.id));
        parts
    }

    /// Bytes on disk of every finished document.
    pub fn total_bytes(&self) -> u64 {
        self.closed.iter().map(|part| part.bytes).sum::<u64>()
            + self
                .shards
                .values()
                .map(|shard| {
                    std::fs::metadata(&shard.path)
                        .map(|meta| meta.len())
                        .unwrap_or(0)
                })
                .sum::<u64>()
    }

    /// Absolute path of the directory the documents are written into.
    pub fn directory(&self) -> &Path {
        &self.dir
    }

    /// Writes the class-to-category map the index needs.
    ///
    /// # Errors
    ///
    /// Propagates an I/O failure.
    pub fn write_class_map(&self, target: &Path) -> CoreResult<()> {
        let file = File::create(target)?;
        let mut writer = BufWriter::new(file);
        serde_json::to_writer(&mut writer, &self.class_to_category)
            .map_err(|error| CoreError::InvalidPattern(error.to_string()))?;
        writer.flush()?;
        Ok(())
    }
}

impl RecordWriter for ShardWriter {
    fn meta(&mut self, _stats: &InternalSummary) -> std::io::Result<()> {
        // The category documents carry their own stats, so the table header has
        // no place in them.
        Ok(())
    }

    fn class(&mut self, record: &ClassRecord) -> std::io::Result<()> {
        let category = self
            .rules
            .category_for_class(&record.name, &record.bases)
            .unwrap_or(OTHER_NAMED)
            .to_string();
        self.class_to_category
            .insert(record.name.clone(), category.clone());
        let shard = self
            .shard_for(&category, Section::Classes)
            .map_err(io_error)?;
        shard.ensure(Section::Classes)?;
        if !shard.first_in_section {
            shard.writer.write_all(b",")?;
        }
        shard.first_in_section = false;
        shard.writer.write_all(b"\"")?;
        write_escaped(&mut shard.writer, &record.name)?;
        shard.writer.write_all(b"\":")?;
        serde_json::to_writer(&mut shard.writer, record)?;
        shard.classes += 1;
        Ok(())
    }

    fn function(&mut self, record: &FunctionRecord) -> std::io::Result<()> {
        let category = self.function_category(&record.name);
        let shard = self
            .shard_for(&category, Section::Functions)
            .map_err(io_error)?;
        shard.ensure(Section::Functions)?;
        if !shard.first_in_section {
            shard.writer.write_all(b",")?;
        }
        shard.first_in_section = false;
        serde_json::to_writer(&mut shard.writer, record)?;
        shard.functions += 1;
        Ok(())
    }

    fn symbol(&mut self, record: &DataSymbolRecord) -> std::io::Result<()> {
        let category = self.function_category(&record.name);
        let category = if category == UNNAMED {
            OTHER_NAMED.to_string()
        } else {
            category
        };
        let shard = self
            .shard_for(&category, Section::Symbols)
            .map_err(io_error)?;
        shard.ensure(Section::Symbols)?;
        if !shard.first_in_section {
            shard.writer.write_all(b",")?;
        }
        shard.first_in_section = false;
        serde_json::to_writer(&mut shard.writer, record)?;
        shard.symbols += 1;
        Ok(())
    }

    fn finish(&mut self, _stats: &InternalSummary) -> std::io::Result<()> {
        let shards = std::mem::take(&mut self.shards);
        for (_, shard) in shards {
            self.closed.push(shard.close().map_err(io_error)?);
        }
        Ok(())
    }
}

fn io_error(error: CoreError) -> std::io::Error {
    std::io::Error::other(error.to_string())
}

/// Writes a JSON string body without allocating a temporary `String`.
fn write_escaped(out: &mut dyn Write, value: &str) -> std::io::Result<()> {
    for byte in value.as_bytes() {
        match byte {
            b'"' => out.write_all(b"\\\"")?,
            b'\\' => out.write_all(b"\\\\")?,
            b'\n' => out.write_all(b"\\n")?,
            b'\r' => out.write_all(b"\\r")?,
            b'\t' => out.write_all(b"\\t")?,
            0x00..=0x1f => write!(out, "\\u{byte:04x}")?,
            _ => out.write_all(&[*byte])?,
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    fn rules() -> CategoryRules {
        CategoryRules::parse(
            r#"{"categories":[
                {"category":"block_placement","label":"Block placement",
                 "classes":["^(Block)$"],"functions":["placeBlock"]},
                {"category":"actor_entity","label":"Actors","classes":["^(Actor)$"]}
            ]}"#,
        )
        .expect("rules")
    }

    fn class(name: &str) -> ClassRecord {
        ClassRecord {
            name: name.to_string(),
            mangled: format!("_{name}"),
            bases: Vec::new(),
            vtable: 0x1000,
            vtable_offset: 0x10,
            type_info: 0x2000,
            slot_count: 0,
            slots: Vec::new(),
            vtable_groups: Vec::new(),
        }
    }

    fn function(name: &str) -> FunctionRecord {
        FunctionRecord {
            address: 0x3000,
            file_offset: 0x200,
            size: 4,
            name: name.to_string(),
            name_source: "symbol".to_string(),
            aliases: Vec::new(),
            kind: "function".to_string(),
            status: "verified".to_string(),
            prologue_kind: "pac".to_string(),
            mangled: None,
            target: None,
            signature: None,
            signature_unique: None,
            suspect_reason: None,
        }
    }

    #[test]
    fn every_record_lands_in_exactly_one_shard() {
        let dir = std::env::temp_dir().join(format!("shards-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let mut writer = ShardWriter::new(rules(), &dir, String::new()).expect("writer");
        writer.class(&class("Block")).expect("class");
        writer.class(&class("Actor")).expect("class");
        writer.function(&function("Block::placeBlock")).expect("fn");
        writer.function(&function("sub_1")).expect("fn");
        writer.finish(&InternalSummary::default()).expect("finish");
        let parts = writer.parts();
        let total: usize = parts
            .iter()
            .map(|part| part.classes + part.functions + part.symbols)
            .sum();
        assert_eq!(total, 4);
        assert!(parts.iter().any(|part| part.id == "block_placement"));
        assert!(parts.iter().any(|part| part.id == "unnamed"));
        let block_part = parts
            .iter()
            .find(|part| part.id == "block_placement")
            .expect("block shard");
        let block = std::fs::read_to_string(&block_part.path).expect("read");
        let parsed: serde_json::Value = serde_json::from_str(&block).expect("valid json");
        assert_eq!(parsed["category"], "block_placement");
        assert!(parsed["classes"]["Block"].is_object());
        assert_eq!(parsed["stats"]["classes"], 1);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn an_empty_category_is_not_written_but_the_index_still_maps_classes() {
        let dir = std::env::temp_dir().join(format!("shards-empty-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let mut writer = ShardWriter::new(rules(), &dir, String::new()).expect("writer");
        writer.class(&class("Block")).expect("class");
        writer.finish(&InternalSummary::default()).expect("finish");
        let parts = writer.parts();
        assert_eq!(parts.len(), 1);
        assert_eq!(parts[0].id, "block_placement");
        let map = dir.join("map.json");
        writer.write_class_map(&map).expect("map");
        let text = std::fs::read_to_string(&map).expect("read");
        assert!(text.contains("Block"));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_large_category_rolls_over_into_parts() {
        let dir = std::env::temp_dir().join(format!("shards-parts-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let mut writer = ShardWriter::new(rules(), &dir, String::new()).expect("writer");
        for index in 0..(MAX_SHARD_ROWS + 5) {
            writer
                .function(&function(&format!("Block::placeBlock{index}")))
                .expect("fn");
        }
        writer.finish(&InternalSummary::default()).expect("finish");
        let parts = writer.parts();
        assert_eq!(parts.len(), 2);
        assert_eq!(parts[0].id, "block_placement");
        assert_eq!(parts[1].id, "block_placement_part_02");
        assert_eq!(parts[1].functions, 5);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_ndjson_writer_keeps_the_old_shape() {
        let mut buffer = Vec::new();
        let mut writer = NdjsonWriter::new(&mut buffer);
        writer.class(&class("Block")).expect("class");
        writer.function(&function("Block::placeBlock")).expect("fn");
        let text = String::from_utf8(Cursor::new(buffer).into_inner()).expect("utf8");
        assert!(text.contains("\"type\":\"class\""));
        assert!(text.contains("\"type\":\"function\""));
    }
}
