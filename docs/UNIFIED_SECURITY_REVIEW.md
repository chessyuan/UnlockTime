# Security review scope

This is a source and publication review, not a certification. Production runtime files in the public checkout match the tested build17 source. Native credential handling, trusted lock-window selection, one-attempt protection, device session revocation and the scoped GUI SEND setting are described in [UNIFIED_SECURITY.md](UNIFIED_SECURITY.md).

Public publication excludes private artifacts, databases, local configuration and signing material. Personal location and schedule identifiers in two regression fixtures are replaced with synthetic examples. Historical public source and the candidate publication are scanned for credential material before repository visibility changes. Scanning cannot prove the absence of every vulnerability.

No full upstream release-signing workflow is imported: the tested local Debug APK is attached as an explicitly labeled asset, using the existing UnlockTime certificate. Original authors' signing identity is not used. Platform distribution/accessibility policy compliance is separate from these engineering tests.

Real-device evidence and limitations: [UNIFIED_TEST_REPORT.md](UNIFIED_TEST_REPORT.md). No claim of universal OEM support, guaranteed business success or exhaustive task-overlap testing is made.
