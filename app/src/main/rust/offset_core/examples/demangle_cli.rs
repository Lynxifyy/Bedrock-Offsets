//! Demangles lines from standard input, for the corpus cross-check.
//!
//! Input lines are `<kind>\t<mangled>` where kind is `type` or `symbol`.
//! Output is `<kind>\t<mangled>\t<demangled>`; a name the engine cannot
//! demangle prints `ERROR` in the third column. Kept as an example so it is
//! built by `cargo test --all-targets` and cannot rot.

use std::io::{self, BufRead, Write};

fn main() -> io::Result<()> {
    let stdin = io::stdin();
    let mut stdout = io::BufWriter::new(io::stdout().lock());
    for line in stdin.lock().lines() {
        let line = line?;
        let Some((kind, mangled)) = line.split_once('\t') else {
            continue;
        };
        let demangled = match kind {
            "type" => offset_core::demangle::demangle_type(mangled).map(|name| name.display),
            "symbol" => offset_core::demangle::demangle_symbol(mangled),
            _ => None,
        };
        writeln!(
            stdout,
            "{kind}\t{mangled}\t{}",
            demangled.as_deref().unwrap_or("ERROR")
        )?;
    }
    stdout.flush()
}
