# AGENTS.md

## Project

- Android scrobbler for Last.fm and Libre.fm: Kotlin, Jetpack Compose, WorkManager.
- Two Gradle modules: `:app` (Android UI/service/storage) and `:core` (plain JVM logic and API client).
- Requires JDK 17 and the Android SDK (`local.properties` or `ANDROID_HOME`). Wrapper is Gradle 8.11.1.

## Commands

- CI-equivalent build and all tests: `./gradlew test assembleRelease`
- Core tests only: `./gradlew :core:test`
- One test class: `./gradlew :core:test --tests "io.github.teamomuito.larpfm.core.PlaybackTrackerTest"`
- Debug build: `./gradlew assembleDebug`
- Release APK: `app/build/outputs/apk/release/app-release.apk` (signed with the debug key if no release keystore is configured).
- No ktlint/detekt/Android lint task is wired up; Kotlin official style is set in `gradle.properties`.

## Architecture

- `MainActivity` hosts Compose; `LarpApp`/`AppGraph` is manual dependency injection, reached through `Context.graph`.
- `ScrobbleListenerService` is a `NotificationListenerService` that watches other apps' `MediaSession`s; notification content is never read.
- Flow: `PlaybackTracker` decides now-playing/scrobble events -> `ScrobbleRepository` queues them in SQLite -> `FlushWorker` -> `ScrobbleSubmitter` -> `LastFmClient`.
- Keep `:core` Android-free (no `android.*` imports). `org.json` is `compileOnly` for Android and test-only in Gradle; put playback, title/artist cleanup, and API logic there with JUnit 4 tests under `core/src/test/kotlin`.
- Only `:core` has automated tests; there are no Robolectric or instrumentation tests.
- `LastFmClient` handles both sites through `ScrobbleService`; Libre.fm uses a fixed API key because it does not register keys. Route site-specific URLs/credentials through `ScrobbleService`.
- The auth callback `larpfm://auth` is declared in `AndroidManifest.xml` and duplicated in `MainViewModel.AUTH_CALLBACK`; update both together. `MainActivity` is `singleTask` and ignores the token on recreation, so the one-time token is not reused.
- `ScrobbleDb` is schema version 3. Bump the version and add an `onUpgrade` migration when changing the schema; pending rows must survive upgrades.
- Settings/account state live in `SharedPreferences` via `Settings.kt` and are exposed as `StateFlow`.
- UI uses custom DS primitives in `ui/Ds.kt` (colors, DotGothic16 font, grid, notched panels, buttons, segments) and the fixed light theme in `Theme.kt`. Prefer these over raw Material components to preserve the Pictoclip look.

## CI and releases

- `.github/workflows/android.yml` runs `./gradlew test assembleRelease` on every push and PR.
- Every push to the default branch publishes a GitHub release and keeps the 5 newest. Do not create releases or tags manually.
- `versionName` is bumped by hand in `app/build.gradle.kts`; `versionCode` comes from `GITHUB_RUN_NUMBER` (1 locally).
- Last.fm keys and release signing are optional env/Gradle properties (`LASTFM_API_KEY`, `LASTFM_API_SECRET`, `SIGNING_*`), documented in `README.md`. Never commit keys or keystores.
