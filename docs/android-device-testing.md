# Android 设备调试与回归

本项目使用 **Android Emulator + Mobile Next Mobile MCP + ADB**。Mobile MCP 是开发辅助工具，运行于开发机，不属于用户 APK 或后端运行依赖。

## 工具选择与连接方式

| 工具 | 本项目用途 | 官方资料 |
| --- | --- | --- |
| Android Emulator / ADB | API 36 虚拟设备、APK 安装、服务端口转发、日志和显示配置 | [Emulator](https://developer.android.com/studio/run/emulator)、[硬件加速](https://developer.android.com/studio/run/emulator-acceleration) |
| Mobile Next Mobile MCP | 用 MCP 读取原生界面、点击、输入、滑动、截图 | [项目](https://github.com/mobile-next/mobile-mcp) |
| Maestro | 已调研的候选，适合后续独立 YAML 流程；本轮未安装或声称执行 | [官方安装文档](https://docs.maestro.dev/maestro-cli/how-to-install-maestro-cli) |

`tools/mobile-testing/mobile.mjs` 使用 MCP SDK 建立真实 stdio 连接。调用 `initialize / tools/list / tools/call`，无需修改全局 IDE 配置，也不会开启网络监听端口。连接仅在测试进程内存在，不能等同于已经在 Codex 插件目录安装全局插件。

固定版本：`@mobilenext/mobile-mcp` 1.0.17、`@modelcontextprotocol/sdk` 1.32.2，传递依赖由 package-lock 固定。MCP 包为 Apache-2.0；其 mobilecli 二进制依赖使用 FSL-1.1-ALv2，不能将整个工具链统一称作 Apache-2.0。遥测关闭，未连接云设备账号。测试截图、界面文本、日志和模拟器数据均写入 Git 忽略目录。

## 准备与运行

需要 Node.js 20+、JDK 17、Android Platform Tools、Emulator 和 `system-images;android-36;default;x86_64`。SDK 默认位于 `android/.tools/sdk`，也可通过 `ANDROID_HOME` 指定。AVD 存储于 `android/.tools/avd`。

```powershell
# 项目根目录，先按 README 启动本机数据库与 Java 服务。
$env:JAVA_HOME = 'C:\path\to\jdk-17'
& android/.tools/sdk/cmdline-tools/latest/bin/sdkmanager.bat --sdk_root=android/.tools/sdk 'emulator' 'system-images;android-36;default;x86_64'
& scripts/start-android-test-device.ps1
npm.cmd ci --prefix tools/mobile-testing --ignore-scripts
node tools/mobile-testing/mobile.mjs mobile_list_available_devices
node tools/mobile-testing/mobile.mjs mobile_install_app --device skating_api36 --path D:/path/to/app-debug.apk
node tools/mobile-testing/mobile.mjs mobile_launch_app --device skating_api36 --packageName com.skatingcoach.android
node tools/mobile-testing/mobile.mjs mobile_list_elements_on_screen --device skating_api36
node tools/mobile-testing/mobile.mjs mobile_take_screenshot --device skating_api36
```

SDK manager 在本机曾出现下载停滞。实际安装回退为从 Google 的 `dl.google.com/android/repository` 下载原始 ZIP，并逐个比对 Google repository XML 的 SHA-1 后解压。使用 Emulator 37.2.13 / build 16740729、API 36 默认 x86_64 镜像 revision 2；没有改用第三方镜像。

`start-android-test-device.ps1` 检查硬件加速、创建专用 AVD、后台启动模拟器，并建立 `adb reverse tcp:8080 tcp:8080`。App 调试版服务地址为 `http://127.0.0.1:8080`，空访问凭据只适用于本机开发会话。

Mobile MCP 在本机返回的设备 ID 是 AVD 名 `skating_api36`；ADB serial 是 `emulator-5554`，不能混用。脚本可通过 `MOBILE_DEVICE` 和 `ADB_SERIAL` 指定另一台专用测试设备，执行前会核对两者是否指向同一设备。系统文件选择器脚本按本轮默认英文 AOSP 系统的 `Show roots / Downloads` 操作。

安装 APK 并在 App 连接本机服务后，顺序执行，不能同时操作同一台模拟器：

```powershell
npm.cmd run test:plan --prefix tools/mobile-testing
npm.cmd run test:profile --prefix tools/mobile-testing
npm.cmd run test:resilience --prefix tools/mobile-testing
npm.cmd run test:upload --prefix tools/mobile-testing
```

`test:resilience` 会在专用模拟器中停止并重启本 App、临时移除服务端口转发、旋转屏幕、修改字号/显示大小/深色模式，结束时恢复。`test:upload` 额外需要 PATH 中有 FFmpeg，本轮使用 8.1.1 生成一秒纯色测试视频，测试后删除本次产生的资料和模拟器文件；Java 测试使用仓库内同源 Base64 小样本，不依赖 FFmpeg。不要对个人日常手机或生产账号执行这些脚本。

测试报告、UI 树、MCP 截图和 ADB 全屏截图位于被 Git 忽略的 `tmp/mobile-testing/`。文件名带 `-mcp` 的是 MCP 截图，不带此后缀的是实际 ADB 全屏截图。编排自测会留下带 `MCP-` 唯一名称的开发方案供检查；资料/记忆测试只操作它自己创建的记录。

脚本不会清理已有真机或其他模拟器数据。自动化只应指定本项目专用测试设备。界面操作每次先获取当前可访问性节点，再按节点引用或实际位置操作；截图用于核对布局。ADB 用于日志、显示配置、文件推送和端口转发。

本轮发现 Mobile CLI 1.0.23 的快照可能省略 Android 系统软键盘和剪贴板浮层。Unicode 输入借助系统剪贴板，临时浮层会拦截应用按钮的点击。因此 `ui.mjs` 通过 `dumpsys input_method / window` 检查状态、等待浮层消失，再重新读取节点位置；用 `adb exec-out screencap -p` 检查完整屏幕。不能仅凭 MCP 返回“点击成功”就认定功能已执行，关键流程还核对真实 Java/PostgreSQL 状态。

## 验证边界

编译、单元测试、lint、设备界面流程和真实云端模型质量是不同的验证层。没有云端模型凭据时，只能检验推荐入口、状态与错误恢复；不能把它报告为教练建议质量已验证。设备结果与修复记录见本文件后续实测记录。

## 2026-11-14 实测与修复

环境：Windows、WHPX 硬件加速、Pixel 7 配置的 `skating_api36`，默认 1080×2400 / 420 dpi。兼容性用例临时改成 720×1280 / 320 dpi、字体 1.5 倍、深色模式并打开实际系统键盘。使用真实 Java HTTP 服务及 PostgreSQL/pgvector，没有用本地假数据代替 App API。

| 发现的问题 | 修复和验证方式 |
| --- | --- |
| Kotlin 默认 `SINGLE` 未序列化，单跳到服务端变成未知动作类型 | `PlanElement.type` 显式编码；契约测试及模拟器保存后读取真实方案确认 |
| 从列表打开已存预案时停留在主编排，可能沿用当前草稿的上下文 | 预案模式由 ViewModel 管理，恢复关联主方案、已完成前缀和原预案标题；实际打开后验证只读前缀 |
| 空白比赛范围被存成私有赛事键，通用资料未进入省略比赛范围的 RAG 检索 | 入库与查询统一处理空值，兼容旧空字符串记录；集成测试验证教练上下文、赛事过滤及跨用户隔离 |
| 键盘弹起时内容区域缺少明确的 IME 适配 | Scaffold 增加 IME inset，Activity 使用 adjustResize；核对小屏大字号的 ADB 全屏截图 |
| 连接失败直接显示底层英文异常 | 连接/超时错误转为可操作的中文提示并保留 cause；真实断网重启验证草稿仍在、恢复连接后可继续 |
| 上传 MP4 的容器信息被当成文档正文，显示部分可用 | 按文件实际内容检测媒体，等待后续视觉/音频理解，不生成证据块；生成的纯色视频及伪装成 text/plain 的视频均用于回归；客户端明确说明目前不能判断动作或能力 |
| Windows 服务运行时锁住 target JAR，重新打包失败 | 启动内容哈希命名的运行副本；已在服务保持运行时成功重新打包 |

| 检查 | 结果/覆盖 |
| --- | --- |
| Java `mvnw package` | 54 项测试，0 失败/错误/跳过；其中真实 PostgreSQL 集成 16 项 |
| Android `assembleDebug testDebugUnitTest lintDebug` | 构建通过；9 项单元测试通过；lint 0 errors、15 warnings |
| APK `apksigner verify` | 通过，调试签名 v2 |
| `smoke.mjs` | 5 项：登录与目录；单跳/连跳/执行时间保存；长按拖动、下移及撤销保持动作与时间关联；预案冻结前缀；直接重开预案 |
| `knowledge-memory.mjs` | 4 项：记忆创建/纠正/来源；遗忘；自由中文资料入库/检索/来源；撤回后检索不再返回 |
| `upload.mjs` | 2 项：Android 系统文件选择器上传 UTF-8 文本并核对实际内容；上传合成 MP4 后显示待分析、证据块为空 |
| `resilience.mjs` | 5 项：断网重启保留草稿并重连；未配置模型时保留输入；横屏；小屏/大字/深色/真实软键盘；本轮日志无本 App 崩溃或 ANR |

设备流程合计 16 项通过，详细结果在 `tmp/mobile-testing/` 的四份 `*-report.json` 中；每项保存实际 passed / failed 状态。后续修改后应重跑受影响流程，不能只依赖历史通过记录。完整屏幕截图包括 `recovery-reopened.png`、`uploaded-document.png`、`uploaded-video.png`、`offline-draft.png`、`landscape.png`、`small-keyboard.png`。

本轮不包含真实云端教练盲评、训练视频动作识别、实体手机、多厂商 ROM、低 Android 版本或发布签名验收。服务未配置 LLM/Embedding，设备流程核对的是明确的未配置状态；视频验证的是上传和诚实的待分析状态，不是能力判断正确率。原有 15 条 lint 警告仍保留，不用 baseline 隐藏。
