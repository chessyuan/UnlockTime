# UnlockTime

[简体中文](README.md) · [Download v0.6.4 / build17](https://github.com/chessyuan/UnlockTime/releases/tag/v0.6.4) · [Validation](docs/UNIFIED_TEST_REPORT.md)

**Full physical-device adaptation is currently limited to Samsung Galaxy S24+ (SM-S9260, Android 16, pattern lock). Other devices are not adapted, including S24, S24 Ultra and other regional S24+ variants. PIN and password tests on an emulator do not establish compatibility with other real phones.**

One app for AI phone operation, scheduled tasks, local automatic unlocking and fixed-position repeat taps. Set the daily task itself: an unlocked phone proceeds directly; a locked phone uses its locally configured credential once and proceeds only after Android confirms it is unlocked. A separate unlock alarm is unnecessary.

The current download is the **0.6.4 / build17 Debug APK** tested on the physical phone. Device compatibility and third-party business outcomes are not guaranteed.

## What differs from OpenPhoneUse?

This comparison uses the pinned OpenPhoneUse 0.0.7 integration baseline, not future upstream versions.

| Scenario | OpenPhoneUse baseline | UnlockTime 0.6.4 |
| --- | --- | --- |
| A device routine fires while locked | Notify the user to unlock | Wake, authenticate locally once, verify Keyguard, then continue |
| Repeat one fixed tap | General tap / batch actions | Independent `repeat_tap`: one model call, a local sequence, actual completed gesture counts |
| Ordinary in-app page changes during repeat taps | General task-step handling | Keep the fixed point by default; an explicit stop-on-change option is available |
| Open several apps in order | Single open-app action | Up to five deterministic steps, each with a delay before launch; no model needed |
| Long saved task instructions | Inherited task editor limit | Removed the original 1,000-character cap |
| AI observation and authentication | AI automation engine | One accessibility service and shared device ownership; local credentials and authentication screens stay outside model access |

The Agent, model clients, interface and routine infrastructure come from [OpenPhoneUse](https://github.com/joungminsung/OpenPhoneUse). Local waking and authentication come from UnlockTime, formerly LocalWake. Selected screen-capture protection changes from OpenPhoneUse v0.0.8 are included; this is not a wholesale update to upstream main. Original copyrights remain, without a claim of official endorsement.

## Repeat tapping

Ask the AI to tap a currently located button 60 times at 1,000 ms intervals. It supports a pixel point or one fixed point inside a rectangle, never random scatter. Defaults are 10 taps and 1,000 ms; the maximum is 60 taps per call.

The phone performs the sequence locally without model decisions between taps. `stop_on_page_change=false` is the default for ordinary in-app changes; `true` stops when the page changes. Results report requested taps, completed gesture callbacks and the stop reason. Cancellation, relocking, accessibility loss, protected capture and disallowed surfaces still stop execution. Existing risk confirmations remain.

Version 0.6.4 fixes a compound execution request being rejected as discussion when it says “do not stop when the page changes” or “不要设置 stop_on_page_change=true”. No library-specific booking parser or forced task-result rewriting is included.

Routing still includes keyword rules: mixing discussion words such as “example” or “code” into an execution request can be misclassified. Check the actual tool receipt rather than assuming execution.

## Setup

1. Download from [Releases](https://github.com/chessyuan/UnlockTime/releases). Update an existing same-signature installation in place; do not uninstall or erase its data.
2. Enable the single UnlockTime accessibility service and exact alarms, allow required notifications, and configure a matching local lock credential. Automatic unlocking defaults on and can be disabled.
3. Sign in to ChatGPT in AI settings or configure a supported provider. Model services may require an account or paid plan.
4. Save daily, weekly or one-time tasks, including ordered app launches and waits in the same editor.

The owner must manually unlock once after boot. Failed or ambiguous authentication pauses further credential input. Routine operation requires no PC, ADB, root, Shizuku or separate integration server. English is the default; Simplified Chinese is selectable.

## Overlapping tasks

Phone-operation tasks run one at a time. A due task waits while another is running; it expires if it has not started within ten minutes of its scheduled time. Multiple overdue tasks currently follow the internal pending-list order, not a guaranteed scheduled-time priority. Separate tasks do not automatically depend on the previous task's business success.

## Privacy and controls

Device credentials remain encrypted locally under Android Keystore and do not enter model prompts, tool arguments, chats, screenshots or diagnostics. Ordinary screen content, screenshots and task context required for AI operation go to the user's chosen model service. Android protected-capture restrictions remain enforced.

When “Confirm before sending messages” is off, eligible direct local requests and user-saved routines with non-third-party triggers honor that choice for GUI sends. Money, deletion, security settings, SMS and other connector operations retain their applicable confirmation rules. See [security and privacy](docs/UNIFIED_SECURITY.md).

## Actual evidence

- Delivered build17: Debug build successful; 1,695 JVM passes, 3 skips, no failures; Lint 0 errors and 106 warnings.
- One complete saved task on the real WeChat page: one `repeat_tap`, 60/60 completed, followed by status verification and recent-task cleanup. The existing booking state is not proof of a new successful reservation.
- Three follow-up runs on S24+: securely locked phone → native automatic authentication → AI opens Calculator and reads its screen, 3/3 passed.
- Same application ID/signing identity, original task definitions and accessibility enablement preserved.

These are short physical-device trials, not overnight standby or multiple-backlogged-task tests. Other physical models remain unverified. [Detailed evidence](docs/UNIFIED_TEST_REPORT.md) · [De-identified summary](docs/evidence/build17-validation.json)

## Build

JDK 21 and Android SDK Platform 37; target SDK 36, minimum SDK 31. Window-capture protection used for repeat taps requires Android 14 or later; the physical-device limitation above still applies.

```text
gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

The application ID remains `org.localwake.app`. Private signing keys are not included, so a rebuild may not update an existing installation. Account data, private phone screenshots, raw logs and credential material are excluded. Public regression fixtures replace personal places and schedule times with synthetic examples; production code matches the delivered build17 source, with text line endings normalized by Git.

MIT: © 2026 joungminsung (OpenPhoneUse), LocalWake contributors and OpenClaw Foundation (adapted portions). [License](LICENSE) · [Notices](THIRD_PARTY_NOTICES.md) · [Architecture](docs/ARCHITECTURE.md)
