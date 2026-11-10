# Security Policy

## What this app does with your data

Bedrock Offset Extractor reads a Minecraft Bedrock APK that you choose, extracts
`lib/arm64-v8a/libminecraftpe.so` from it, and parses that library as data.

It never loads the library and never executes a single instruction from it. The
engine maps the file read-only and walks its ELF headers, section headers, symbol
tables and byte patterns. There is no code path that jumps into, `dlopen`s or
`System.load`s the library under analysis.

Nothing is uploaded. The app has no `INTERNET` permission, so it could not send
your files anywhere even if a bug tried to.

## Reporting a vulnerability

Open a private security advisory on the repository
(`Security` → `Advisories` → `Report a vulnerability`). Please include:

- the app version or commit,
- the Android version and device,
- a description of the issue and, if possible, a minimal reproduction.

Do not open a public issue for a security report.

## Design decisions that reduce risk

- **Untrusted input.** An APK is fully attacker-controlled. Every parse is
  bounds-checked against the mapped length, every size field is validated before
  it is used, and a panic at the JNI boundary is converted into an error value
  rather than allowed to unwind into the runtime.
- **Memory.** No component holds a whole APK or library in memory. Extraction
  streams through a fixed 64 KB buffer; the scan maps the library read-only and
  processes it in 4 MB chunks.
- **Storage access.** Files are opened through the Storage Access Framework. No
  file path string is ever constructed, and the descriptor is closed when the
  scan ends.
- **Cached copies.** Extracted libraries and offset documents are overwritten
  with zeros before they are unlinked, because a plain delete would leave a copy
  of your APK readable in free space.
- **Release logging.** Logcat is capped at warnings and above in a release
  build, so file names, sizes and section boundaries are not written where other
  apps could read them.
- **No obfuscation of the JSON models.** Gson and the JNI bindings are kept by
  name through R8; this is a correctness requirement, not a secrecy one.

## What is deliberately not done

- **APK signature verification.** A real check needs the platform's
  `PackageManager` to parse a file path, but this app only ever holds a Storage
  Access Framework descriptor and never constructs a path. Since the library is
  parsed as data and never executed or installed, a forged APK can at worst
  produce wrong offsets. Faking the check by parsing the signing block by hand
  would be more code and more risk than the threat it removes.

## Scope

This policy covers the app in this repository. The Minecraft Bedrock APK and the
signatures you feed the tool are not in scope: scanning a file you do not have
the right to inspect is your responsibility, not a vulnerability in this app.
