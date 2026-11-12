# FreeHands — project memory

FreeHands = a fork of OpenHands Agent Canvas (`Lynxifyy/FreeHands`) used as a
coding app with DeepSeek V4.1 Flash, plus a Capacitor Android shell and a
forked agent-server SDK (`Lynxifyy/software-agent-sdk-2.0`).

## Branches and PRs (FreeHands)
- Long-lived feature branch: `feat/discord-automations-and-bot-connect`
  (local ref `feat-discord`) — carries all the app-bundle work.
- Merge conflicts between sibling branches off the same base are all "keep both":
  action/type-guard/base unions, the adapter `client_tools` list, the websocket
  dispatch, action titles, client-tool result prefixes, and `translation.json`
  (union the keys by parsing each side as JSON — never by hand).
- `main` has **no branch protection**, so the fork-only red checks do not block
  a merge.

## Git auth (important)
- `$GITHUB_TOKEN` / `$Github` are read-only for git push on Lynxifyy repos (403).
- `$GithubLynxifyy` has full `repo` scope: use it for `git push` and for the
  GitHub API (`create_pr` tool uses the read-only token and 403s — create PRs
  with a `curl` POST to the API instead).
- Merge convention: PRs are **squash**-merged. Use the async API
  `PUT /repos/{o}/{r}/pulls/{n}/merge-async` with `{"merge_method":"squash","sha":"<head>"}`
  (returns 202 + a UUID; poll `.../merge-async/{uuid}` until `status` is `merged`).
- PR titles must be conventional (`feat:`/`fix:`/…) or the `pr-title` check fails.
  A wrong title is fixable with `PATCH /pulls/{n}` after opening.
- PR #7 CI: `test-and-build (ubuntu)` + `(windows)`, `Build Android APK`,
  `pr-title`, `check-pr-artifacts` all green. Fork-only reds: `Validate PR
  description` (issues disabled) and `Build & Push` (pushes OpenHands' ghcr).

## Fork limitations
- **Issues are disabled** on both forks (`has_issues: false`), so the
  `Validate PR description` check can never pass (it requires a linked
  `ready-for-dev` issue). `Build & Push` also fails (pushes OpenHands' ghcr).
- The `HUMAN:` section of the PR body is reserved for the human author; agents
  must not fill it, which also fails the description check by design.
- CI that *does* pass on the fork and is worth watching: `test-and-build
  (ubuntu)` and `(windows)`, `Build Android APK` (the only verifier of the
  Kotlin changes), `Check SDK version consistency`, `pr-title`. All green on
  `feat/discord-automations-and-bot-connect` @ `b2f6582`.

## Build / verify
- `npm ci` then `npm run lint` (0 errors), `npm test` (~850 files / ~9.2k tests,
  ~11 min — background it), `npm run build`, `npm run build:lib`.
- The pre-commit hook (lint-staged → tsc) needs generated files first:
  `npm run make-i18n` and `npx react-router typegen`. Do not run `make-i18n`
  concurrently (it deletes `public/locales` and races).
- Android: no Java/Gradle in the dev container — Kotlin changes are only
  verified by the CI `Build Android APK` job.

## Secrets
- Discord bot token lives only as the `DISCORD_BOT_TOKEN` secret (never in
  source). Public bot application id is `1568005572769480704`.

## GlowberryClient (ChimeraAnt-DEV/GlowberryClient) — Android launcher
- **Native code is a git submodule**: `app/src/main/cpp/preloader` (remote
  `ChimeraAnt-DEV/preloader-android`). A change under that path must be committed
  **inside the submodule**, pushed, then the parent must commit the bumped
  pointer — `git add` of a submodule path in the parent stages the gitlink, not
  the file. **Always check the pin is not behind the submodule's `main`**
  (`git -C <sub> merge-base --is-ancestor origin/main HEAD`); a long-lived
  `feat/*` branch can silently skip a large `main` PR. Merging `main` can also add
  JNI exports with no Java declaration → `JniBindingParityTest` fails with
  `stale-export`; add the Java `native` methods
  (`app/src/main/java/.../launcher/preloader/BerryApi.java`).
- **Build/test**: `JAVA_HOME=/workspace/tools/jdk-21.0.13+9
  ANDROID_HOME=/workspace/android-sdk ./gradlew :app:testDebugUnitTest`
  (3844 tests, 0 failures, 1 skipped, ~25s warm). Targeted:
  `--tests "org.chimeramc.client.<pkg>.*"`. Native host tests:
  `bash app/src/main/cpp/preloader/tests/cosmetics/run_tests.sh` (needs g++ +
  zlib + nlohmann).
- **BerryAPI ABI guard**: `python3 scripts/verify_berryapi_abi.py`. Pins the 14
  dlsym'd `BERRY_*` symbols and the seam/replay/governor ABI numbers against the
  bundled `libBerryAPI.so`. **`BERRY_Inject`/`BERRY_IsAttached` return `int`
  (0/1), not C++ `bool`** — a 1-byte bool return leaves the upper result register
  undefined. Upstream: `Lynxifyy/BerryAPI` (ABI 8).
- **Module catalogue**: `PvpModuleCatalog` = 200 entries (38 `link()` + 162
  `add()`). Honest split: **50 reachable/toggleable**, **112 disabled/badged**
  (52 `ENGINE_HOOK` + 60 `SERVER_DECIDED`), pinned by
  `PvpModuleScopesTest.theBadgeCountsMatchTheAuditNote`. The 52 ENGINE_HOOK need a
  **complete world seam** (the preloader binds only `get_local_player`; the
  stripped `libminecraftpe.so` gives no entity-list/level anchor) plus a signature
  pack for the active build. The 60 SERVER_DECIDED cannot be enabled by client
  code.
- **Setup wizard**: `SetupWizardActivity` is a self-sizing popup over
  `MainActivity`; each step shows only its own block; finishing calls
  `finishWizard()` → `MainActivity` (never a controller screen). Pinned by
  `SetupWizardLastTabsTest`.
- **Two CI gates that fail locally-silent changes** (both in `android.yml` before
  the Gradle build, so they fail fast): `python3 scripts/check_ui_hygiene.py` fails
  on *new* `findViewById` calls beyond a per-file baseline — read the view from the
  generated ViewBinding instead (or `--update` with a reason). `:app:lintDebug`
  fails on a new `MissingSuperCall` — do not override `onBackPressed()` without
  calling `super`; register an `OnBackPressedCallback` on
  `getOnBackPressedDispatcher()` instead.
- **Git author**: `Lynxifyy <297587416+Lynxifyy@users.noreply.github.com>`, no
  co-authors, no AI mentions in PRs. Push with `$GithubLynxifyy`.

## Bedrock-Offsets (Lynxifyy/Bedrock-Offsets) — Android + Rust offset extractor
- **Traps**: `createComposeRule` tests belong in `app/src/testDebug/` (the release
  variant has no Compose test manifest); goblin's `Elf::lazy_parse` leaves
  `section_headers` empty (use `parse_section_headers`); JNI symbols are
  `Java_com_chimerateam_offsetextractor_data_native_NativeLib_<method>`.
- **Review-findings PR #11** (`fix/review-findings`, 16 fixes, one commit each):
  cancelling a coroutine does **not** stop a blocking JNI scan — Rust
  `scanner::begin_scan()` registers the in-flight scan behind a generation-checked
  `ScanGuard`, `cancelScanNative` flips the flag, `MainViewModel.cancel/cancelBatch`
  signal the engine before `job.cancel()`. Also: Compose `ModifierParameter` lint
  needs `modifier` as the **first** optional param; CI rustfmt can wrap a long
  method chain differently from the container's — copy the `Diff in` form from the
  job log. `GSON_MODEL_CLASSES` is now **30** entries.
- **Branch targeting**: `feat/offset-extractor-engine` drifted **32 behind / 10
  ahead** of `main`, so a fix committed there cannot merge cleanly. Branch new work
  off current `main` instead.
- **Signature pack (fixed in PR #9)**: `patterns.txt` used to hold 10
  *instruction-shape* patterns (`frame_push_x29_x30`). Those match thousands of
  places and are named after nothing, so the symbol cross-check found no symbol →
  every offset `UNVERIFIED` → confidence permanently 0 (measured: 12,374 matches,
  8,191 discarded, 0 verified). The pack is now **derived** by
  `scripts/derive_patterns.py` from a real `libminecraftpe.so`: keep defined
  `Java_*` dynsyms and grow each signature from the symbol's bytes until unique in
  `.text`. Real library: 91 signatures → 91/91 verified, 0 discarded, 100%.
  Two traps that cost debug cycles: the in-`.text` index is `st_value -
  text.sh_addr`, **not** `st_value - delta` (`delta = sh_addr - sh_offset` is the
  *file-offset → VA* direction); and `bytes.count` skips overlaps, so uniqueness
  needs a `find` loop. The library is **unstripped** (90,670 symbols, 95 `Java_*`)
  but its internal classes (`ClientInstance`, `LocalPlayer`, …) **are** stripped,
  so JNI entry points are the only verifiable Bedrock-specific anchors.
- **Prologue check is a filter, not a veto**: it runs *after* the symbol
  cross-check and keeps a `Verified` hit whose shape is not a stack frame — a leaf
  (`isEduMode` = `mov w0, wzr; ret`) has no frame, and 27 of 91 correct offsets
  were being discarded before this. `MCPE_SO=<path> cargo test` runs the live
  `signature_pack_test.rs` measurement.
- **Layout**: Kotlin/Compose UI in `app/src/main/java/com/chimerateam/offsetextractor/`,
  Rust engine crate `offset_core` in `app/src/main/rust/offset_core/`, Python build
  automation in `scripts/` (`build_rust.py`, `generate_patterns.py`, `verify_offsets.py`,
  `verify_release.py`, `verify_version.py`, `changelog.py`).
- **CI (`android.yml`)** has three jobs: `test` (runs `./gradlew test`, ktlintCheck,
  lintDebug, assembleRelease, verify_release.py), `tooling` (python unittest), `rust`
  (fmt/clippy/test/doc/audit). **The `test` job needs the FULL Rust cross toolchain**:
  Gradle `preBuild` depends on the `buildRustEngine` Exec task, so `./gradlew test` alone
  fails with `error: no such command: ndk` unless cargo-ndk + NDK are installed. Fixed in
  73c8522 by (a) making `build_rust.py` treat a missing cargo-ndk as `ToolchainMissing`
  (warn + exit 0, not a build failure) and (b) installing the toolchain in the test job.
- **Release**: tag `v*` triggers `release.yml` (cross-compile, `verify_version.py` guard,
  assembleRelease, verify_release.py, changelog.py, `gh release create`). Version pinned in
  `app/build.gradle.kts` (`versionCode = 6`, `versionName = "1.0.5"`); the tag must match or
  `verify_version.py` fails. Release signing is optional: with no keystore secrets the
  release variant now reuses the **debug** signing config (commit d4fb36b) — without that
  explicit assignment Gradle emits `app-release-unsigned.apk` which will not install.
- **Verify release locally**: `python3 scripts/verify_release.py <apk>` checks size < 20 MiB,
  `lib/arm64-v8a/liboffset_core.so` present, no `Log.d/Log.v`, no `INTERNET` permission.
- **Build/test**: `source /workspace/tools/env.sh` then `./gradlew testDebugUnitTest
  ktlintCheck lintDebug` (~161 Kotlin tests). Rust: `cd app/src/main/rust/offset_core &&
  cargo fmt --all --check && cargo clippy --all-targets -- -D warnings && cargo test
  --all-targets` (149 tests; `MCPE_SO=<path>` adds the live-library ones). Python: `python3 -m unittest discover -s scripts/tests -t .`
  (97 tests).
- **ktlint** `12.1.1` + `.editorconfig` (`ktlint_code_style = android_studio`). ktlintCheck is
  a CI gate.
- **Env quirk**: this container's `$ANDROID_HOME` and tools live under `/workspace`
  (`/workspace/tools/jdk-21.0.13+9`, `/workspace/tools/cargo/bin`, `/workspace/android-sdk`);
  `source /workspace/tools/env.sh` sets them. jniLibs is gitignored, so the `.so` is built
  fresh on every checkout.

### Memory budget: the crash class is a transient heap spike, not a leak
- **Symptom shape**: on device the log simply *stops* — no exception, no engine
  error. That is the kernel killing the process for exceeding the heap cap, so
  there is nothing to catch and nothing to log. Any phase that allocates in
  proportion to input is a candidate.
- **v1.0.5 fix (`rtti.rs`)**: `.rela.dyn` on the reference lib holds **1,043,633
  entries**; reading it into a flat `Vec<Relocation>` and copying the supported
  ones was a **64 MiB transient spike**. Now: stream in bounded `BLOCK_ENTRIES`
  blocks and filter as you read (`try_reserve` → `CoreError::OutOfMemory`);
  resolve each candidate's class name in the same pass (the 227,513-entry
  candidate vector is gone); release the dead relocation pages *before*
  `.data.rel.ro` is faulted in. Measured: phase heap 64 → <40 MiB, pipeline peak
  RSS 86.4 → **53.7 MiB**, scan peak 21.2 MiB, output unchanged (4,688 vtables).
- **`.rela.dyn` is NOT sorted by offset** (13,418 out-of-order entries), so the
  sorted index is load-bearing — do not "simplify" it into a streaming merge.
- **Guard**: `tests/rtti_memory_test.rs` budgets the phase's own heap with a
  counting allocator and failed at 64 MiB before the fix. Reach for this pattern
  whenever a phase's allocation is sized by the input.
- **Error kinds** cross JNI as `kind: detail`. Keep three places in sync:
  `jni_bridge::throw_core_error`, `EnginePayloads.kindOf`, and
  `ErrorMapper.fromEngine`. `ErrorMapperTest` enumerates every kind and fails if
  two share a message.
- **Open (device-only)**: the scan phase reported **165.3 MB** on device where
  the host measures 21.2 MiB. Not reproduced; suspects are the two concurrent
  `ProgressCallback` objects and the JNI string marshalling of the payload.

### Gson + R8 release shrink trap (fixed)
- **Symptom**: release-only failure `JsonIOException: Abstract classes can't be
  instantiated! ... Class name: com.chimerateam.offsetextractor.data.native.e`.
  The one-letter name is the obfuscated `ExtractionPayload`.
- **Cause**: `app/proguard-rules.pro` kept `...offsetextractor.model.**` and
  `...data.model.**` — **neither package exists**. The real Gson models live in
  `data/native`, `data/export`, `data/cache`, `data/patterns`, `ui/state`, so R8
  stripped their constructors and marked them abstract.
- **Fix**: keep rules for those five packages (`<init>(...); <fields>;`) plus
  `-keepattributes Signature` (else `List<PatternHit>` arrives as raw `List`).
- **Guard**: `scripts/verify_release.py` `check_gson_models` parses every
  `classes*.dex` with the pure-Python reader `scripts/dex.py` and fails if any
  class in `GSON_MODEL_CLASSES` is missing, abstract, or constructor-less. A
  `ProguardRuleTests` in `scripts/tests/test_verify_release.py` fails if the
  rules and that list drift apart, or if the dead package names return.
- **Error path**: `EnginePayloads.decode` maps a non-syntax `JsonParseException`
  to `EngineErrorKind.Payload`; `ErrorMapper` renders a clean message instead of
  leaking the obfuscated class name. `ResultCache.load` catches `JsonParseException`.
- **Verify locally**: build `assembleRelease` then `python3
  scripts/verify_release.py` should print `Gson models: 30 models instantiable`.
  CI uploads `app/build/outputs/mapping/release/` as the `r8-mapping` artefact.
