# Unified security and privacy

## Trust boundary

The model chooses ordinary automation actions on an unlocked phone. Native Kotlin alone may retrieve the locally encrypted device credential. `LocalUnlockProvider` is an internal dependency, never a ToolModule or MCP command. It returns fixed result codes, not credentials or authentication UI. Global automatic unlock defaults on, as specified by the product owner, and can only be changed in native settings. No separate task grant or module API key exists.

The original `unlock_credential_v1` preferences, `localwake_unlock_credential_v1` / `localwake_pattern_v1` Keystore aliases, encryption format and durable failure latch remain unchanged. App ID and signing identity must match the installed version before upgrading. No uninstall or clearing private data is part of migration.

## Device ownership

`DeviceExecutionCoordinator` grants one identity-based lease at a time. `UNLOCK`, `AGENT`, `ROUTINE`, `CREDENTIAL_EDIT` and `MIGRATION` share it. Epoch changes revoke older operations and callbacks. Native lock input additionally uses `LocalInputSession`; synchronous input and asynchronous Android gestures hold `DeviceInputFence` tickets. Cancelling a coroutine does not pretend Android has finished an accepted gesture.

Credential editing reserves the device before launching Android authentication, waits for actual input completion, retains the reservation through rotation/return animations, and keeps `FLAG_SECURE`. Ordinary model sessions reject this app's visible application windows, including another split-screen pane; the model cannot edit its own credentials, API keys or global policy. Android's protected screenshot restrictions are honored.

Agent device access is lazy: screen-free chat and Notify require no unlock. The first screenshot/tree/gesture/application action checks the full Keyguard, first-unlock-after-boot, permissions, migration authority and global setting. Local authentication executes once. Each subsequent operation checks its existing lease, job state, current task revision, service identity and connection generation. Relock/disconnect/stop invalidates reads, watchers, cached snapshots and model send guards; it never silently reauthenticates the same interrupted task.

## Authentication

Trusted system package/window checks, live node bounds, stable/new node consistency, Samsung preparatory gestures, the continuous pattern path and full Keyguard verification are retained. PIN/password nodes must be identifiable; unknown layouts fail closed. A durable attempt latch is set before authentication input. Failures preserve the pause/unknown outcome. No brute-force retry, hidden API, root, Shizuku or runtime ADB requirement is added.

## Data sent to models

Unlike the former offline-only app, the unified application has INTERNET permission. Tasks and required normal screen content/screenshots may be sent to the model provider the user selects, along with existing conversation/context. These services have their own terms and account requirements. Local device credentials are not model inputs. Lock/authentication screenshots are not permitted attachments; password-node text is redacted. History screenshots are omitted from a new screen-free conversation rather than causing automatic authentication. Ordinary user-chosen photos remain supported.

RiskGuard, RiskJudge, ConfirmPolicyGate and UserGate remain in the execution path, with an explicit **local GUI SEND policy adjustment** after the user requested that the disabled send-confirmation setting be honored. It is inaccurate to describe the entire confirmation policy as unchanged.

The validation-13 source preauthorizes only GUI SEND when the local send-confirmation setting is OFF and the run originated from a direct local APP/ASSIST request (not a suggestion), or a user-saved routine whose trigger is not third-party content. Helper calls, result-card/suggested requests and notification/calendar-content-triggered routines do not inherit that consent. The setting was already OFF during the first timed test; the change fixes its treatment in the GUI path, not a silent setting change. Validation-13 build/JVM/Lint checks and the API 31 local agent fixture passed. Its second Samsung timed trial completed both existing tasks without manual confirmation/resuming: the user confirmed automatic unlock and the complete flow, with completed occurrences/history verified afterward. The target app's business rejections remain failures of the requested business outcomes, not failures to complete the automation flow.

This local consent is independent of screen/history taint: visiting a third-party screen still records taint but does not itself restore a GUI SEND card for an eligible local task. Taint continues to constrain other authorization, memory and module paths. The exception does not cover SMS, remote/MCP or other module tools, helpers, money, security or deletion; their prior confirmation rules and any existing narrowly scoped exceptions remain. Global automatic unlock grants none of this SEND consent. Models cannot change the native setting or manufacture an eligible source by claiming `user_authorized`.

## Diagnostics and recovery

Compatibility export is opt-in and whitelist based. No raw pattern, PIN, password, ciphertext, IV, key, touch path, full UI text or lock screenshot belongs in reports. Migration backups contain tasks/history/guard only. No default telemetry/upload was added. Model/network errors must not log credential data; test reports use synthetic fixtures.

Corrupt task authority or ambiguous results stop both schedulers. Recovery verifies stored provenance; it does not replace the new store with an old backup. Claimed but unfinished operations remain unknown and cannot be replayed automatically. Keep the build11 APK for rollback analysis; installing an older APK after migration is not a supported automatic data rollback.

The validation-13 `RoutineTimeEditPolicy` correction handles an explicit time edit after today's earlier occurrence was claimed. A genuinely future eligible time can arm a **new definition revision** when recorded occurrences/history support it; an imported date alone, clock rollback or an ambiguous/nonexistent DST time is not enough. Old occurrence tokens, failure guards and cooldown conditions remain in force. This is not automatic replay of an interrupted action. For the second controlled test, the existing tasks' cooldown was explicitly set temporarily to zero. After completion, normal UI restored the original daily schedule and 60-minute cooldown; both tasks remained enabled, prompt hashes were unchanged and new pending occurrences for today were checked. The failure guard remained clear. This explicit test-setting change did not weaken the policy in code.

Public evidence is restricted to whitelisted summaries under `docs/evidence/`. The private `artifacts/` directory contains account/chat screenshots and diagnostic details and must be excluded in full from source ZIPs. Do not copy it wholesale to resolve report links. No credential shape, gesture coordinates, raw trace, personal institution/seat identifier, account or private message belongs in the public summaries.

## Evidence limits

These are implementation guarantees subject to the tests recorded in `UNIFIED_TEST_REPORT.md`, not a claim of 100% Android/OEM compatibility. Unit fixtures cannot prove a real Samsung upgrade or a real provider request. The debug APK retains the inherited debug command receiver only in debug builds; its device commands use the same shared gate and do not expose an unlock tool. Release builds must retain manifest and permission checks. Google Play accessibility/distribution policy review is a separate release requirement; the service declares general automation, not a disability-assistance purpose.
