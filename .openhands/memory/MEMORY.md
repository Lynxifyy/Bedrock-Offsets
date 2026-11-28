# Project memory

Index only. Detail and session notes live in the daily logs (`YYYY-MM-DD.md`).

> Other repos this workspace has touched: **FreeHands** (`Lynxifyy/FreeHands`, a fork of OpenHands
> Agent Canvas) + `Lynxifyy/software-agent-sdk-2.0` + litellm. Its notes are in the 2026-11-06 /
> 11-09 / 11-10 daily logs. Nothing below applies to it.

## CaramelClient (`ChimeraAnt-DEV/CaramelClient`) — Android launcher + SweetLoader

The repo was called **GlowberryClient** before; the package is `org.chimeramc.client` either way.

### UI skeleton state (2026-11-14: legacy UI deleted, then the pack UI ported back)
The launcher UI + the in-game Mod Menu UI were removed in one sweep, keeping every system; the
Caramel UI pack was then ported back in on top of current `main` (PR #185 + SweetLoader #40). **Do
not re-add the old View-based UI** — the pack port is the way forward. The port **did not** restore
the pack's demo data (`Versions`/`ExampleMods`/`ExampleServers`): `CaramelLauncherState` defaults
every list empty and `MainActivity` fills it from `VersionManager`/`MsftAccountStore`/`ModManager`,
so empty screens draw empty states (honesty rule). See the 2026-11-14 daily log for the full list.
- **`ui.activities` files live under `launcher/ui/activities/`** (dir ≠ package for the whole
  `launcher/ui` tree — package decls are `org.chimeramc.client.ui.*`). Operate on FILES, not dirs.
- **Never "fix" a Java file's package to match its directory.** The whole app has dir ≠ package
  (`launcher/core/auth/storage/XalExporter.java` declares `org.chimeramc.client.core.auth.storage`;
  `launcher/util/JsonIOUtils.java` declares `org.chimeramc.client.util`). Kotlin resolves Java
  classes by the DECLARED package, so rewriting the decl to match the path makes
  `:app:compileDebugKotlin` fail with `Unresolved reference '<last segment>'` on the Kotlin import
  (and it looks like a whole-package failure, not a file failure). Copy the existing `package` line.
  javac also needs explicit FILE lists (`-sourcepath` can't find dir≠package sources).
- 7 placeholder Activities remain (`BaseActivity` + `MainActivity`/`SplashActivity`/`LibraryActivity`/
  `SettingsActivity`/`ModsFullscreenActivity`/`CrashActivity`); they draw nothing but are manifest
  targets. `MainActivity.setTextMinecraftVersion()` and `LibraryActivity.EXTRA_INITIAL_TAB`/`TAB_*`
  are kept no-op API other code still calls. Layouts 94→20; the kept 20 are the game-host/HUD ones.
- **The in-game menu was 3 layers, all cut, then the native one restored** (SweetLoader #40): native
  ImGui (`sweetloader/src/pl/menu/` + vendored `third_party/imgui/`, consumed only by
  `PresentFrameEffect.cpp`), the Java bridge (`PreloaderMenu`/`InGameMenuController`), and the
  launcher overlay widgets (`MoreButtonsEditor`, `VoicePanel`/`VoiceUiKit`, `CosmeticsPanel`, still
  cut). `DebugMenuOverlay` is the F3 overlay MOD — keep it. The menu is drawn from the present hook
  **with no full-screen dim scrim**, and the host skips the whole ImGui frame while the menu is closed.
- **Kept (a new UI will reuse them)**: `UiTokens`, `Widget.Caramel.Card*`, `TextAppearance.Caramel.*`,
  `GlowDimens`, generic `bg_*`/`ic_*` drawables, `PersonalizationManager`, all `core.*` systems.
  Removed only the dead legacy chrome (`splash_bg_*`, `launch_bg_*`, dock/nav drawables + styles).
- Gate that catches dead drawables: `LayoutResourceTest` (unreferenced layout) fails the build —
  delete the layout. Hygiene: `scripts/check_ui_hygiene.py --update` retires stale findViewById
  baseline rows and shrinks the baseline when a file is deleted.
- **Trap**: a regex `<style name="X".*?</style>` over-matches a self-closing `<style …/>` and eats
  the next real style. Match `/>` explicitly.

### Native engine (git submodule `app/src/main/cpp/sweetloader`)
- Submodule repo is `ChimeraAnt-DEV/SweetLoader` (the old `preloader-android` name 301-redirects).
  **The parent must pin a commit that is on the submodule's `main`** — a `feat/*` tip can be
  unmerged, and then a clean checkout depends on that branch surviving. `scripts/check_submodules.sh`
  gates it. Check with `git -C <sub> merge-base --is-ancestor <pin> origin/main`, and note a pin can
  be *behind main by one* while still looking "on a branch" (`git log --oneline <pin>..origin/main`).
  `scripts/check_submodules.sh` does **not** check ancestry (only that no `branch =` floats and the
  checkout equals the pin), so a `feat/*` tip pin passes it — PR #185 pins `4ff2e63` on
  `feat/restore-pack-menu`, which must merge (SweetLoader #40) before/with it.
- **`.universe` and `.singularity` are the only mod formats** (`.AntEgg`/`.chimerapack`/`.levipack`
  are gone). Rust workspace `sweetloader/rust/`: `universe-core/-jni`
  (manifest/archive/signature/loadorder/permission/config/report/runtime/fault/version/patterns),
  `singularity-core/-jni` (noise/biome/chunk/mesher/fluid/physics/structure), `nethernet-core/-jni`,
  `caramel-cli`. `:app:buildRustEngine` cross-compiles arm64-v8a and **skips with a warning when cargo
  is absent**, so CI MUST install it (`rustup target add aarch64-linux-android`); `android.yml` also
  runs `cargo test --workspace`. **The dev container has no cargo/rustc**, so the Rust suite is
  CI-only.
- JNI owners: `org.chimeramc.client.universe.UniverseLoader`, `.singularity.SingularityEngine`; both
  fail closed on a host JVM instead of throwing. `JniBindingParityTest` also scans the `.rs` sources,
  so a new Rust export needs a matching Java `native` method (else `stale-export`).
- **A signature that matches more than once is refused** (`src/pl/memory/Signature.cpp`): it used to
  take the first hit, but `pauseMenuDtorSig` is a bare prologue matching **183 times** in 1.26.62.
  Ambiguous → resolves to 0 with a warning. **The rules file is signed and the private key is NOT in
  the repo** (`scripts/sign_sweetloader_rules.py --key <pem>`; `android.yml` runs `--verify`), so it
  must not be hand-edited. Shipped rules top out at 1.26.50.99.
- **BerryAPI**: ABI guard `scripts/verify_berryapi_abi.py` (+ its test) pins the 14 dlsym'd `BERRY_*`
  symbols and the seam/replay/governor ABI numbers against the bundled `libBerryAPI.so`.
  **`BERRY_Inject` / `BERRY_IsAttached` return `int` (0/1), not C++ `bool`** — a 1-byte bool return
  leaves the upper result register undefined. Upstream is `Lynxifyy/BerryAPI` (clone to /tmp for
  headers; ABI 8). Cosmetic C ABI is bound (PR #176 + SweetLoader #33) but **the bone half is not
  done**: `set_player_model_hook` needs the preloader to resolve the per-build player-render target
  itself, so nothing renders yet and the JNI entries only report hook state.
- **Module catalogue**: `PvpModuleCatalog` = 200 entries (38 `link()` + 162 `add()`); honestly
  **50 reachable/toggleable** and **112 disabled/badged** (52 `ENGINE_HOOK` + 60 `SERVER_DECIDED`).
  Pinned by `PvpModuleScopesTest.theBadgeCountsMatchTheAuditNote`. The 52 need a **complete world
  seam** (the preloader binds only `get_local_player`; the stripped `libminecraftpe.so` exposes no
  entity-list/level anchor) plus a signature pack for the active build; the 60 cannot be enabled by
  any client code.
- **Setup wizard**: `SetupWizardActivity` is a self-sizing popup over `MainActivity`; each step shows
  only its own block; finishing calls `finishWizard()` → `MainActivity` (never a controller screen).
  Pinned by `SetupWizardLastTabsTest`.

### UI
- The UI pack (`Lynxifyy/CaramelClient-UI-pack`) is a Compose **Desktop** app plus a GLFW Dear ImGui
  demo, not an Android library. **Its tokens are the single design reference**, carried by three live
  sources that must agree: the shared theme `ui/compose/theme/` (`GlowPalette.java` + `GlowTheme.kt` +
  `GlowGlass.kt`, used by account/connect/replay), the pack component port `ui/compose/caramel/`
  (`CaramelTheme.kt` + `CaramelControls/Labels/Glass.kt`, used by the launch screen; `CaramelColors` =
  the pack's `theme/Theme.kt` value for value incl. amber/greens the shared scale lacks), and the
  in-game menu `sweetloader/src/pl/menu/MenuTheme.*` + `MenuWidgets.*` (token for token).
  `CaramelPaletteParityTest` pins the overlap equal to `UiTokens`/`GlowPalette` and the ImGui twin
  equal to the Compose port. (An earlier `caramel/Caramel*` layer was dead code and deleted; it is
  live again because the launch screen imports it — check for importers before calling it dead.)
  Never wire the GLFW backend. PR #183 + SweetLoader #38.
  Hazards (Haze blur model, hardcoded `Text` literals, `(size / 2).dp`, version skew, and a preview
  apple that is not the launcher art) are in `docs/agents/ui-pack-review.md`.
- **The mod menu is the native ImGui one; the Compose/View mod menu and the radial wheel are deleted**
  (`ui/compose/modmenu/`, `ModMenuOverlay`, `RadialMenu*`/`Wheel*`, their layouts). `PreloaderMenu`
  (Java) ↔ `pl/menu/MenuJni.cpp` is the only bridge; `ModMenuNavLayoutTest` fails if a deleted layout
  comes back or the native tab bar loses its single labels+icons source.
- **Dear ImGui 1.91.5 is vendored at `sweetloader/third_party/imgui/`** (the configure step must not
  need the network). `check_pinned_deps.py` only inspects `GIT_REPOSITORY` pins, so a vendored dep
  just needs a NOTICE row.
- **Frame generation + motion blur are present-frame effects, not frame-rate gains.** Both live in
  `core.mods.render.PresentFrameCatalog`/`PresentFramePolicy` and run on the `eglSwapBuffers` detour
  (`pl/render/PresentFrameEffect.cpp`); they blend the previous presented frame in, present no extra
  frame, and are badged while the present hook is absent. `PresentFrameModulesTest` forbids any
  frame-rate claim in their copy.
- **Animated values that cross zero belong on `offset`, never `padding`.** `Modifier.padding` throws
  `IllegalArgumentException: Padding must be non-negative`; a drift of `(value - 0.5f) * 6f` is
  negative on the first frame, so `LaunchChangelogCard` threw on **every** launch.
  `check_ui_hygiene.py` now has a 4th, baseline-free rule enforcing this (only `padding` —
  `size`/`width`/`height` legitimately take computed values).
- **Two Android CI gates fail locally-silent changes** (both run before the Gradle build, so they fail
  fast): `scripts/check_ui_hygiene.py` fails on *new* `findViewById` beyond a per-file baseline (read
  the generated ViewBinding instead, or `--update` with a reason) and on a runtime `padding` argument;
  `./gradlew :app:lintDebug` fails on a new `MissingSuperCall` — do not override `onBackPressed()`
  without `super`, register an `OnBackPressedCallback` instead. **It also has a standing failure on
  `main`**: `BaseActivity.java:75` `NewApi` (`WindowInsetsController#show`, API 30) is not in
  `app/lint-baseline.xml`, so lint cannot be used as a per-task gate — compare against `main` or
  rely on compile + tests + `check_ui_hygiene.py`.

### Git / PR flow
- `$GITHUB_TOKEN` has **full repo scope here** — `git push`, REST, GraphQL and the async merge all
  work. `$GithubLynxifyy` is **not injected** (401) — do not reference it.
  `$OH_GIT_CREDENTIAL_ACCESS_TOKEN_GITHUB` is git-credential-helper only. The parent `origin` remote
  embeds a working `ghu_...` credential, so a plain `git push` works; **the submodule remote has no
  credential helper** — push it with
  `git -c credential.helper='!f() { echo username=x-access-token; echo password=$GITHUB_TOKEN; }; f' push`.
- **`git push` can be rejected GH007 "publish a private email address"** when the configured
  `user.email` is private on GitHub. Fix: set the repo-local identity to the noreply address
  (`297587416+Lynxifyy@users.noreply.github.com`) then
  `git rebase origin/main --exec 'git commit --amend --no-edit --reset-author'`.
- Commits here: `Lynxifyy <297587416+Lynxifyy@users.noreply.github.com>`, no co-authors, **no AI
  mentions in PRs**. Conventional PR titles only or the `pr-title` check fails (fixable with
  `PATCH /pulls/{n}`).
- **PR flow**: `create_pr` opens a **draft** and the merge API refuses drafts; REST `PATCH
  {"draft":false}` does NOT clear it — use GraphQL `markPullRequestReadyForReview` with the
  `node_id`. Then `PUT /pulls/{n}/merge-async {"merge_method":"squash","sha":"<40-char head>"}` (a
  short sha is rejected) and poll `.../merge-async/{uuid}`. `gh` CLI works.

### Verify
- `JAVA_HOME=/workspace/tools/jdk-21.0.13.1+2 ANDROID_HOME=/workspace/android-sdk ./gradlew
  :app:testDebugUnitTest` (**3397 tests, 0 failures, 1 skipped** on the BETA6 snapshot; targeted via
  `--tests "org.chimeramc.client.<pkg>.*"`). `:app:assembleDebug` is the only thing that compiles the
  NDK C++ (`Signature.cpp` is not host-compiled).
- **A fresh container needs the native submodules before ANY gradle task**: `preBuild` depends on
  `verifyNativeSubmodules`, which fails the build if `app/src/main/cpp/sweetloader/CMakeLists.txt` or
  `.../libHttpClient/Build/libHttpClient.Android/CMakeLists.txt` is absent. Run
  `git submodule sync && git submodule update --init --recursive` (the openssl tree under
  libHttpClient pulls dozens of nested submodules; takes minutes). `local.properties` is gitignored —
  write `sdk.dir=/workspace/android-sdk`. The Adoptium "latest 21 GA" URL now resolves to
  `jdk-21.0.13.1+2` (memory previously said `jdk-21.0.13+9`); the SDK needs
  `platforms;android-35/36` + `build-tools;35.0.0/36.0.0` for the unit-test task (NDK not required
  for `:app:testDebugUnitTest`).
- The cosmetics host C++ runner is at **`app/src/main/cpp/sweetloader/tests/cosmetics/run_tests.sh`**
  (not the repo root). `cargo test --workspace` (needs cargo),
  `scripts/check_ui_hygiene.py`, `scripts/gen_launcher_icons.py --check`,
  `scripts/verify_berryapi_abi.py`, `scripts/check_submodules.sh`,
  `scripts/validate_release_content.py`, `actionlint .github/workflows/android.yml`.
- **A cloud phone is available (Mobilebox, Android 14 arm64, $0.01/min while running)**: the launcher
  *can* be driven and screenshotted from this container — recipe, traps and what it verified live in
  `docs/agents/runtime-verification.md`. `:app:assembleDebug` needs Rust (`rustup` +
  `rustup target add aarch64-linux-android`) or it fails with "cargo was not found"; `open_app` does
  not launch our app, tap `"CaramelClient"` in the drawer instead. Park the phone when done. A
  Microsoft sign-in still needs the account owner's password + 2FA, so game-side XAL, the in-game
  "Signing in…" text and in-game skin/FPS stay unverified: say so rather than claiming a measurement.
- **Device-only work beyond the launcher's own screens cannot be measured here**: any hook/scanner
  behaviour against a real `libminecraftpe.so` cannot be executed. Most remaining "engine hook"
  features need a game-side anchor the stripped `libminecraftpe.so` does not expose —
  `docs/agents/update-list-audit-*.md` records this per module.
- `version.properties` (`version.name`) is what CI stamps when a shallow checkout has no tags; keep
  it ahead of the last tag or CI builds name a stale version.
- **SweetLoader CI** (`.github/workflows/ci.yml`, 4 gates: host-tests, rust, native-build,
  repo-integrity). Two traps: a CI run with 0 jobs and no annotation = the workflow FILE was rejected
  (diagnose with `actionlint`, not the API); and `set -o pipefail` + `cmd | grep -q` exits 74
  (SIGPIPE) even on a match — dump to a file and grep the file. The Rust tree is rustfmt-normalized;
  keep it so.

### Hygiene gates (the four that fail a local change)
- **`FileSizeBudgetTest`** (`scripts/hygiene/file-sizes-baseline.txt`, threshold 1500 lines): a listed
  file may only **shrink**. `InbuiltModuleProvider.java` was at its 1765 baseline; the fix is to
  **split**, not to edit the baseline. It was split into `ModConfigSchema.java` (config keys +
  `ConfigEntry` list + `RuntimeConfigSchema` builders, 1175 lines) and the provider (748). The
  provider keeps thin `static schemaTypeFor`/`configDescriptionRes` delegators so
  `InbuiltModuleProviderTypeTest` (same package) still compiles; the moved members are package-private
  and the provider calls them as `ModConfigSchema.x(...)`.
- **`ModuleDescriptionsTest`**: every `inbuilt_mod_*_desc` in `values/strings.xml` must be ≤ **80**
  chars (target 60) and one sentence. A long "weighted by how fast the view is moving" tail is the
  usual miss — keep it in the `*_scope_note` instead.
- **`TranslationCoverageTest` / `check_ui_hygiene.py` translation baseline**: a new string must be
  translated in all 10 locales (`es fr hi id ja pt ru tr vi zh-rCN`) or marked `translatable="false"`.
  Add a `scripts/translations_*.json` batch, register it in `BATCHES` in
  `scripts/apply_translations.py`, then run the applier (it is idempotent and refuses a dropped `%s`).
  After applying, `check_ui_hygiene.py` may report baseline entries that "no longer exist" — retire
  them with `python3 scripts/check_ui_hygiene.py --update` (that also refreshes the findViewById
  baseline, which *shrinks* when a `findViewById` is replaced by a ViewBinding read).
- **`NoSilentCatchTest`**: a `catch` that discards the throwable fails. Use
  `AppLog.warnOnce(tag, key, message, e)`, even for an expected `UnsatisfiedLinkError` fail-closed path.
- **`NoEmojiInUiTextTest` / `NoEmojiTest`**: no emoji/glyph codepoints in client UI strings (a `★` in
  a Compose `FilterChip` label fails it). **`Api28CompatTest`**: shipped sources must not call an API
  newer than 28 — `String.isBlank` (33) is the usual slip, use `s.trim().isEmpty()`.
- Baseline format note: the baseline files live under **`scripts/hygiene/`**, not `scripts/`.

### Mobilebox cloud phone (2026-11-28)
Key is in `$MOBILESANDBOX_API_KEY` (workspace `prj_33khrd52ucmr`, `android_cloud`, ~$12.94 left,
$0.01/min). `claude` CLI is **not** installed here, so `claude mcp add …` is not the path — use REST:
`POST /v1/phones?wait=ready`, `GET /v1/phones/{id}/screenshot` (raw PNG), `GET …/device`,
`POST …/park` (stops billing). A phone is a real Android 14 / 1080x2400 arm64 device, but it cannot
prove anything about a game it does not have: no 1.26.60 APK and no MSA sign-in, so the XAL export
path stays device-UNVERIFIED.

### XAL export contract (the "Sign In although the launcher is signed in" fix, 2026-11-28)
- `com.microsoft.xal.androidjava.XalStorageContract` (in the `minecraft` module, the copy of the
  game's Java that runs in the launcher's process) now owns: `xal/` layout, `Xal.Accounts.json`,
  `Xal.Current.json` (the export pointer), base64 id encoding, and which crypto prefs to use.
  `Storage.getStoragePath` returns `files/` only when there is NO export and the
  `launcherManagedMcLoginEnabled` toggle is off — the toggle alone used to decide, so a default
  install read a store the exporter never wrote.
- `XalExporter` (package `org.chimeramc.client.core.auth.storage`) builds its file set from
  `XalExportBundle` (pure, JVM-tested) and writes: DeviceIdentity, Default, Msa, User with
  envelopes Dtoken/Utoken + Xtoken for xboxlive, multiplayer.minecraft.net, playfab, realms,
  `rp://api.minecraftservices.com/` (from `javaXstsToken`, minted by `JavaAuthManager` in
  `MsftAuthManager.refreshStoredAccount` and merged into the Bedrock snapshot).
- Export runs on every launch (`MinecraftRuntimePreparer.exportAccountForLaunch`) and after every
  refresh (`MsftAccountStore.updateAuthMaterial` → `XalExporter.refreshExportedAccount`).
- **Device-key encoding is a real trap**: `MinecraftAuth`'s `JsonUtil.encodeKeyPair` writes
  standard Base64 (`+`, `/`, `=`); the game's `Ecdsa.restoreKeyAndId` decodes with
  `Base64.decode(str, 11)` = `URL_SAFE|NO_PADDING|NO_WRAP` and throws. The export must re-encode
  (`XalExportBundle.urlSafeBase64`). Any exporter rewrite that drops it silently breaks XAL
  proof-of-possession signing and the game sits on "Signing in with your Microsoft account".
- `javaXstsToken` (the `rp://api.minecraftservices.com/` party) needs a Java XSTS exchange that the
  offline exporter cannot do: mint it in `saveAccountOrThrow` **and** in `refreshStoredAccount`, and
  let `refreshAccountsForLaunch` refresh on `needsMinecraftServicesToken(...)` too, not only on the
  twelve-hour rule.
- `:app:testDebugUnitTest` = 3432 tests, 0 failures, 1 skipped (warm ~25 s).

### Compose launcher (2026-11 launcher round)
- **`app/src/main/java/org/chimeramc/client/launcher/core/versions/` files declare
  `package org.chimeramc.client.core.versions`** (no `launcher`). A new file there must use that
  package or `VersionManager` cannot see it ("package ImportedPackageLayout does not exist").
- `VersionManager.java` sits at its `FileSizeBudgetTest` baseline (1505) — it may only shrink, so new
  import logic goes into a sibling class (`ImportedPackageLayout`, `ArchiveExtractor`).
- **`assembleDebug` cannot run in this container**: `:app:buildRustEngine` fails with "cargo was not
  found". `:app:testDebugUnitTest` + `compileDebugKotlin` are the verifiable gates here; an APK
  needs the Rust toolchain (and `aarch64-linux-android`).
- Unit tests get cwd = the module dir, so repo-relative paths need a "walk up until the file exists"
  helper (see `RadiusScaleTest.resolve`); a bare `Paths.get("app/src/...")` fails.
- Vector-drawable attributes are namespaced: parse with `setNamespaceAware(true)` +
  `getAttributeNS("http://schemas.android.com/apk/res/android", "pathData")`.
- `Api28CompatTest` flags `Stream.toList` on `EnumEntries`/lists — pass the `entries` list straight to
  LazyRow's `items(...)`.
- CurseForge Bedrock (`gameId=78022`) class ids: Addons 4984, Maps 6913, Skins 6925, Texture Packs
  6929, Scripts 6940; `sortField=6` is total downloads.
- Shell heredocs with long/odd content get **mangled in transit sometimes** (a python script's own
  string literal was corrupted and failed its `assert`). For big Java/Kotlin edits write the block to
  a temp file, `grep` it to confirm, then splice by line index in a short script. `file_editor`'s
  `str_replace` needs byte-exact text — blank lines in these sources carry trailing spaces.

### Bedrock-Offsets build + internal shard layout (2026-11-28)
- `scripts/setup_toolchain.sh` hardcodes JDK dir `jdk-21.0.13+9`, but the Adoptium archive extracts
  `jdk-21.0.13.1+2`; symlink or the script dies at `sdkmanager` with an invalid `JAVA_HOME`.
- `cargo install cargo-ndk --locked` is **required** for `:app:testDebugUnitTest`:
  `:app:buildRustEngine` runs `scripts/build_rust.py`, which shells out to `cargo ndk`. Without it the
  build fails before any test. `buildRustEngine` succeeds here once cargo-ndk + NDK are present.
- Category scans write shards **directly into `cache/internal`** as `<category>.json` plus `map.json`
  (`shards.rs`), and `InternalSummary.path` is blank in that mode. Any keep logic keyed on `path`
  therefore protects nothing; derive the directory from `shards[0].path.parent` and treat the whole
  directory as the result set (`StorageManager.keepState` / `newestTable`).
- `StorageManager.newestTable()` must consider `.json` shards and directories, not only `.ndjson`,
  or a restart sweep deletes the previous scan's cache before `cachedResultFor` can use it.
- Gates here: `cargo test` (208 unit + integration), `gradle :app:ktlintCheck`,
  `gradle :app:testDebugUnitTest` (373 tests, all green). `cargo fmt --check` + `cargo clippy
  --all-targets` are clean.

### Bedrock-Offsets v1.1.0 release (2026-11-28)
- v1.1.0 is the first non-prerelease of the 1.1 line (`versionCode 19`). Published asset:
  `bedrock-offset-extractor-v1.1.0.apk`, SHA-256
  `1f3ed86edc64aceb9a9a1a010e150dd62526228d81d2c8e6cb92fc0054ffaed5`.
- The persistent release keystore lives **outside the repo** at
  `/workspace/release-signing/` (`bedrock-offsets-release.jks`, `keystore.b64`, `store_password`,
  `README.txt`; all mode 600). Certificate SHA-256:
  `BB:ED:38:B2:23:94:B3:0E:1C:21:20:C3:A2:64:EE:F0:F4:B2:BA:78:C8:B5:C3:2C:58:D0:2A:6E:DA:16:5D:5C`.
  Never commit it.
- The current GitHub token is a `ghu_` user-to-server token without secrets permission: the Actions
  secrets API returns 403. The four `SIGNING_*` secrets must be added by a repo admin; until then
  `release.yml` hard-fails instead of publishing a debug-signed APK. It pins the certificate
  fingerprint above, so a different key also fails.
- Creating a release/tag through the GitHub API **does trigger** the tag-push `release.yml` workflow
  (the v1.1.0 run was cancelled after the locally built APK was published). A draft release is
  `untagged` until published; publishing creates the tag.
- GitHub rejected pushes with `GH007` until the author email was changed to
  `297587416+Lynxifyy@users.noreply.github.com`; the existing commits used a private address.

### Internal C++ peak-RSS pass (2026-11-28)
- `ClassRecord.slots` shares every `Arc<SlotRecord>` with `vtable_groups[0].slots`. Clear
  `record.slots` before any `Arc::make_mut` pass, then restore the primary clone afterwards;
  otherwise every primary slot is deep-cloned. This applies to `apply_inheritance`,
  `unify_slot_names_by_address`, and the final signature/status pass.
- `signature::plan` now takes `PlanPages { releaser, text_file_offset }`. The planner works in
  text-relative offsets while `PageReleaser` expects **file offsets**, so a window must add the
  section's `sh_offset`; passing text-relative offsets silently releases ELF-header pages.
- `generate_signatures` takes `XrefTable` by value, copies only per-anchor data refs/first calls,
  then drops the full table before allocating the signature map. `classes` is drained before the
  function write loop and `slot_meta` is dropped after the class pass.
- 2026-11-28 follow-up (commit `2d76d19`): generated `Signature.masks` is now `Option`, so there is
  no second `Box<[u32]>` and masks are derived with `keep_mask`; the planner's sorted vector is
  reused in place (`SignatureMap = Vec`), binary-searched for slots and cursor-drained for
  functions. Slot metadata/extras are attached one class at a time. Vtable JSON is streamed by
  `resolveVtablesToFileNative` to a temp file and parsed via a Gson `Reader`; `ScanCache` has a
  streaming `writeDocument` overload and `ResultCache` streams store and load. A JDK exists at
  `/workspace/tools/jdk-21.0.13+9`, so Android tests are runnable here (374 tests pass).
- Gates after this pass: `cargo test --all-targets` = 210 unit tests (1 ignored) plus all
  integration targets, `cargo clippy --all-targets -- -D warnings`, `cargo doc` with
  `RUSTDOCFLAGS=-D warnings`, and `scripts/build_rust.py` (4,137,504-byte
  `liboffset_core.so`). `:app:testDebugUnitTest` runs with `JAVA_HOME` set (374 tests, 0 failures).
