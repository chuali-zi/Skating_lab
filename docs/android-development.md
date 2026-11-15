# Android 客户端开发与验证

首版客户端位于 `android/`，使用 Kotlin、Jetpack Compose 和 Material 3。所有页面均为原生 Compose，未使用 WebView、端侧模型或客户端计分。应用通过 `/api/v1` 调用 Java 服务。

## 构建基线

| 组件 | 固定版本 |
| --- | --- |
| JDK | 17 |
| Android Gradle Plugin | 8.13.2 |
| Gradle Wrapper | 8.13，配置官方 distribution SHA-256 |
| Kotlin / Compose compiler plugin | 2.2.21 |
| Compose BOM | 2025.10.01 |
| compileSdk / targetSdk | 36 / 36 |
| minSdk | 26 |

AGP 8.13 官方兼容说明要求 Gradle 8.13 和 JDK 17，支持至 API 36.1。依赖采用已固定且经过本轮构建验证的组合，后续升级单独验证，不使用动态版本。[AGP 官方说明](https://developer.android.com/build/releases/agp-8-13-0-release-notes)、[Compose BOM 说明](https://developer.android.com/develop/ui/compose/bom)

安装 Android SDK Platform 36、Build Tools 35.0.0 和 Platform Tools。通过 Android Studio 选择 SDK，或在不入库的 `android/local.properties` 指定 `sdk.dir`。Windows properties 中盘符冒号应转义，例如 `sdk.dir=D\:/Android/Sdk`。

在 `android/` 下执行：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon
```

调试 APK 生成在 `android/app/build/outputs/apk/debug/app-debug.apk`。SDK、下载工具、构建产物、`local.properties`、签名密钥不入库；标准 Gradle Wrapper 脚本、属性和 wrapper JAR 入库以保证可复现构建。

## 本机服务连接

1. 按服务端说明启动 PostgreSQL 和 Java 服务，并显式启用本机开发会话。后端正式配置不会自动开放开发登录。
2. 连接 Android 真机或启动模拟器，用 Platform Tools 执行 `adb reverse tcp:8080 tcp:8080`。
3. 安装 APK：`adb install -r app/build/outputs/apk/debug/app-debug.apk`。
4. 打开 App 的“连接服务”，填写 `http://127.0.0.1:8080`。调试构建可以留空访问凭据，调用本机 `/dev/session`；已有 Bearer 凭据则通过 `/me` 确认身份。

调试版仅对 `localhost`、`127.0.0.1`、Android 模拟器主机别名 `10.0.2.2` 允许 HTTP，其他地址和发布版要求 HTTPS。开发登录只用于本机调试，不能作为正式账号系统。客户端不保存云端模型密钥，访问会话 token 仅保存在运行内存中。

## 已实现的客户端范围

| 页面 | 当前行为 |
| --- | --- |
| 准备 | 服务端动作目录；按周数组/单个选择；低周数默认补选并尊重主动取消；连跳独立选择及 `3S+3T → 3S+2T` 默认项；自由状态、目标和比赛语境；推荐提交及已有方案打开 |
| 编排 | 原生动作行编辑、添加/删除、长按拖动及上移/下移、时间输入、撤销；服务端 BV/规则结果；保存与打开；推荐候选与依据 |
| 预案 | 服务端选择默认最高已知 BV 连跳情景；不确定时让用户直接选择；记录实际前缀；已发生行只读；后续行编辑、追加、删除和排序；服务端前缀核对和关联主方案保存 |
| 资料 | 自由文字入库；系统文件选择器上传任意 MIME；流式上传避免把视频整个读入内存；处理状态刷新；原文片段查看；检索；撤回；专业知识来源目录与官方链接 |
| 档案 | 当前记忆列表、来源类别与范围、原始来源阅读、纠正、遗忘；所有操作使用服务端当前身份 |

当前比赛输入是自由语境入口，尚未提供完整赛事库的搜索选择器。文档和视频的实际理解能力、进度状态及失败原因由服务端提供；客户端不会把“文件上传成功”展示成“已经完整理解”。

所有正式 BV 来自 `/scoring/evaluate` 或 `/scoring/recovery`。`scoreComplete=false` 或 `evaluatedTotal=null` 时显示“待核算”，不会拿 `knownSubtotal` 冒充完整分数。动作执行时间为空时不从行号推断后半程。

没有配置云端模型时，推荐状态 `PROVIDER_UNAVAILABLE` 会直接说明服务尚未配置，保留输入和草稿。`STALE`、`FAILED`、`REDACTED` 分别作为未完成或已停止使用的结果处理，不生成本地模拟方案。

## 状态与并发处理

- 输入草稿随编辑保存到应用私有存储，按服务地址与服务端选手 ID 隔离。更换服务/身份会清空上一身份的内存列表、来源和候选；不把本地选手 ID 当授权依据。
- 应用备份与设备迁移排除私人草稿存储。服务会话 token 不持久化，重新启动后需要重新连接。
- 分值请求绑定本机编辑版本；旧响应不能覆盖更新后的动作列表。高频编辑采用短延迟合并请求。
- 云端分析超过一轮轮询时保留任务 ID；准备页和编排页均可通过“查看最近一次分析”继续获取同一任务，避免重复提交。真实 GLM 联调见 [2026-11-15 记录](glm-live-validation-2026-11-15.md)。
- 保存带服务端方案版本；冲突返回后保留输入，要求读取最新方案再处理，不能静默覆盖其他设备编辑。
- 预案保存携带 `completedPrefix` 和 `sourcePlanId`，后端再次验证。重新打开预案时恢复固定前缀。
- 遗忘/撤回导致来源失效时，清空相关临时分析显示并呈现方案 `contextInvalidated` 状态；读取已清理的最新版本后，再由用户明确保存/重新生成。

## 文件结构

```text
android/
  app/src/main/java/com/skatingcoach/android/
    MainActivity.kt
    data/Models.kt           # 与 Java 接口对应的 DTO
    data/CoachApi.kt         # Bearer、HTTP、序列化、流式上传与错误
    ui/CoachViewModel.kt     # 当前页面、草稿、服务调用和响应版本
    ui/SelectionEditor.kt    # 输入便利逻辑，不包含能力推断或计分
    ui/CoachApp.kt           # 准备、编排、资料、档案及对话框
    ui/Theme.kt              # 深浅主题、字号和语义色彩
  app/src/test/              # 选择边界及 API 契约测试
```

## 验证范围

9 项单元测试覆盖：取消低周数默认项后不复活、单跳与连跳独立、取消连跳默认项后不复活、未知 BV 不转为部分小计、预案来源/冻结前缀序列化、嵌套资料来源及原文定位、Bearer 传递及 409 冲突保留；新增默认单跳类型必须发送、断网异常提供中文恢复提示。

构建命令同时执行 APK 编译、以上单元测试和 Android lint。本机已安装 Android 16 / API 36 x86_64 专用模拟器，并通过 Mobile MCP + ADB 执行原生界面流程及真实服务联调。设备配置、可重跑脚本、实际覆盖与修复见 [Android 设备自测记录](android-device-testing.md)。实体手机及不同厂商系统仍需后续验证。

UI 使用中文标签、Material 原生触控控件、系统文件选择器和可见的上移/下移替代拖动操作。已检查模拟器全屏截图中的横屏、小屏幕、1.5 倍字体、深色主题及系统软键盘；这些结果仅代表记录中的设备配置，不代替完整设备兼容性验收。
