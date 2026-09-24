# AGENTS.md — Forgery

Android app (Stable Diffusion client): Kotlin 2.0.21, AGP 8.7.3, compile/target SDK 35, min SDK 29, Java 17 toolchain. NowInAndroid-style modularization.

## Build (non-obvious)

- **No Gradle wrapper in repo** — there is no `./gradlew`. Use system `gradle` (SDKMAN, 9.5.0):
  - `gradle :app:assembleDebug` — full APK (slow, KSP+Hilt+Room)
  - `gradle :<path>:compileDebugKotlin` — fast compile-only check, e.g. `gradle :feature:generate:impl:compileDebugKotlin`
  - `gradle :<path>:testDebugUnitTest` — single module unit tests, e.g. `gradle :core:data:testDebugUnitTest`
- Root pipeline scripts (prefer them over raw `gradle` for full flows; all `set -euo pipefail`, `cd` to repo root, executable):
  - `./build_debug` → `:app:assembleDebug` (prints APK path)
  - `./build_release` → `:app:assembleRelease` + `:app:bundleRelease` (prints APK/AAB/mapping paths), signed; reads the key from `local.properties` (`RELEASE_KEYSTORE_FILE/PASSWORD`, optional `RELEASE_KEY_PASSWORD`, same names as android_crm) with `FORGERY_KEYSTORE_*` env override, forwarded as `-P` props to the guarded `release` signingConfig in `app/build.gradle.kts` (unsigned release when absent). Fail-fast when the key is missing. Never commit secrets — `local.properties`/`*.key` are gitignored.
  - `./deploy [debug|release]` (default debug) → builds the type, then installs: `ANDROID_SERIAL` wins → single device → prefer physical over emulator → else error. `adb install -r`, prints device model.
  - `./run_tests [gradle-args…]` → `testDebugUnitTest` across all modules (no flavors, no `androidTest`/connected suites — no emulator needed); extra args forwarded, e.g. `./run_tests :core:data:testDebugUnitTest`.
- `RepositoriesMode.FAIL_ON_PROJECT_REPOS` — declare repos only in `settings.gradle.kts`, never in module files.
- `TYPESAFE_PROJECT_ACCESSORS` is on — reference modules as `projects.feature.generate.impl`, not `project(":...")`.
- Convention plugins in `build-logic/convention/src/main/kotlin` are the source of truth: `forgery.android.application`, `forgery.android.library`, `forgery.android.library.compose`, `forgery.android.feature` (= library + compose + hilt + serialization, plus `core:ui`/`core:designsystem`/nav deps), `forgery.hilt`, `forgery.jvm.library`. Don't hand-add Hilt/Compose/serialization versions — apply the convention plugin. Versions live in `gradle/libs.versions.toml`.
- `gradle.properties`: parallel + caching + configuration-cache on, `android.nonTransitiveRClass=true`, `kotlin.code.style=official`.

## Structure

- `app/` — only wiring: `MainActivity` (@AndroidEntryPoint, wraps `ForgeryTheme`) → `ForgeryApp` (Hilt + WorkManager via `HiltWorkerFactory`) → `ForgeryNavHost` (6-tab bottom bar GEN/INP/QUE/GAL/ANA/CFG, start = `GenerateRoute()`). Depends on all `feature:*:impl` + `core:{common,ui,designsystem,data,model}`. Quirks: android `namespace` is `com.forgery.app.app` (double `app`) while `applicationId` is `com.forgery.app` (v2.5, versionCode 11); `app/.../queue/QueueService.kt` is a placeholder stub.
- 11 features: `generate`, `inpaint`, `queue`, `gallery`, `lora`, `styles`, `magicprompt`, `settings`, `power`, `comfy`, `analyze` (each `:api` + `:impl`). `feature:comfy` is included in `settings.gradle.kts` and `app` deps but **not registered in `ForgeryNavHost`** (deferred) — don't assume every feature module is navigable.
- `core/model` — pure JVM/Kotlin module (serialization + datetime), no Android. Everything else is an Android library.
- `core/` ownership: `common` (Result + shared utils, → `model`); `database` (Room, KSP) / `network` (Retrofit + kotlinx-serialization) / `datastore` (Preferences) → all on `model` + `common`; `data` (repositories, WorkManager) → `model`, `common`, `database`, `network`, `datastore`; `ui` (`DraftTextField`, `Dropdown`, `States`) + `designsystem` (`ForgeryTheme`) feed feature `impl`s via the `forgery.android.feature` plugin.
- `feature/<name>/api` vs `/impl`: `api` holds **only** the `@Serializable` route (`GenerateRoute`, …) + `*_ROUTE` const + `navigateToX()` helper; `impl` holds Screen + ViewModel + UiState + `*Navigation.kt` (`NavGraphBuilder` extension like `generateScreen(...)` with `composable<GenerateRoute>`) + optional `di/` and helpers (e.g. `AnalyzeImageSource`, `GalleryFiles`, `ImageAttachmentReader`). New feature = copy this split + register in NavHost + add `include()` in `settings.gradle.kts` + `implementation(projects.feature.<n>.impl)` in `app/build.gradle.kts`.
- `core/testing` — single file `Testing.kt`: `ForgeryTestRunner` (Hilt test app) + `TestDispatcherRule` (defaults to `UnconfinedTestDispatcher`). Library modules default to `AndroidJUnitRunner`; the `forgery.android.feature` plugin and `app` override to `ForgeryTestRunner`.

## Tests

- JUnit4 + `kotlinx-coroutines-test` + Turbine. No mocking library — hand-write fakes (see `feature/generate/impl/src/test/.../GenerateViewModelTest.kt`) and drive ViewModels with `runTest` + `TestDispatcherRule`.
- Room/Retrofit/Hilt all use KSP — after touching `@Entity`/`@Dao`/`@Module`/`@HiltViewModel`, expect generated code and prefer a module compile before running tests.

## Forge Neo API doc

- `docs/forge-neo-api.md` mirrors the server's sdapi (branch `neo`, endpoints ✅ used / ❌ unused, DTOs, schedulers table).
- When wiring a previously-❌ endpoint (new `ForgeService` method / repository fetch): flip its status to ✅, add the caller + field mapping, and extend the DTO section if the shape is new.
- When the server branch/commit moves: re-check `modules/api/api.py` (routes), `modules/api/models.py`, `modules/sd_schedulers.py` and update the doc header + affected sections.

## Stale skill docs — do not follow blindly

- `.opencode/skills/bug-fix/SKILL.md` and `emulator/SKILL.md` reference `./deploy`, `./build_debug`, `./build_release`, and task `compileGooglePlayDebugKotlin`. **Flavor tasks don't exist**: no product flavors (only `debug`/`release`; debug has `.debug` suffix). The root scripts **do** exist (see Build) — prefer them; install manually: `adb -s <serial> install -r <apk>`.
- Emulator skill's package `com.belyisklad` is wrong — real id is `com.forgery.app` (`com.forgery.app.debug` for debug builds). Same for its `pm list packages | grep belyisklad` troubleshooting line.
- Bug-fix skill's smoke-test domains (POS → cart → checkout, inventory CRUD, settings → backup) don't match this app (Stable Diffusion client) — translate to Generate/Inpaint/Queue/Gallery flows instead.
- Emulator facts from that skill that ARE correct: AVDs `phonepixel` (default), `tablet10`, `tablet7`; emulator binary is **not** in PATH (use `"$ANDROID_HOME/emulator/emulator"`); `adb` is in PATH; run headless via `xvfb-run` (never `-no-window` — qemu segfaults), poll `sys.boot_completed`, and always pass `-s emulator-5554` (a physical device is often attached).

## Subagents — parallelize independent work

- When a task splits into independent chunks (different modules/files, no shared edits), fan out via the Task tool (`general` for implementation, `explore` for codebase research) instead of doing everything sequentially.
- Give each subagent exact file paths + verbatim specs (fresh context): what to change, what to keep, what names/signatures the shared API uses.
- One owner per file — never assign overlapping edits to two agents.
- Run shared verification (module compiles, unit tests) centrally afterwards: concurrent `gradle` invocations in one project serialize on locks and slow each other down; if an agent hits a file lock, it waits ~60s, retries once, then reports.
- Don't parallelize what is small or sequential — direct execution beats orchestration overhead.

## Misc

- No README, CI, lint/detekt config, or git repo. `local.properties` exists (gitignored: `sdk.dir` + `RELEASE_KEYSTORE_*` for `./build_release`) — ensure `ANDROID_HOME`/SDK present before building.
- Free compiler arg `-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi` is global (see `KotlinAndroid.kt`) — don't add per-file opt-ins for it.
