# UnlockTime — third-party notices

The integrated application contains MIT-licensed code from both projects. Their notices remain applicable; integration does not imply official endorsement or a new partnership with their authors.

## OpenPhoneUse

Copyright (c) 2026 joungminsung. MIT License: [full text](licenses/OpenPhoneUse-MIT.txt).

Source: https://github.com/joungminsung/OpenPhoneUse, fixed commit `c980cbf134a5d5eb1ec6cd0d097fcfabc45d6a73`. This is the main application, AgentLoop/Runner, tools, model clients, accessibility controller, overlays, UI, risk controls and routine infrastructure, with integration changes. The original README is preserved under `docs/provenance/`. No automatic update to upstream main is performed.

UnlockTime 0.6.0 also adapts the screen-capture safety changes and tests included in the OpenPhoneUse v0.0.8 release at `96f3e72f1c3d86e1f5acdfd93e88a27cf24a47f3`. The same MIT notice applies. [Upstream change](https://github.com/joungminsung/OpenPhoneUse/pull/31)

## UnlockTime / LocalWake

Copyright (c) 2026 LocalWake contributors. MIT License: [original build11 license](docs/provenance/UnlockTime-build11-LICENSE).

Imported from the owner's immutable LocalWake 0.4.0/build11 source: local authentication, trusted SystemUI detection, credential encryption, original scheduler data, compatibility diagnostics, credential UI and regression tests. Persistent identifiers and the registered service component are retained.

## OpenClaw-derived portions

Copyright (c) 2026 OpenClaw Foundation. MIT License: [full text](licenses/OpenClaw-MIT.txt).

The original local implementation adapted dynamic pattern bounds, Samsung preparation, continuous pattern strokes, wake flags, AES-GCM storage and pattern editing from the user-supplied OpenClaw Android archive. Original attribution comments and [build11 notices](docs/provenance/UnlockTime-build11-THIRD_PARTY_NOTICES.md) remain preserved. The supplied archive did not contain its referenced root THIRD_PARTY_NOTICES.md; no missing terms are assumed.

## Dependencies

The build-generated `res/raw/aboutlibraries.json`, shown in Settings → Open-source licenses, identifies the actual resolved application libraries, versions and available license texts. `docs/DEPENDENCY_LICENSES_UNIFIED.md` records the generated inventory. Embedded notices inherited from the local engine remain in `licenses/dependencies/` and APK assets.

- AndroidX, Compose, Kotlin, coroutines, serialization, OkHttp, Anthropic Java SDK and most Java dependencies: their respective Apache-2.0 or other notices, as listed in the inventory.
- Google Play services libraries used by inherited optional location routines are distributed under the **Android SDK License**, not the project's MIT license. Their terms are linked in the application inventory. This optional service does not authorize root, ADB or access to another app's private storage.
- Test-only JUnit, Hamcrest, JSON-java, SQLite JDBC and MockWebServer retain their upstream terms; JVM test libraries are not application runtime code.
- Gradle wrapper/build tools retain their original headers and licenses.

The previous compatibility research projects were design/testing references, not newly incorporated source. No MPL/GPL source was copied as MIT in this integration. Model-provider accounts, services, prices and service terms are separate from this source license. The application does not require a private integration server; local scheduled authentication and app sequences do not call a model.

Branding uses the owner's supplied dot motif and its existing monochrome transparent vector adaptation.
