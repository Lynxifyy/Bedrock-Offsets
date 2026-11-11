# Troubleshooting

Most failures have a specific cause and a specific message. Find the message
below before changing anything.

## Reading the messages

Every engine failure arrives in Kotlin as an `EngineException` carrying an
`EngineErrorKind`. The UI maps each kind to one sentence plus a hint, so the
text you see already names the cause:

| Message | Kind | What it means |
|---|---|---|
| "does not contain libminecraftpe.so" | `MissingSoFile` | The APK has no `lib/arm64-v8a/libminecraftpe.so`. Java Edition, or a bundle with only 32-bit or x86 natives. |
| "could not be parsed as an APK" | `InvalidApk` | The archive is truncated, corrupted or not a ZIP. |
| "Android refused to hand over a readable descriptor" | `InvalidDescriptor` | SAF returned a descriptor the engine could not use. Re-select the file. |
| "not enough free storage to extract the library" | `StorageFull` | The cache volume filled up mid-extraction. |
| "is not a valid ELF shared object" | `InvalidElf` | The entry exists but its bytes are not a parseable ELF. |
| "Only arm64-v8a is supported." | `UnsupportedArchitecture` | The library is 32-bit ARM, x86 or x86-64. |
| "A required section is missing" | `MissingSection` | `.text` (or a section RTTI needs) is absent, which usually means a corrupted or heavily stripped file. |
| "A signature pattern could not be compiled" | `InvalidPattern` | `patterns.json` has malformed wildcard syntax. |
| "The scan was cancelled." | `Cancelled` | The user cancelled. Not an error. |
| "The engine's response could not be read." | `Payload` | A JSON payload could not be turned into a model. Almost always a release build that shrank a model class away; see below. |

If a message is not in that table, the failure came from somewhere else and the
generic branch produced it; the original `Throwable` message is included.

## A JNI crash

A hard crash (the process disappears, no Compose error screen) is different from
a reported failure. A reported failure is the engine working as designed: it
caught the problem and returned it. A crash means the failure happened below the
catch.

### Find the logcat line

```bash
adb logcat -s OffsetCore:V AndroidRuntime:E DEBUG:V libc:V
```

The engine tags everything `OffsetCore`. A panic is logged as
`panic in offset_core: <message>` followed by a backtrace, and the JNI entry
point then converts it into a returned error, so a panic in the log with no
crash is expected and handled.

`DEBUG` and `libc` matter because a JNI error that the engine cannot catch
(a signature mismatch, a null method id) aborts through the runtime, and the
check-JNI report appears there rather than under `OffsetCore`.

### The three JNI failures that are not panics

1. **"JNI DETECTED ERROR IN APPLICATION: can't call ... on instance of ..."**
   The Kotlin callback object does not match the interface the engine expects.
   `progress.rs` calls `onProgress(int, String)` by that exact signature;
   renaming the method or changing the parameter type breaks it. The signature
   is pinned by a unit test on both sides.

2. **"JNI ERROR ... pending exception"**
   The engine returned to Java with an exception pending, which is undefined
   behaviour. Every call site clears the exception it caused. If this appears,
   a new call path was added without the `exception_check` / `exception_clear`
   pair.

3. **"No implementation found for ..."**
   The native method name does not match an exported symbol. The Rust function
   name must be the exact `Java_`-mangled form, and R8 must not rename the Java
   declaration. `-keepclasseswithmembernames class * { native <methods>; }` in
   `proguard-rules.pro` exists for exactly this; check it was not weakened.

### A crash with no logcat line at all

Almost always the engine never loaded. `System.loadLibrary("offset_core")`
fails silently inside a `catch` and every later call throws
`UnsatisfiedLinkError`. Check that `lib/arm64-v8a/liboffset_core.so` is inside
the installed APK:

```bash
unzip -l app-release.apk | grep liboffset_core
```

If it is missing, the Rust cross-compile did not run. Build it directly:

```bash
python3 scripts/build_rust.py
```

## A scan that returns nothing

If the scan completes but every `pattern:` offset is `"-"`, the pack did not
match this build. Check, in order:

1. **The library was found and parsed.** `source.architecture` must be
   `AArch64` and `source.textSize` must be present. If not, the problem is
   earlier and the error message would have said so.
2. **`stats.discardedMatches` is large.** Signatures matched bytes but the
   prologue check rejected them, which means the bytes at those offsets are not
   function entry points in this build. The signature is too loose.
3. **`stats.matchedSignatures` is 0.** Nothing matched at all. The signature
   does not exist in this build; re-derive it.
4. **`verification.conflicts` is non-zero.** Signatures matched, but not where
   the symbols say the function is. Prefer the symbol addresses and re-derive
   the signatures.

Use `scripts/verify_offsets.py` to compare against a build that does work and
see which offsets moved.

## Building the engine

### "cargo: command not found"

`scripts/build_rust.py` warns and exits `0` when the toolchain is absent, so the
Kotlin build still works. To build the engine, install the toolchain:

```bash
bash scripts/setup_toolchain.sh
```

### "error: failed to run custom build command for `capstone-sys`"

The Capstone C library needs a host C compiler and CMake. On Debian or Ubuntu:

```bash
sudo apt-get install -y build-essential cmake
```

### "undefined reference to `__android_log_write`"

The engine is being linked for the host but includes the Android logger. The
logger is behind `#[cfg(target_os = "android")]`; a host `cargo build` does not
compile it. Use `cargo test` or `cargo clippy` for host work and
`scripts/build_rust.py` (which invokes `cargo ndk`) for Android.

## Storage and permissions

### "Android denied read access"

The SAF grant expired, which happens after the app is killed and the URI is
restored from a saved state. Select the APK again; the app never caches the
permission.

### "There is not enough free storage"

Extraction writes the library to the app cache, so it needs the library's size
in free space. Clear the cache from Settings and retry, or free space on the
device.

### The cache keeps refilling

Every extracted library and cached document is overwritten with zeros and
deleted on clear, which can take a moment for a large library because the
overwrite is streamed. That is the cost of not leaving a copy of the APK
readable in free space. Turn on "Force rescan" only if you need to bypass the
cached result, not to free space.

## A release build fails with "Abstract classes can't be instantiated!"

This only happens in a **release** build, never in debug, and it is the app's
own defect rather than a problem with the APK.

Gson builds the engine's payload models by reflection: it calls a constructor
and assigns fields by name. R8 cannot see those reflective calls, so unless a
keep rule names the model it strips the constructor and marks the class
abstract. The first payload the app then parses fails with Gson's

```
JsonIOException: Abstract classes can't be instantiated! Adjust the R8 profile
Class name: com.chimerateam.offsetextractor.data.native.e
```

The single-letter class name is the giveaway: `e` is what
`ExtractionPayload` was renamed to. The UI shows the message from the
`Payload` row above and keeps the raw text out of sight, because the obfuscated
name is meaningless to a user.

### Confirm it

```bash
python3 scripts/verify_release.py app/build/outputs/apk/release/app-release.apk
```

The `Gson models` line lists any model that was shrunk away, marked abstract, or
left without a constructor. When it passes it reads
`Gson models: N models instantiable`.

### Fix it

The models live in five packages, and `app/proguard-rules.pro` keeps all of
them:

```proguard
-keep class com.chimerateam.offsetextractor.data.native.**   { <init>(...); <fields>; }
-keep class com.chimerateam.offsetextractor.data.export.**   { <init>(...); <fields>; }
-keep class com.chimerateam.offsetextractor.data.cache.**    { <init>(...); <fields>; }
-keep class com.chimerateam.offsetextractor.data.patterns.** { <init>(...); <fields>; }
-keep class com.chimerateam.offsetextractor.ui.state.**      { <init>(...); <fields>; }
```

If a **new** package gains a Gson model, add its keep rule **and** the class to
`GSON_MODEL_CLASSES` in `scripts/verify_release.py`. A test in
`scripts/tests/test_verify_release.py` fails if the two drift apart, so the
guard cannot silently lose coverage.

`-keepattributes Signature` matters just as much: without it `List<PatternHit>`
arrives as a raw `List` and Gson mis-types the result instead of throwing.

### The blunt fallback

`-dontshrink` disables shrinking entirely and would hide the problem, at the
cost of the whole point of R8. It is not used here. If the models ever need it,
prefer widening the keep rules, which the verifier can still check.

## Still stuck

Collect the following before reporting:

- the app version (`versionName` from Settings or the APK),
- the Android version and device model,
- the exact message from the UI,
- the `OffsetCore` and `DEBUG` logcat lines from the attempt,
- the `stats` block of the exported JSON, if a document was written.
