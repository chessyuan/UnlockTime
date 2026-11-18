# UnlockTime

[English](README.en.md) · [下载 v0.5.0（Debug APK）](https://github.com/chessyuan/UnlockTime/releases/tag/v0.5.0)

**目前仅适配三星 Galaxy S24+。实测机型为 SM-S9260，Android 16，图案锁屏。其余机型均未适配，包括 S24 和 S24 Ultra。** 其他 S24+ 地区型号和系统版本尚无实机验证记录。PIN 和密码输入目前仅在安卓模拟器上验证。

UnlockTime 可以在手机锁屏时开始执行定时任务。例如，把“打开应用查看预约”设为每天早上执行，到点后手机会自动亮屏。已经解锁就直接操作，需要解锁时就由手机本地完成认证，确认解锁成功后再继续。只需设置日常任务的时间，无需单独安排解锁闹钟。

## 相比 OpenPhoneUse，多了什么？

| 使用场景 | 原项目 OpenPhoneUse 0.0.7 | UnlockTime 0.5.0 |
| --- | --- | --- |
| 定时任务到点，手机还锁着 | 发通知，提示用户解锁 | 开启本地自动解锁后，软件唤醒手机，尝试一次认证，成功后继续执行 |
| 定时依次打开几个应用 | 内置“打开应用”动作一次指定一个应用 | 在同一任务中编排最多五步，指定每一步打开哪个应用、打开前等多久。整个序列在本地执行，无需模型 |
| 定时让 AI 操作手机 | AI 可以读屏、点击、滑动、输入文字 | 沿用这些能力，系统确认解锁后才交给 AI 操作。中途重新锁屏会停止读屏。纯聊天和仅通知的任务可以在锁屏时执行 |

AI 对话、模型连接和任务框架来自 OpenPhoneUse。定时唤醒和本机认证来自原 UnlockTime（LocalWake）。两者共用一个应用和一个无障碍服务。原 UnlockTime 用户可覆盖升级，沿用已有凭据、任务和历史记录。[原项目处理锁屏任务的源码](https://github.com/joungminsung/OpenPhoneUse/blob/c980cbf134a5d5eb1ec6cd0d097fcfabc45d6a73/app/src/main/java/com/phoneuse/routine/RoutineEngine.kt#L62)

## 第一次使用

1. 从[版本发布页](https://github.com/chessyuan/UnlockTime/releases)下载 APK。已装同签名 UnlockTime 的用户可以直接覆盖安装。
2. 在“设备设置”中启用 UnlockTime 无障碍服务，允许精确闹钟，填写与系统锁屏一致的凭据。自动解锁默认开启。
3. 如果任务需要 AI，在 AI 设置中登录 ChatGPT 或配置支持的模型。在任务编辑页选择执行时间、重复日期和动作，可以单次执行，也可以每天或按星期重复。

手机重启后，第一次需要手动解锁。认证失败或结果不确定时，软件会暂停后续自动输入。日常运行无需连接电脑，也无需 ADB 或 Root。

## 隐私与操作确认

锁屏凭据在手机内加密保存，密钥由 Android Keystore 保护。模型无法读取凭据或认证页面。执行 AI 任务时，普通应用的屏幕内容、所需截图和对话上下文会发送给你选择的模型服务。

关闭“发送前确认”后，自己发起的任务和自己保存的定时任务可以直接提交普通界面操作。支付、删除、更改安全设置、直接发送短信和连接服务仍按各自规则确认。

## 实测与构建

S24+ 实测中，手机从锁屏状态开始，两项定时任务先后执行结束，中途无需手动确认或继续。该次目标服务拒绝了业务请求。最终构建的 JVM 测试有 1,618 项通过、3 项跳过、0 项失败。[测试摘要](docs/evidence/validation-summary.json) · [架构说明](docs/ARCHITECTURE.md)

Windows 构建使用 JDK 21 和 Android SDK Platform 37，在项目目录运行 `gradlew.bat testDebugUnitTest assembleDebug lintDebug`。包名为 `org.localwake.app`。最低安装版本为 Android 12，实机适配范围见本页开头。

采用 MIT 许可证，保留 joungminsung（OpenPhoneUse）、LocalWake contributors 和 OpenClaw Foundation 的版权声明。[许可证](LICENSE) · [第三方归属](THIRD_PARTY_NOTICES.md)
