# Build17 validation / 验证记录

Release: UnlockTime 0.6.4 / build17. Physical tests were performed on 2026-11-26; public repository preparation is dated 2026-11-27. The APK is the same tested binary, not a newly signed replacement.

APK SHA-256: `d387f9a9093558537594183c681b2297180659314fc2d4b2f3aea3dd58bc6b44`

Certificate SHA-256: `dc14ee3a5c1795a62a15f22460db0fed05c3e66729496b57c6160c46b503b4e9`

## Build and regression evidence

The local development checkout ran `gradlew.bat --offline --console=plain :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`: build success, 1698 tests total, 1695 passed, 3 skipped, 0 failures/errors; Lint 0 errors and 106 warnings.

Two full-prompt tests reproduced the original NOT RUN rejection before the fix. The new four-test regression suite then passed with the full task, its header-free form, negative-option instructions and explicit no-click/question controls. Public fixture location names and schedule times are synthetic replacements; they do not reproduce private account data.

During public-source preparation, the first anonymization used the Chinese word “示例” (example) in synthetic names. This accidentally matched the existing discussion classifier and produced two test failures. The fixture names were changed to neutral fictional names without changing production code. This exposes a remaining keyword-classification limitation, documented in the README; it is not presented as a new runtime fix.

Fresh checks on the final anonymized publication checkout completed on 2026-11-27: `gradlew.bat --offline --console=plain :app:testDebugUnitTest :app:lintDebug` returned `BUILD SUCCESSFUL`; 1698 tests, 1695 passed, 3 skipped, 0 failures/errors; Lint 0 errors, 0 fatal findings and 97 warnings. These checks compile production and test sources; the release still uses the previously device-tested APK.

## Real phone: full repeat-tap task

Samsung SM-S9260 / Android 16. The existing saved routine was started using its normal Run now button. Its complete instructions were unchanged for this phone test. The Agent opened WeChat, navigated the actual page and located the target from the current screen.

One `repeat_tap` ran with point mode, count 60, interval 1000 ms, check_every 10 and stop_on_page_change false. Receipt: `repeat_tap requested=60 completed=60 stop_reason=COMPLETED`. Tool duration was 61,435 ms. No tap-by-tap model requests or helper delegation occurred during the sequence.

After the sequence, the Agent checked the page and cleared recent tasks. The system recents screen was independently observed empty. The target was already in use at the start, so this is not reported as a new successful reservation. This run did not independently timestamp every touch or deliberately provoke every possible popup/ad transition.

## Real phone: automatic unlock follow-up

Three separate actual AI requests to open Calculator were submitted while the phone was initially unlocked. Before the first device operation, normal Android temporary strong-authentication lockdown and sleep were applied. Each round confirmed Keyguard locked, deviceLocked true, display asleep and strong-authentication required.

The application's existing device preflight woke and authenticated the phone. Every round ended with both lock indicators cleared, strong-authentication requirement cleared, Calculator independently confirmed foreground, and successful open_app, get_screen and finish tool results. Result: 3/3 passed, no manual intervention during a round and no failed-credential retries.

These calls used the app's existing ADB-restricted Debug submission entry for test initiation; runtime phone operation and authentication used the shipped production engine. No practice app was installed, no new daily routines were created and no credentials or lock-screen images were collected. This is not a three-run alarm-firing test or overnight standby test.

## Preservation and limits

The installed APK matched the delivered hash. Existing task definitions, times and accessibility enablement remained intact. The next scheduled occurrences and next actual AlarmManager wakeup were checked; future execution is not claimed already completed. Background supervision was running and the failure latch was clear afterward.

Other brands, other physical authentication methods, overnight standby and multiple overdue tasks remain outside these build17 physical trials. Busy tasks wait at most ten minutes from their due time; a backlog does not currently guarantee scheduled-time sorting. No new correctness claim is made for third-party booking or check-in services.

Only selected results are public in [build17-validation.json](evidence/build17-validation.json). Private screenshots, raw traces, account information, original personal task prompts, credentials and signing keys are excluded. Earlier [build12/validation-13 evidence](evidence/validation-summary.json) remains historical and is not relabeled as build17 testing.
