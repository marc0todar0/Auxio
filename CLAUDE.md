# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Auxio is a local music player for Android, written in Kotlin. `dev` is the development branch (default); `master` holds stable releases.

## This is a personal fork

This checkout is a **personal fork** (`fork` remote, `marc0todar0/Auxio`) of upstream Auxio (`origin` remote, `OxygenCobalt/Auxio`), customized for the owner's own needs. Fork work lives on feature branches (e.g. `feat/m3u-auto-import`) that get rebased onto upstream `master`; push to `fork`, never to `origin`.

When resolving conflicts with upstream, keep both sides: take upstream's changes and re-apply the fork's customizations on top. Fork-specific points to watch:

- **Package identity**: the release build is `org.oxycblt.auxio.fork`, labelled "Auxio Fork" (`applicationIdSuffix ".fork"` in `app/build.gradle`, plus `app/src/release/res/values/donottranslate.xml`, which also overrides the `pkg_authority_cover` provider authority — it must stay unique per package). That file is force-added because `.gitignore` ignores `release/`.
- **Signing**: `./gradlew app:assembleRelease` produces an *unsigned* APK. Sign it with the fork keystore, which lives outside the repo at `~/.android/auxio-fork.jks` (alias `auxio-fork`, password in `~/.android/auxio-fork.pass`): `zipalign -p 4`, then `apksigner sign --ks … --ks-pass file:…`. Always use this key — a build signed with any other key cannot update the installed app.
- **Room schema versions** (`PlaylistDatabase`): the fork adds its own columns, so its version must stay *above* upstream's, with a migration from upstream's version to the fork's. Devices already running an older fork build need a migration from their fork-only version too — otherwise `fallbackToDestructiveMigration` silently wipes the user's playlists.

## Modules

The build has two Gradle modules plus a vendored ExoPlayer tree:

- **`:app`** (`org.oxycblt.auxio`) — the Android application: all UI, playback, services, settings.
- **`:musikr`** (`org.oxycblt.musikr`) — a self-contained music-loading library that scans the device, parses tags, and produces an immutable music library. It is designed to be stateless at its API surface and has no dependency on `:app`. Treat it as a reusable library, not app internals.
- **`:media-lib-exoplayer`, `:media-lib-decoder-ffmpeg`** — a **vendored fork of AndroidX Media3/ExoPlayer**, pulled in as the `media/` git submodule and wired up via `media/core_settings.gradle` in `settings.gradle`.

## Git submodules (required before any build)

The project does not build without submodules initialized:

- `media/` — the vendored ExoPlayer fork.
- `musikr/src/main/cpp/taglib` — TagLib C++ source, compiled via NDK for tag parsing.

```sh
git submodule update --init --recursive
```

## Common commands

```sh
# Build the debug APK (what CI runs)
./gradlew app:packageDebug

# Run musikr unit tests (the only test suite; app has no tests)
./gradlew musikr:testDebug

# Run a single test class / method
./gradlew musikr:testDebug --tests "org.oxycblt.musikr.tag.parse.*"
./gradlew musikr:testDebug --tests "*.SomeTest.someMethod"

# Formatting — Spotless (ktfmt kotlinlang style + license header). CI runs spotlessCheck.
./gradlew spotlessApply     # auto-format before committing
./gradlew spotlessCheck     # verify (CI gate)
```

Requirements: **JDK 21** for building, Android NDK (`28.2.13676358`), and `ninja-build` installed for the native TagLib compile. The `assembleTaglib` Gradle task runs `musikr/src/main/cpp/build_taglib.sh` automatically before debug/release builds; `./gradlew clean` deletes the TagLib `pkg`/`build` output, forcing a slow recompile next build.

Build tuning lives in `gradle.properties`: parallelism is deliberately disabled (`org.gradle.parallel=false`, `workers.max=2`) because the vendored native/media tree otherwise exhausts memory.

## Formatting rules

Every Kotlin and C++ source file must carry the GPLv3 license header from the `NOTICE` file — Spotless enforces this and will fail CI otherwise. Run `spotlessApply` before committing new files. Kotlin style is ktfmt kotlinlang; C++ uses the `eclipse-cdt.xml` profile.

## Architecture

### Music loading (`:musikr`)

Entry point is `Musikr.new(context, config).run(onProgress)`, which returns a `LibraryResult`. Loading is a multi-threaded coroutine **pipeline** in `musikr/.../pipeline/`:

`ExploreStep` (walk the filesystem, consult the cache) → `ExtractStep` (read tags via the TagLib JNI bridge) → `EvaluateStep` (build the graph).

`MusicGraph` (`musikr/.../graph/`) resolves raw per-song tags into the linked `Song`/`Album`/`Artist`/`Genre`/`Playlist` object model. Every item has a stable `Music.UID`. The model interfaces live at the module root (`Music.kt`, `Library.kt`); implementations are in `model/`. `Library` is immutable; `MutableLibrary` handles playlist edits. Side-effecting state (cache, cover storage, stored playlists) is injected via `Config`/`Storage` so the loader itself stays stateless.

The native layer (`musikr/src/main/cpp/`) is a JNI wrapper around TagLib: `taglib_jni.cpp` + `J*Ref`/`JMetadataBuilder`/`JTagMap` marshal tags into Kotlin. Metadata parsing/interpretation on the Kotlin side lives in `musikr/.../tag/` (`parse/`, `interpret/`) — this is where the unit tests concentrate.

### App integration (`:app`)

- **`MusicRepository`** (`music/MusicRepository.kt`) is the app-wide singleton wrapping musikr. It owns the current `Library`, drives indexing, and notifies UI via `UpdateListener` (library changes) and `IndexingListener` (progress). `MusicViewModel` exposes this to fragments.
- **Playback** is layered: `PlaybackStateManager` (`playback/state/`) is the single source of truth for the queue/state; `PlaybackStateHolder` is the abstraction it delegates to, implemented by `ExoPlaybackStateHolder` (`playback/service/`) on top of the vendored ExoPlayer. `MediaSessionHolder` bridges to the Android media session / Android Auto. `PlaybackViewModel` is the UI-facing layer.
- **`AuxioService`** is a single `MediaBrowserServiceCompat` that composes two "service fragments": `PlaybackServiceFragment` and `MusicServiceFragment`. These are not UI fragments — they are the service's playback and library-loading halves.
- **UI** is single-activity (`MainActivity` → `MainFragment`) using the AndroidX Navigation component. `MainFragment` (large) coordinates the bottom-sheet layout: the playback bar/panel and queue sheets, with custom `CoordinatorLayout.Behavior` classes in `ui/` and `playback/`. Feature UI is split into `home/`, `detail/`, `list/`, `search/`, `settings/`, `widgets/`.
- **`IntegerTable.kt`** is a central registry of stable integer IDs (for intents, notifications, persisted state) — reuse from it rather than inventing magic numbers.

### Dependency injection

Hilt throughout. Each feature package has its own `*Module.kt` (`MusicModule`, `PlaybackModule`, `DetailModule`, `HomeModule`, `SearchModule`, `UIModule`, `SystemModule`, `PlaybackStateModule`, `CoilModule`, etc.). `Auxio` is the `@HiltAndroidApp` application; `AuxioService` and most fragments/dialogs are `@AndroidEntryPoint`. Settings classes (`*Settings`) wrap SharedPreferences and each have a `migrate()` called on app start.

### Persistence

Room is used in both modules — musikr for its scan cache and stored playlists, the app for playback-state persistence (`playback/persist/`). Cover images are cached via Coil 3.

## Version-pinned dependencies (do not casually bump)

Several dependencies are intentionally held back with inline comments explaining why in `app/build.gradle` — e.g. `fragment-ktx` 1.6.2 (predictive-back freezes), `recyclerview` 1.2.1 (queue sheet collapse bug), `viewpager2` 1.0.0. Read the comment before upgrading any pinned version.
