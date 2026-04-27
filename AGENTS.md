# PROJECT KNOWLEDGE BASE

**Generated:** 2026-04-28
**Commit:** 0b6ab41
**Branch:** main

## OVERVIEW
ShareHelper is a minimal Android share-target app. It receives shared image/video content, writes temporary processed copies under app cache, then opens the system share sheet again.

Stack: Kotlin, Android Gradle Plugin, AndroidX Core KTX, AndroidX ExifInterface, GitHub Actions release automation.

## STRUCTURE
```
ShareHelper/
├── app/                         # Single Android application module
│   ├── build.gradle.kts          # namespace/applicationId/version/signing
│   └── src/main/
│       ├── AndroidManifest.xml   # Share target + FileProvider wiring
│       ├── java/cn/yojigen/sharehelper/ShareActivity.kt
│       └── res/                  # strings, theme, launcher icons, file_paths
├── .github/workflows/release.yml # tag-triggered signed APK release
├── scripts/publish-release.sh    # local release helper
├── gradle/libs.versions.toml     # AGP/dependency versions
└── README.md                     # user-facing build/release notes
```

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| Change share receive/process/re-share behavior | `app/src/main/java/cn/yojigen/sharehelper/ShareActivity.kt` | Single runtime entry point; no UI layout. |
| Change Android share target filters | `app/src/main/AndroidManifest.xml` | Keep filters aligned with Kotlin MIME filtering. |
| Change FileProvider-visible cache paths | `app/src/main/res/xml/file_paths.xml` + `ShareActivity.kt` | `shared/` must match `SHARED_CACHE_DIRECTORY`. |
| Change app name or share/toast text | `app/src/main/res/values/strings.xml` | User-facing text is Chinese and resource-backed. |
| Change theme | `app/src/main/res/values/themes.xml` | Referenced by Manifest as `Theme.ShareHelper`. |
| Change package, version, SDK, signing | `app/build.gradle.kts` | `namespace` and `applicationId` are both `cn.yojigen.sharehelper`. |
| Change CI release behavior | `.github/workflows/release.yml` | Runs on `v*` tags and publishes signed APK. |
| Change local release behavior | `scripts/publish-release.sh` | Requires clean tree and `SHAREHELPER_RELEASE_*` env vars. |
| Change dependency versions | `gradle/libs.versions.toml` | Current dependencies are intentionally small. |

## CODE MAP
| Symbol | Type | Location | Role |
|--------|------|----------|------|
| `ShareActivity` | Activity | `ShareActivity.kt` | Receives `ACTION_SEND` / `ACTION_SEND_MULTIPLE`; processes media off main thread. |
| `processIntent` | function | `ShareActivity.kt` | Builds batch ID, output directory, and per-item export IDs. |
| `collectInputMedia` | function | `ShareActivity.kt` | Reads `clipData` and `EXTRA_STREAM`; de-duplicates URIs. |
| `processImage` | function | `ShareActivity.kt` | Copies image, writes EXIF `ExportID`, or re-encodes JPEG fallback. |
| `processVideo` | function | `ShareActivity.kt` | MP4 remux + metadata track; non-MP4 conservative copy fallback. |
| `shareProcessedMedia` | function | `ShareActivity.kt` | Builds outgoing share intent and grants URI read permission. |
| `SHARED_CACHE_DIRECTORY` | constant | `ShareActivity.kt` | Must stay aligned with `res/xml/file_paths.xml`. |

## CONVENTIONS
- Root project: `ShareHelper`; only Gradle module: `:app`.
- Package/namespace/applicationId: `cn.yojigen.sharehelper`.
- App name: `分享助手`; keep user-facing copy in `strings.xml`.
- `ID` spelling is intentionally uppercase in names such as `batchID`, `exportID`, and `ExportID`.
- Cache output only: `cacheDir/shared/batch_<timestamp>_<uuid>/`.
- Export files include type, index, and export ID in the filename.
- `FileProvider` authority is `${applicationId}.fileprovider`; Kotlin uses `$packageName.fileprovider`.
- Supported input classes are images and videos; mixed multi-share is valid.
- Manifest has a broad `ACTION_SEND_MULTIPLE */*` filter; Kotlin still rejects unsupported MIME types.
- Release tags use `v<version>`; current version is `0.0.1` / `versionCode = 1`.
- Release APK naming: `ShareHelper-v<version>.apk`.
- Release signing data comes only from environment variables or GitHub Secrets.

## ANTI-PATTERNS (THIS PROJECT)
- Do not add a launcher UI just to configure the app; it is intentionally a share target.
- Do not save processed media to Gallery, MediaStore, or public external storage.
- Do not commit keystores, passwords, generated release artifacts, or decoded signing files.
- Do not hard-code signing credentials in Gradle, shell scripts, README, or workflow logs.
- Do not change `SHARED_CACHE_DIRECTORY` without changing `res/xml/file_paths.xml`.
- Do not change `applicationId` without checking Manifest authority and `FileProvider.getUriForFile`.
- Do not put user-visible Chinese text directly in Kotlin; use `strings.xml`.
- Do not remove `FLAG_GRANT_READ_URI_PERMISSION` or outgoing `ClipData` from sharing.
- Do not broaden Manifest MIME filters without matching Kotlin validation and processing support.
- Do not rely on `app/build/` contents as source; generated outputs are disposable.

## UNIQUE STYLES
- Minimal app surface: no layout XML, no Compose, no launcher flow.
- Background processing uses a plain `Thread` and returns to UI with `runOnUiThread`.
- Failure UX is toast + `finish()`.
- Images prefer metadata mutation, then JPEG re-encode with a tiny pixel difference fallback.
- MP4 tries `MediaExtractor` + `MediaMuxer` and writes an `application/sharehelper-export` metadata track.
- Video fallback is deliberately conservative copy for non-MP4 or muxer-rejected inputs.
- Old cache batches are cleaned at launch after 2 days.

## COMMANDS
```bash
# Debug APK
./gradlew :app:assembleDebug

# Main local verification used for this project
./gradlew clean :app:assembleDebug :app:check

# Signed release APK; requires SHAREHELPER_RELEASE_* env vars
./gradlew :app:assembleRelease

# Local publish helper; requires clean git tree, gh login, and signing env vars
scripts/publish-release.sh 0.0.1
```

## NOTES
- No `AGENTS.md` / `CLAUDE.md` existed before this file.
- Kotlin LSP was unavailable in this environment; verify with Gradle tasks instead.
- No `app/src/test` or `app/src/androidTest` source directories currently exist.
- Lint reports, when generated, use Android default paths under `app/build/reports/lint-results-<variant>.*`.
- `app/build.gradle.kts` references `proguard-rules.pro`, but no such source file was found; minify is currently disabled.
- GitHub Actions uses Java 17 and Android SDK setup; Node is only the runtime required by GitHub actions.
- Subdirectory `AGENTS.md` files are not warranted yet: `app/`, `.github/`, and `scripts/` are small enough for this root file to cover.
