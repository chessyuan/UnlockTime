# UnlockTime

[简体中文](README.md) · [Download v0.5.0 (Debug APK)](https://github.com/chessyuan/UnlockTime/releases/tag/v0.5.0)

**Device support is limited to the Samsung Galaxy S24+.** The physical test used the **SM-S9260 running Android 16 with a pattern lock**. Other phones, including the S24 and S24 Ultra, have no adaptation. Other S24+ variants and OS versions have no physical validation here. PIN and password input have emulator coverage only.

Schedule the task you want to run. When it needs the phone, UnlockTime wakes it, uses your saved credential locally if it is locked, and checks that Android has unlocked before continuing. An already unlocked phone proceeds directly. A morning task can open an app and check a reservation without a separate unlock alarm.

## What changed from OpenPhoneUse?

| Use case | OpenPhoneUse 0.0.7 | UnlockTime 0.5.0 |
| --- | --- | --- |
| A routine needs the screen while the phone is locked | Notifies the owner to unlock the phone | With local auto-unlock enabled, attempts authentication once and proceeds after Android confirms the unlock |
| Open several apps on a schedule | The built-in Open App action targets one app | A local sequence opens up to five apps in order, with a delay before each launch. No model call is needed |
| Automate an app with AI | The agent can read the screen, tap, swipe and type | The same agent takes control after authentication. Relocking stops its screen access. Pure chat and notification-only tasks can run while locked |

OpenPhoneUse supplies the agent, model clients and routine framework. UnlockTime brings the wake and authentication engine from LocalWake into the same app and accessibility service. Existing UnlockTime users can update in place with their local credentials, tasks and history. [Upstream lock handling at the integrated revision](https://github.com/joungminsung/OpenPhoneUse/blob/c980cbf134a5d5eb1ec6cd0d097fcfabc45d6a73/app/src/main/java/com/phoneuse/routine/RoutineEngine.kt#L62)

## Set up a task

1. Install the APK from [Releases](https://github.com/chessyuan/UnlockTime/releases). Apply it as an update if your existing installation uses the same signing certificate.
2. In Device settings, enable the UnlockTime accessibility service and allow exact alarms. Configure the same lock method and credential you already use on the phone. Local auto-unlock is enabled by default.
3. For AI tasks, sign in to ChatGPT or configure a supported provider. In the task editor, choose the time, repetition and action. A task can run once, daily or on selected weekdays.

After restarting the phone, unlock it manually once. A failed or uncertain credential attempt pauses further automatic input. Normal use requires no PC, ADB or root.

## Privacy and confirmation

Android Keystore protects the key used to encrypt your local credential. The model has no access to the credential or the authentication screen. For AI tasks, your selected provider receives the ordinary screen content, screenshots and chat context needed for the task.

You can turn off send confirmation for eligible in-app submissions in your own requests and saved time-based routines. Payments, deletion, security settings, direct SMS and connected services keep their separate checks.

## Verification and build

On the S24+, two timed tasks ran from a locked start and finished without manual confirmation or resuming. The target service declined the business requests during that trial. The final JVM test run recorded 1,618 passed, 3 skipped and 0 failed. [Test summary](docs/evidence/validation-summary.json) · [Architecture](docs/ARCHITECTURE.md)

On Windows, install JDK 21 and Android SDK Platform 37, then run `gradlew.bat testDebugUnitTest assembleDebug lintDebug`. The package ID is `org.localwake.app`. The minimum installation version is Android 12. The device support stated above applies to this release.

MIT. Includes work by joungminsung (OpenPhoneUse), LocalWake contributors and OpenClaw Foundation. [License](LICENSE) · [Third-party notices](THIRD_PARTY_NOTICES.md)
