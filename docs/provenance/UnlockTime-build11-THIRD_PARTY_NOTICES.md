# Third-party notices

UnlockTime (formerly unlockphone / LocalWake) original code is MIT licensed. Third-party components retain their own licenses. This source distribution and the APK assets include license texts.

## OpenPhoneUse design reference

Copyright (c) 2026 joungminsung — MIT. Full text: [licenses/OpenPhoneUse-MIT.txt](licenses/OpenPhoneUse-MIT.txt).

The home and settings layouts, warm neutral palette, card borders, spacing and typography are adapted from [OpenPhoneUse](https://github.com/joungminsung/OpenPhoneUse), specifically `app/src/main/java/com/phoneuse/ui/theme/Theme.kt`, `ui/chat/HomeContent.kt` and `ui/settings/SettingsScreen.kt`, inspected 2026-11-15. The UnlockTime UI uses its own offline scheduling and credential flows. No remote agent, model API or account integration was copied. The original nine-dot branding was supplied by the user; this revision uses its transparent core mark.

## Adapted OpenClaw code

Copyright (c) 2026 OpenClaw Foundation — MIT. Full original text: [licenses/OpenClaw-MIT.txt](licenses/OpenClaw-MIT.txt).

Source: the user-provided `openclaw-android-source-unlock.zip`, preserved separately as an immutable reference. Adaptations are explicitly marked in source headers:

- LocalPinUnlocker → LocalPatternUnlocker, PatternGridProvider, SamsungOneUiAdapter: dynamic IDs/BFS lookup, Samsung bounded preparatory swipes, 3×3 path construction and callback timeout.
- LocalPinCredentialStore → LocalPatternCredentialStore / PatternCipher: Android Keystore and AES-GCM storage representation.
- LocalPinSetupActivity → PatternGridView / PatternValidator: touch selection and midpoint insertion.
- MobileUiWakeActivity → WakeActivity: public screen wake/keyguard flags.

The root reference LICENSE mentions THIRD_PARTY_NOTICES.md, but that file was **absent from the supplied archive**. This project does not assume its contents. Supplied Android THIRD_PARTY_LICENSES were examined, and unrelated assets/libraries/features were not migrated. No OpenClaw Gateway, UI framework, credentials or database were copied.

## Compatibility research (0.4.0)

The new compatibility interfaces, trust/stability checks and diagnostic whitelist are independently implemented. AutoJs6 (MPL-2.0), AutoTask (conflicting root/README/header licensing), adb-unlock (MIT), APK UI Parse (Apache-2.0), uiautomator2 (MIT plus separately licensed vendored code), Latch (MIT), scrcpy and Appium were inspected as design/testing references. No source file or binary from those projects was incorporated in this revision. Pinning, license limitations and exact evidence are in [docs/OPEN_SOURCE_COMPARISON.md](docs/OPEN_SOURCE_COMPARISON.md) and its linked research notes. Existing OpenClaw-derived code retains the MIT attribution above.

## Runtime and build dependencies

Resolved runtime and test coordinates with actual upstream POM licenses: [docs/DEPENDENCY_LICENSES.md](docs/DEPENDENCY_LICENSES.md). Embedded notices from cached original artifacts: [licenses/dependencies](licenses/dependencies/).

- AndroidX / Compose / Material 3 / Lifecycle / Activity / DataStore: principally Apache-2.0, copyright The Android Open Source Project and respective contributors.
- Kotlin / kotlinx.coroutines: Apache-2.0, JetBrains and contributors.
- Okio (transitive DataStore dependency), annotations and Guava ListenableFuture: upstream Apache-2.0 notices retained.
- DataStore's relocated external Protobuf: BSD-3-Clause, its embedded complete notice retained.
- Tests only: JUnit 4.13.2, Eclipse Public License 1.0; Hamcrest 1.3, BSD; JSON-java 20250517, public domain. These test implementations are not included in the APK.
- Gradle wrapper: Apache-2.0; wrapper scripts retain their original Gradle copyright headers. The wrapper JAR was reused from the reference; the new distribution URL and SHA-256 are pinned independently. Gradle and Android Gradle Plugin are build tools, not bundled application runtime code.

Full Apache-2.0 text: [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt). No proprietary or paid runtime service is used. To regenerate the dependency inventory and notices, run `gradlew dependencyInventory` then `python scripts/collect_dependency_notices.py`.
