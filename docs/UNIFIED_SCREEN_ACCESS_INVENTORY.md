# Unified screen-access entry-point inventory

The symbols below identify screen-access paths in the integrated source reviewed on 2026-11-16. See the [test summary](evidence/validation-summary.json) for runtime coverage.

## Enumeration method and boundary

The inventory searched all of `app/src/main/java`, including `org/localwake`, for `rootInActiveWindow`, `.windows`, `takeScreenshot`, `dispatchGesture`, `performAction`, `performGlobalAction`, `startActivity`, and `openUri`. Follow-up searches covered `activeRoot`, `dispatchUnlockGesture`, activity-result launchers, `PendingIntent`, widget launches, `placeCall`, model-tool registration and model-client call sites. Definitions/delegates were distinguished from real Android calls. No code was executed on a phone.

Paths beginning `com/` or `org/` below are relative to `app/src/main/java/`. Classification:

- **Model screen path:** reachable from a registered model tool or a current screen-analysis request. Requires `AndroidDeviceAccess` and the ordinary controller/platform input gate.
- **Trusted local UNLOCK path:** the local lock-screen algorithm/wake handshake. May inspect authentication UI only under the actual local `UNLOCK` lease and local validation; it is never an AI tool or a source of credential content for the model.
- **Trusted transition/metadata:** a tightly bounded check or app navigation step that supports ownership, without publishing a screen to the model.
- **User UI / system dispatch:** a real user click, permission/OAuth launcher or notification/widget PendingIntent. Generating the card/notification does not execute its click handler. These are not classified as model screen bypasses merely because their implementation uses `startActivity`.
- **Debug only:** DUMP-permission receiver registered only when `BuildConfig.DEBUG`; screen commands still use the device gate.

## Raw screen reads and screenshots

| File / symbol / snapshot line | Actual read | Caller and control |
| --- | --- | --- |
| `com/phoneuse/service/AccessibilityTreeReader.kt:36`, `resolve:191` | `service.windows`, window roots and node traversal | Model screen path. `snapshot`/`resolve` enter the access gate; snapshot return and controller result publication validate the originating lease. Traversal has a stoppable read budget, not a per-node atomic lease check. Resolved nodes are revalidated by the controller immediately before actions. |
| `com/phoneuse/service/AccessibilityDeviceController.kt:134`, `takeScreenshotUnbounded:280` | Snapshot/cache access; the only ordinary `takeScreenshot` Android call at line 283 | Lazy screen preflight, lease-scoped reads/cache, normal-surface checks, callback checks and buffer disposal. `markedScreenshot` uses the same path. |
| `com/phoneuse/unified/AndroidDeviceAccess.kt:116` | `rootInActiveWindow`, package name only | Trusted preflight metadata for leaving this app before ordinary AI observation. It runs after task acquisition and prior-input drain; it does not compress/export this root. |
| `com/phoneuse/unified/AndroidDeviceAccess.kt:139` | All accessibility windows and application-root package names | Gate enforcement, not a model snapshot. Any visible own-app application root or unknown application root refuses normal access; overlays have a separate window type. |
| `com/phoneuse/files/AttachmentStore.kt:77` | Active-root package name only | Preflight metadata refusal for “recent screen” from inside this app. Real snapshot/capture at lines 87/92 goes through the controller and a task lease. No preflight root text becomes an attachment. |
| `com/phoneuse/service/DebugProbes.kt:15` | Window ID/type/display metadata | Debug gate at line 10; normal-surface validation before logging each item. No root labels, UI text, image or gesture path is logged here. Experimental secondary-display probes are disabled. |
| `org/localwake/app/accessibility/LocalWakeAccessibilityService.kt:52` (`activeRoot`), line 97 (`foregroundPackage`) | Raw active root; package-only foreground observation | Trusted local host methods, not self-authorizing public model APIs. Their execution callers carry `LocalInputSession` under UNLOCK; root parsing is confined to the native algorithm. |
| `org/localwake/app/device/SystemUiTree.kt:50`, line 77 | `service.activeRoot()` through the local host | Native System UI validation and fresh-root consistency. This authentication-surface exception must remain exclusive to the UNLOCK path; ordinary model tools must never call it to evade the unlocked-screen gate. |

## Raw input and activity launches reachable during execution

| File / symbol / snapshot line | Actual operation | Route and current boundary |
| --- | --- | --- |
| `com/phoneuse/service/AccessibilityDeviceController.kt:339`, 470, 485, 491, 508, 515 | Node click/long-click, set text, IME enter, directional/generic scroll | Registered screen tools → controller. Each actual `performAction` is inside `input` (line 73), which checks current task/normal surface and reserves a shared input ticket after node resolution. Password/own-app text nodes are refused. |
| Same file, `dispatchUnbounded:422` / raw call 434 | Tap, press, swipe and other gesture dispatch | Current lease + input ticket before Android dispatch. Only callback/rejection/exception ends the ticket; coroutine cancellation alone does not. Completion checks the captured lease before publishing success. |
| Same file, raw global action at 559 | Back/Home/Recents and supported global actions | Ordinary device gate plus `input`. |
| Same file, `launchApp:567`, `openUri:583`, raw `startActivity:609` | Application launch and URI/settings intent launch | Lazy preflight; own package refused; `OpenUriPolicy` strips/denies unsafe own-component/file grants and validates parsed intents. Actual Android launch is fenced by `input`. |
| `com/phoneuse/agent/ToolExecutor.kt:455`; `PhoneModule.kt:197`; `app/OverlaySession.kt:198` | `openUri` delegates, not additional platform calls | Registered `open_uri` and `open_route` eventually use the controller above; `OverlaySession.ready()` delegates to that controller. |
| `com/phoneuse/phone/AndroidPersonalData.kt:179` (`AndroidAlarmSource.open`) | Alarm/timer `startActivity`, even with `EXTRA_SKIP_UI` | Registered `PhoneModule.set_alarm/set_timer → AlarmTool`. Both are in `ToolExecutor.DEVICE_TOOLS`; `AndroidPhone.kt:41` supplies lazy preflight; the actual launch uses `AndroidDeviceAccess.platformInput`. This was a reachable bypass before the integration fix. |
| `com/phoneuse/unified/AndroidDeviceAccess.kt:119` | Preflight HOME | Trusted transition out of this app. Occurs within the acquired task on Main after prior-input drain; subsequent ordinary observations must pass the normal-surface gate. It is not a model-selected exception for editing this app. |
| `com/phoneuse/app/AgentRunner.kt:654` → `ui/MainActivity.kt:255` | Return to chat | Trusted finish transition inside the originating task, before release. Checks cancellation, drains input, validates and publishes under the exact lease. Non-cancellable final cleanup does not launch it. |
| `org/localwake/app/accessibility/LocalWakeAccessibilityService.kt:68` | Local unlock gesture | Requires ready service and the actual `UNLOCK` lease from `LocalInputSession`; reserves the shared fence. `LocalPatternUnlocker.kt:114` is its caller, not a separate raw dispatch path. |
| `org/localwake/app/unlock/LocalUnlockEngine.kt:240`, 252, 257 | Native PIN/password/set-text/IME/click input | Local-only credential path. Fresh trusted System UI, policy and task checks immediately precede input; actual node actions use `LocalInputSession.input`. Credential retrieval is local and returns fixed outcome codes upstream. |
| `org/localwake/app/execution/LocalExecutionCoordinator.kt:39`, line 59 | WakeActivity and ordered application launch | Native deterministic execution, no model call. Rechecks `stillEnabled`/Job and unlocked state where appropriate, then `LocalInputSession.input` under the UNLOCK lease. |
| `com/phoneuse/unified/AndroidLocalUnlockProvider.kt:189` | WakeActivity before local authentication | Internal provider dependency, not Android ContentProvider/ToolModule/MCP command. Rechecks `stillCurrent`/Job and uses local lease/fence. The later credential algorithm is the native path above. |

`LocalInputSession` is a context/capability carrier, not a replacement for local surface validation. Its current production callers acquire UNLOCK in `AndroidLocalUnlockProvider`, `UnifiedStartup.executeNative` or the retained service's local submission path. Future callers must not turn a generic owner enum or a raw `activeRoot()` method into permission to inspect credentials.

## Assist, attachments, delayed callbacks, and provider sends

| Entry | Reachability and handling |
| --- | --- |
| `com/phoneuse/assist/PhoneUseSession.kt:379` (`loadSuggestions`) | An explicitly configured screen-suggestion operation creates its own device task before reading the controller tree. It sends compressed normal-screen text only. Plain Assist input uses `AgentRunner.send(Source.ASSIST)` without forcing an initial observation. |
| `com/phoneuse/assist/AssistSuggestions.kt:73` | Checks outbound state around the suggestion model request. The enclosing screen task remains valid through result handling. |
| `com/phoneuse/files/AttachmentStore.kt:71`, 149, 311 | New screen captures use the gate and are tagged as screen captures. Loading a marked screen image requires access; historical marked screen images are omitted by the history loader. User-chosen photos/camera/document images are separate import paths, not accessibility captures. |
| `com/phoneuse/agent/AgentLoop.kt`, `Helper.kt`, `app/AgentRunner.kt` | Device engagement is lazy; helper tools share ToolExecutor, risk and taint checks. A stale screen-task callback cannot turn a revoked lease into a new unlock. |
| `com/phoneuse/model/ChatCompletionsClient.kt`, `OpenAiResponsesClient.kt`, `ClaudeClient.kt` | Request/stream boundaries call `AndroidDeviceAccess.checkOutbound`. Claude's independent listener captures `outboundGuard` at line 269. This guards delayed result publication and later sends; already transmitted valid requests cannot be recalled. |
| `com/phoneuse/unified/LocalUnlockProvider.kt` | Internal native interface returning status codes. It is not registered among model tools and is not an exported content/service endpoint for credential extraction. |
| `app/src/main/AndroidManifest.xml:285` | AndroidX attachment FileProvider for camera/photo files, `exported=false`; not the local unlock provider. The inventory found no separate exported provider that reads the accessibility tree or returns device credentials. |

## User-controlled UI, OAuth, permissions, cards, and widgets

These direct launches are not reachable merely by returning a model tool call. Their user/system trigger is part of the classification; they must not be reused as a new ToolModule launch shortcut.

| Actual call or launcher | Trigger / distinction |
| --- | --- |
| `org/localwake/app/MainActivity.kt:229` | Native credential confirmation launcher. `CredentialUiSession` first reserves CREDENTIAL_EDIT; the activity waits for the input fence and checks the same session before launching. `ui/CredentialEditorDialog.kt:37` uses `SecureFlagPolicy.SecureOn`. |
| `com/phoneuse/ui/connectors/ConnectorsScreen.kt:98`, 128 | User-selected connector/OAuth authorization flow and browser URL; not a registered model tool. |
| `com/phoneuse/ui/cards/ResultCard.kt:223` (`tryStart`) | User-clicked card action: dialer, route, URL, share sheet or permission settings. A model can supply a displayed card but cannot directly execute `runCardAction`; URL/permission/photo URI checks remain in the click handler. |
| `com/phoneuse/ui/settings/Permissions.kt:113`, `NudgeSettingsScreen.kt:138` | User requests Android permission/settings screens. |
| `com/phoneuse/ui/settings/SettingsScreen.kt:723`, 735; `ui/usage/UsageScreen.kt:172`; `ui/chat/ModelSheet.kt:140` | User opens plan/account/help-related pages. These are not automatic device-tool launches. |
| `com/phoneuse/ui/MainActivity.kt:678` | User navigation into the native LocalWake/device UI; its ordinary own-app windows are blocked from AI screen input. |
| `org/localwake/app/ui/BackgroundGuardCard.kt:34`; `ui/SettingsPage.kt:75` | User opens notification/background or application settings. |
| `com/phoneuse/overlay/OverlayManager.kt:194` | User presses dock microphone; opens AssistVoiceActivity. This is a UI callback, not a model gesture/launch method. |
| `com/phoneuse/widget/QuickAskActivity.kt:138`, 143 | User submits a typed prompt or chooses a chip; opens the app prompt route. Actual work enters AgentRunner. |
| `com/phoneuse/widget/*Widget.kt`, `WidgetActions.kt:70` | Widget `actionStartActivity` opens app UI; routine action calls `RoutineSystem.runNow`. They do not contain another raw accessibility executor. |
| `com/phoneuse/notify/Notifier.kt:100`, 168, 331; `NotifyActionReceiver.kt:83`, 95 | Notification PendingIntents open UI or token-validated receiver actions. Task replies/continuations enter AgentRunner, rather than directly calling Android screen APIs. |
| `com/phoneuse/proactive/ProactiveSystem.kt:372` | Nudge PendingIntent opens UI after user interaction. The weather model request at line 339 uses an empty tool list. |
| `com/phoneuse/widget/BriefWork.kt:128` | Background brief-generation model call has an empty tool list; it does not read the live accessibility screen or launch activities. |

Native global policy changes remain in `ui/settings/SettingsScreen.kt` via `GlobalUnlockSettings.setEnabledFromLocalUi`. Ordinary AI access rejects this app's application windows and own-package/own-component launch targets; producing a result card is not authorization to edit global policy.

## Debug and other phone APIs

`PhoneUseAccessibilityService.kt:189` registers the inherited debug receiver only in debug builds. Registration requires the sender's `android.permission.DUMP` (line 240). Non-Stop commands are refused on a locked device or during UNLOCK/CREDENTIAL_EDIT/MIGRATION; direct debug screen commands run within `AndroidDeviceAccess.task(allowAutoUnlock=false)`. Debug-run requests use AgentRunner. `DebugProbes` is metadata-only. These are privileged diagnostic surfaces, not model tools; release build/manifest behavior still needs its own build validation.

`AndroidCallSource.call` in `com/phoneuse/phone/AndroidPersonalData.kt:198` contains `TelecomManager.placeCall`. In the reviewed manifest CALL_PHONE is not declared; `AndroidPhone.kt:43` therefore does not construct CallTool and `PhoneModule` does not offer `call`. This is a dormant launch-like path, not an active bypass in the reviewed configuration. Adding that permission or exposing the tool requires a new screen/ownership review. A user-clicked dial card instead launches ACTION_DIAL as described above.

Calendar/contact/notification/photo APIs, media/volume/torch controls and wallpaper writes are data or device-service operations, not additional `root/windows/screenshot/gesture/startActivity` paths in this enumeration. They retain their own permission/confirmation/taint requirements; exclusion from the screen gate is not a statement that all such operations are low risk.

## Change rule and evidence limit

Any future registered model tool that can launch an activity, send an activity PendingIntent, inspect a live window or inject input must join the shared device gate even when the Android API advertises “skip UI.” Keep native credential algorithms exclusive to UNLOCK ownership; keep card/OAuth/permission click handlers distinct from model execution.

Follow-up boundary: `PhoneUseApp.onActivityPreStarted` now calls `AndroidDeviceAccess.ownActivityStarting` before every own Activity starts. The coordinator atomically revokes only AGENT/ROUTINE, preventing an in-flight capture from surviving a brief own-app visit that has already ended by callback time. No-lease chat and native UNLOCK/MIGRATION/CREDENTIAL_EDIT retain their owners. `NormalSurfacePolicy` permits only read-only waiting for missing/blank roots; it never treats an unknown root as authorization. Existing callback-time and actual-input gates remain required in addition to the lifecycle notification.

The same lifecycle callback invokes `OwnActivityInputGuard`: a busy platform-input fence temporarily adds `FLAG_NOT_TOUCHABLE` to that Activity until actual completion. A timeout does not enable input. Stop/destroy cancels its weakly referenced wait and removes only the flag it added. This protects ordinary native settings from already accepted input without adding a model tool or changing local credentials/policy.

The search and call-chain reading found no additional currently reachable model-to-screen bypass after the alarm/timer correction. This inventory does not prove reflection/native-library behavior, Android/OEM callback timing, network data absence, exported-component behavior of a built APK, or safe real-device upgrade. No Gradle, ADB, installation or device interaction was performed for this document.
