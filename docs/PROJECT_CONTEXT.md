# Fairy Voice 项目全上下文（PROJECT CONTEXT）

> **用途**：本文件用于在完全丢失上下文时，快速恢复对 Fairy Voice 双仓库项目的完整认知。
> **最后核对时间**：2026-08-13 11:58（CST）
> **核对人**：Fairy（配合程曦月开发）
> **维护规则**：每次里程碑/关键修复后更新本文件；代码文件级说明若有变动，同步修订对应小节。

---

## 0. 一句话定位

按钮触发式语音助手：**手机（C 端 Android App）负责录音/识别/TTS 播报**，**AstrBot 插件（B 端）负责 AI 大脑（LLM 生成 + 记忆管理 + 工具调用）**，两端通过 WebSocket 通信。C 端主动外连 B 端（穿透 NAT），B 端永不主动连 C。

---

## 1. 双仓库速览

| 角色 | 本地路径 | GitHub 仓库 | 技术栈 | 里程碑状态 |
|---|---|---|---|---|
| **B 端插件**（AstrBot Star） | `D:\project\fairy-voice` | `silver-wolf-little-wife/fairy-voice` | Python 3 + aiohttp，仓库根即插件根 | M2 ✅（M4 阶段 B 端基本无需改动） |
| **C 端终端**（Android App） | `D:\project\fairy-voice-android` | `silver-wolf-little-wife/fairy-voice-app` | Kotlin + OkHttp，minSdk 26 / targetSdk 34 | M3 ✅，**M4-1 录音 ✅（2026-08-13），进行 M4-2 语音识别** |
| AstrBot 本体（B 端运行环境） | `D:\project\AstrBot` | - | Python 3.13.14 | 供插件运行，本地有完整 git 仓库 |

> ⚠️ **目录名注意**：本地 C 端目录叫 `fairy-voice-android`，GitHub 仓库名是 `fairy-voice-app`（历史遗留，README/PROTOCOL 文档里写的都是 fairy-voice-app）。B 端插件目录 `D:\project\fairy-voice` 才是这个名字。

---

## 2. 架构与数据流

```
手机（C 端，fairy-voice-android）                    B 端（AstrBot 插件，fairy-voice）
┌─────────────────────────────────┐                ┌─────────────────────────────────┐
│ 唤醒入口（三选一）                 │                │ aiohttp WS 服务端 :8766/ws        │
│  A. 无障碍音量上+下 0.5s          │   WS 主动外连    │  hello/token 握手 → hello_ack     │
│  B. 控制中心磁贴                  │ ────────────▶  │  ping → pong（60s 超时清理）       │
│  C. 通知栏「唤醒」按钮             │    JSON 帧      │  ask 请求 → llm_generate /        │
│   ↓ M4-1: 开始录音（VoiceController）│              │  tool_loop_agent（3轮+5分钟记忆）  │
│ AudioRecord 16kHz/16bit/单声道    │ ◀────────────  │  response 帧回传 AI 回复          │
│   → WAV(filesDir/recordings)     │    response    │                                  │
│   ↓ M4-2: ASR 识别（待开发）       │                │                                  │
│ sendAsk(文本) → 收到回复          │                │                                  │
│   ↓ M4-3: TTS 播报（待开发）       │                │                                  │
└─────────────────────────────────┘                └─────────────────────────────────┘
```

- **角色分工**：C = 纯语音终端（只采集与播报，不做 AI 判断）；B = AI 大脑（LLM + 记忆）。
- **协议**：UTF-8 JSON 文本帧，JSON-RPC 风格，`id` 关联请求/响应。完整定义见 B 端 `docs/PROTOCOL.md`（双端共享，改协议必须双端同步）。
- **与 cherry 项目的区别**：cherry 是 B→C 下发指令（C 为执行器）；本协议是 C→B 上送语音指令（C 为终端）。

---

## 3. 里程碑与任务状态（来自 B 端 docs/DEV_PLAN.md）

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M1 协议定稿 | WS 协议 v1.0.0（hello/心跳/ask），文档写入 PROTOCOL.md | ✅ |
| M2 B 端插件骨架 | WS 服务端、记忆策略、LLM 接入、tool_loop_agent、/fairy 命令 | ✅ |
| M3 C 端语音终端骨架 | Android App：WS 客户端、前台服务、三路唤醒、手动输入联调 | ✅ 实机验证（android-phone 上线问答通） |
| **M4 语音闭环** | 录音 / ASR / TTS / 完整链路 / 状态机 / 唤醒词预留 | 🔶 **进行中（M4-1 录音已完成，M4-1.1 唤醒/分享修复已完成）** |
| M5 打磨与安全 | TTS 设置、wss、Release 打包、开机自启 | ⬜ |

### M4 子任务清单（DEV_PLAN.md 原文）

- [x] 录音（AudioRecord 16kHz/16bit/单声道 → WAV）← **M4-1，已完成 2026-08-13**
- [x] ~~磁贴/通知栏唤醒点击无反应、磁贴常亮~~ ← **M4-1.1 修复（Intent action 驱动 + 恒 INACTIVE），2026-08-13**
- [ ] 语音识别（本地 whisper.cpp/faster-whisper 或云端 ASR API）← M4-2
- [ ] TTS 播报（Android 系统 TTS 或 edge-tts/云端 TTS）← M4-3
- [ ] 完整链路：触发 → 录音 → 识别 → WS 发送 → AI 回复 → TTS 播报
- [ ] 不做连续问答 UI（按一次答一次），AI 侧保留 3 轮上下文（B 端已实现）
- [ ] 状态机：空闲/录音中/识别中/等待AI/播报中（界面/通知栏显示状态）← 状态枚举已就位（VoiceController.State），UI/通知栏展示待接
- [ ] 唤醒词预留：可在录音链路前加轻量唤醒（可选项）

### M4 验收标准（DEV_PLAN.md）

- 按热键 → 3 秒内开始录音，识别文本 3 秒内返回
- WS 断线 15 秒内自动重连，重连后状态同步
- AI 回复从发送到 TTS 播报 < 10 秒（视模型速度）
- 常驻挂机内存 < 150MB，空闲 CPU < 1%
- 超 5 分钟未对话后再次指令，AI 无上一段记忆（B 端逻辑，已验证）

---

## 4. C 端代码地图（fairy-voice-android，逐文件）

源码根：`app/src/main/java/com/fairyvoice/app/`

### 4.1 protocol/Protocol.kt
- **职责**：协议帧模型，纯 Kotlin + org.json，可在 JVM 单测。
- 关键内容：
  - `PROTOCOL_VERSION = "0.1.0"`（C 端声明的 client_version）
  - `helloFrame(token, deviceId)` / `pingFrame()` / `askFrame(id, text, lang)` 三个 C→B 帧构造
  - `HelloAck`（ok/sessionId/error）与 `ResponseFrame`（id/ok/text/errorCode/errorMessage）两个 B→C 帧解析（解析失败返回安全兜底对象，不抛异常）

### 4.2 protocol/FairyVoiceClient.kt
- **职责**：B 端 WS 客户端，纯 Kotlin + OkHttp，可在 JVM 单测（MockWebServer）。
- **生命周期**：`start()`（幂等，`loopRunning` CAS 防止重复提交重连循环）→ `runLoop()`（连接 + 指数退避重连 1s→60s 封顶）→ `stop()`。
- **握手流程**：onOpen → 发 hello → 等 hello_ack（`helloLatch`，10s 超时）→ ack.ok 才置 `connected=true`、触发 `onReady`、启动心跳。
- **⚠️ M4-1 修复（2026-08-13）**：握手期间连接被断开或超时 → 抛 `IOException` 走**静默退避重试**，不再误报 `onAuthError`；`onAuthError` 仅由 `ack.ok=false`（token 被拒）触发。原实现把「B 端重启导致的握手中断」误报成 `bad_ack` 认证错误，且导致重连单测永远等不到第二次 onReady。
- **消息路由**：握手阶段只认 `hello_ack`；握手后只处理 `response`（按 id 匹配 `pending` map 的 CompletableFuture）。
- **ask**：`sendAsk(text, lang)` 阻塞等待（askTimeoutMs 默认 60s），超时/断开抛 `FairyVoiceException` 子类（NotConnected / AuthError / AskError）。
- **回调**：onReady / onReply / onDisconnect / onAuthError（均在客户端工作线程触发，调用方自行切线程）。
- **配置快照**：`serverUrlValue/tokenValue/deviceIdValue` 暴露给 FairyClientHolder 判断是否需要重建。
- **⚠️ 历史坑**：`@Volatile` 只能注解成员变量不能注解局部变量；stop() 要中断 runLoop 的 sleep 才能让下次 start() 生效。

### 4.3 FairyClientHolder.kt
- **职责**：全局客户端持有者（单例 object）。
- `createOrGet(serverUrl, token, deviceId, heartbeatMs, askTimeoutMs)`：配置未变复用实例；**配置变了先 stop 旧实例再新建**（否则一直连旧地址/token）。
- `clear()`：停客户端并置空（ConnectionService.onDestroy 调用）。

### 4.4 service/ConnectionService.kt
- **职责**：常驻前台服务，持有客户端，通知栏显示状态 + 「唤醒」按钮。
- onStartCommand 读取 Prefs → `FairyClientHolder.createOrGet` → `start()`；`START_STICKY` 保证被杀后重启。
- 前台通知：`FOREGROUND_SERVICE_TYPE_DATA_SYNC`（API 29+ 用带 type 的 startForeground）；小图标 `ic_menu_compass`；「唤醒」按钮 = PendingIntent 拉起 MainActivity。
- **⚠️ M4-1.1 修复**：唤醒 PendingIntent 改用 `WakeTrigger.wakeIntent()`（带 `ACTION_FAIRY_WAKE`）。原实现 Intent 无 action，MainActivity.onNewIntent 识别不了 → 点击无反应。
- M4 挂接点：状态机后续建议挂在此服务（M4-6）。

### 4.5 util/Prefs.kt
- **职责**：配置存储。token 用 EncryptedSharedPreferences（AES256_GCM），**初始化失败降级为普通 SharedPreferences**（个别模拟器）。
- 键：KEY_SERVER_URL / KEY_TOKEN / KEY_DEVICE_ID / KEY_HEARTBEAT_MS / KEY_ASK_TIMEOUT_MS / KEY_WAKE_COMBO（唤醒组合，vol_up+vol_down 或 vol_up_long）。

### 4.6 wake/WakeKeyService.kt（含 WakeTrigger）
- **WakeTrigger**（统一唤醒入口，磁贴/无障碍/通知按钮共用）：
  - `wakeIntent(context)`：构造带 `action=ACTION_FAIRY_WAKE` + `FLAG_ACTIVITY_NEW_TASK|SINGLE_TOP` 的 MainActivity Intent。
  - `trigger(context)`：`startActivity(wakeIntent(context))`。
  - **⚠️ M4-1.1 修复**：旧实现「startActivity（无 action）+ sendBroadcast」双通道——Intent 无 action 导致热启动 onNewIntent 识别不了；广播在 App 冷启动时无人接收。现统一由 Intent action 驱动 onCreate/onNewIntent 直接触发录音，**已彻底移除广播**（防止热启动双触发导致「开始立刻停止」）。
- WakeKeyService：无障碍服务，监听音量键组合（默认音量上+下 0.5s），**不消费按键事件**（onKeyEvent 恒返回 false，音量调节不受影响）；2s 防抖；onInterrupt/onDestroy 清理。
- **⚠️ 边界**：电源键事件被系统独占，任何第三方 APP 无法监听（无 root 无解）；息屏时收不到任何按键事件。

### 4.7 wake/FairyVoiceTileService.kt
- **职责**：控制中心磁贴（OPPO 无系统级按键映射 ROM 的替代唤醒入口）。
- **⚠️ M4-1.1 修复**：
  1. **磁贴是「动作按钮」不是开关**：恒 `STATE_INACTIVE`（暗态可点击），连接状态只放副标题（「已连接 · 点击唤醒」/「点击唤醒」）。旧实现连接成功即设 STATE_ACTIVE，用户看到「一直亮着」且误以为不可点。
  2. **点击唤醒**：`startActivityAndCollapse(WakeTrigger.wakeIntent(this))` 拉起主界面并收起控制中心；API 28+ 用 PendingIntent 变体（Intent 变体在 API 34 弃用），API 26/27 走旧接口（@Suppress 去警告）。
- 锁屏态（isLocked）不响应，防误触。

### 4.8 MainActivity.kt
- **职责**：连接配置 UI + 状态轮询 + M3 联调入口（手动输入指令触发 ask）+ M4-1 录音测试入口 + M4-1.1 录音分享 + 唤醒引导。
- 关键点：
  - 前台每 2s 轮询刷新连接状态（statusPoll），无需重进页面。
  - singleTask 模式：后台唤醒走 `onNewIntent`；**冷启动唤醒在 onCreate 检查 `intent.action == ACTION_FAIRY_WAKE` 后 post handleWake**（M4-1.1 新增，广播已删，两条路径都靠 Intent action）。
  - **M4-1.1 已移除动态广播 receiver**（wakeReceiver 及其 onResume/onPause 注册注销）——广播与 onNewIntent 双触发会导致 toggle 两次（开始立刻停止）。
  - Android 13+ 请求 POST_NOTIFICATIONS 运行时权限；点「启动连接」时才请求。
  - **M4-1 `handleWake()`** = 请求 RECORD_AUDIO 运行时权限（API 23+）→ `VoiceController.toggle(this)`（录音中再按一次=停止）；权限被拒 Toast 提示。onRequestPermissionsResult(RC_RECORD_AUDIO=101) 授权后 `startRecording`。
  - **M4-1 录音测试按钮** `btnRecordTest`：与唤醒同走 `requestRecordPermissionAndToggle`；`tvVoiceState` 显示状态机当前态。
  - **M4-1.1 录音结果回显**：onRecorded 显示 `文件名（X.X 秒 / Y KB）`（WAV 头 44 字节，32000 B/s 换算时长），供用户不导出也能确认录音非空。
  - **M4-1.1 分享按钮** `btnShareRecord`：FileProvider 授权 URI（authority=`${applicationId}.fileprovider`，白名单 `res/xml/file_paths.xml` 仅 recordings/）+ `ACTION_SEND`（type audio/wav）走系统分享面板，可导出到文件管理/微信/电脑。`lastRecordedFile` 记录最近一次录音。
  - onSendAskClick：开 Thread 调 `client.sendAsk`（阻塞调用不能放主线程）。

### 4.9 FairyVoiceApp.kt
- **职责**：全局 Application，兜底捕获未处理异常写入 `crash_log.txt`（filesDir），不吞异常，落盘后仍交给系统。

### 4.10 资源与布局
- `res/layout/activity_main.xml`：ScrollView + LinearLayout，深色主题（fairy_bg #0F1B2D，fairy_blue #4FC3F7），从上到下：标题 → 状态 → B 端连接配置（地址/token/设备ID/心跳）→ 启动/停止 → **M4 录音区（录音按钮 + 分享按钮 + 状态 + 说明，M4-1/M4-1.1）** → M3 联调区（输入框+发送+回复回显）→ 唤醒方式（无障碍按钮+说明）→ 控制中心磁贴（添加按钮+说明）。
- `res/values/strings.xml`：全部文案（含 M4 录音区、分享、磁贴副标题文案）。
- `res/xml/accessibility_service_config.xml`：无障碍服务配置（flagDefault|flagRequestFilterKeyEvents、canRequestFilterKeyEvents=true，仅 typeWindowStateChanged）。
- `res/xml/file_paths.xml`（M4-1.1 新增）：FileProvider 白名单，仅 `files-path recordings/`。

### 4.11 测试（app/src/test/java/com/fairyvoice/app/）
- `protocol/ProtocolTest.kt`：7 用例，帧构造与解析（含垃圾输入安全解析）。
- `protocol/FairyVoiceClientTest.kt`：5 用例，MockWebServer 模拟 B 端（握手成功 / token 错误 / ask 往返 / 未连接抛错 / 断线自动重连）。**⚠️ 重连用例 M4-1 修复**：原用例在 hello_ack 前断开连接，导致客户端按「握手中断」处理、第二次 onReady 永不触发；已改为「先回 ack 再断开」真正覆盖断线重连路径。
- `audio/WavHeaderTest.kt`：6 用例（M4-1 新增），WAV 头长度/魔数/字段/小端/回填，纯 JVM 无 Android 依赖。

### 4.12 构建配置
- 根 `build.gradle.kts`：AGP `8.7.3` + Kotlin `1.9.24`（apply false）。
- `settings.gradle.kts`：google() + mavenCentral()，`FAIL_ON_PROJECT_REPOS`。
- `gradle.properties`：jvmargs -Xmx2048m、useAndroidX、nonTransitiveRClass、`suppressUnsupportedCompileSdk=36`。
- `gradle/wrapper/gradle-wrapper.properties`：Gradle 8.9。
- `local.properties`：`sdk.dir=C:/Users/13370/AppData/Local/Android/Sdk`（**已被 .gitignore 忽略**）。
- app 模块：namespace/applicationId `com.fairyvoice.app`，compileSdk 36 / minSdk 26 / targetSdk 34，Java+Kotlin 17，versionName 0.1.0。
- 依赖：core-ktx 1.13.1 / appcompat 1.7.0 / material 1.12.0 / constraintlayout 2.1.4 / security-crypto 1.1.0-alpha06 / okhttp 4.12.0；测试 junit 4.13.2 / mockwebserver 4.12.0 / org.json:json 20240303（JVM 单测无 Android org.json）。

### 4.13 audio/ 模块（M4-1 新增，语音链路）
- **audio/WavHeader.kt**：44 字节 RIFF/WAVE 头生成，纯 Kotlin 无 Android 依赖（JVM 单测）。`build(dataSize, sampleRate=16000, channels=1, bitsPerSample=16)` 生成头；`patch(header, dataSize)` 录制结束后回填 data size。
- **audio/AudioRecorder.kt**：AudioRecord 录音循环（16kHz/16bit/单声道）。先写 44 字节头占位 → 流式写 PCM（单线程 executor `fairy-audio-rec`）→ 结束回填 WAV 头。默认 15 秒时长上限自动停止；start/stop 幂等；回调 onStart/onStop(file)/onError。权限由调用方保证。
- **audio/VoiceController.kt**：语音链路状态机控制器（单例 object）。`State{IDLE, RECORDING, RECOGNIZING, WAITING_AI, SPEAKING}`（M4-1 只实现 IDLE↔RECORDING，其余为 ASR/TTS 预留）；`toggle(context)` 统一入口（录音中=停止，空闲=开始）；录音文件 `filesDir/recordings/fairy_yyyyMMdd_HHmmss.wav`；Listener{onStateChanged/onRecorded/onError} 回调在录音线程触发。**注意：filesDir 为应用私有沙盒，文件管理器不可直接访问，导出走 MainActivity 分享按钮（FileProvider）**。

---

## 5. B 端代码地图（fairy-voice，逐文件）

源码根：`D:\project\fairy-voice\`（仓库根即插件根）

### 5.1 main.py —— 插件主逻辑
- `FairyVoice(Star)`，register 名 `astrbot_plugin_fairy_voice`。
- `initialize()`：按配置启动 `FairyWsServer`（asyncio.create_task），ask_handler 绑定 `_handle_ask`。
- `_handle_ask(device_id, text)` 核心流程：
  1. `_build_event` 伪造最小 AstrMessageEvent（umo = `fairy_voice:friend:{device_id}`）
  2. `_resolve_provider_id`：先按会话 umo 取 provider，失败回退全局默认，再失败抛错
  3. 记忆：`session.add_user(text)` → 超轮则 `pop_oldest_round` 交给 LLM 压缩进 summary（**压缩失败只 warning 不阻断对话**）→ 组装 contexts
  4. `enable_tools` 开 → `tool_loop_agent(event, chat_provider_id, contexts, tools=_all_tools(), max_steps)`；关 → `llm_generate(system_prompt=summary, contexts)`
  5. `session.add_assistant(reply)` → 返回回复文本
- `_all_tools()`：从 `context.get_llm_tool_manager()` 收集 `func_list + builtin_func_list`，包成 ToolSet（**工具注册表全局共享，米家/远程电脑等已注册工具都可被语音指令调用**）。
- `_build_event`：构造最小 AstrBotMessage（FRIEND_MESSAGE、session_id=device_id、sender=MessageMember(user_id=device_id, nickname="FairyVoice")、message=[Plain(text)]）+ PlatformMetadata，三个类均可无平台构造（已源码验证 v4.27.x）。
- `/fairy` 命令：查看在线设备（device_id + session 前 8 位 + 工具模式）+ 每设备记忆状态（摘要有无/最近轮数/是否过期）。
- **⚠️ 版本兼容**：`MessageType` 双路径导入（v4.27.2 起在 `core.platform.message_type`，旧版在 `core.message.message_type`）；其余 agent.message / platform 模块 import 失败时置 None 并在使用时抛明确错误。

### 5.2 ws_server.py —— aiohttp WS 服务端
- `FairyWsServer(port, token, heartbeat_timeout=60, ask_handler)`，仅依赖 aiohttp，独立于 AstrBot 可单测。
- 路由：`GET /ws`；max_msg_size 4MB。
- `_handle_hello`：token 错 → hello_ack ok:false + close 4001；缺 device_id → close 4002；**同 device_id 重复接入 → 关旧连接（close 4000）**；成功回 hello_ack(session_id=uuid, server_version=0.1.0)。
- `_handle_ask`：空文本 → empty_text；按 device_id 串行锁（asyncio.Lock）保证记忆无竞态；handler 异常/超时 → llm_error 错误码回传不中断连接。
- 心跳循环：每 heartbeat_timeout 扫一遍，`now - last_seen > timeout` 则清理设备+记忆+关 ws。
- `device_summary()`：在线设备列表（/fairy 命令用）。

### 5.3 memory.py —— 记忆策略
- 规则：保留最近 `rounds` 轮（默认 3 轮 = 6 条消息）原文；超出且 TTL 内（默认 300s=5 分钟）再对话 → 最早 1 轮压缩为摘要；超 TTL → 清空全部（含摘要）。
- `MemorySession`：summary / recent（[{"role","content"}]）/ last_active；needs_compress = len(recent) > rounds*2；pop_oldest_round 弹最早 2 条；expired 判定。
- `MemoryManager`：按 device_id 管理，get() 不存在则新建、已过期则 clear 重开（对象复用）；drop 删除。

### 5.4 配置项（_conf_schema.json）

| 配置项 | 默认 | 说明 |
|---|---|---|
| ws_port | 8766 | WS 服务端口 |
| auth_token | change-me | 握手认证 token（C 端必须一致） |
| heartbeat_timeout | 60 | 心跳超时（秒） |
| memory_ttl | 300 | 记忆保留时长（秒） |
| memory_rounds | 3 | 保留最近对话轮数 |
| enable_tools | false | 启用 tool_loop_agent（实机验证后建议开） |
| tool_max_steps | 10 | 工具循环最大步数 |

### 5.5 测试与运行
- `tests/test_memory.py`：5 用例（3 轮不压缩 / 第 4 轮压缩 / 过期清空 / 过期标志 / drop）。
- `tests/test_ws_smoke.py`：冒烟 7 场景（错误 token 拒绝 / 握手 / ping-pong / ask 往返 / 空文本 / handler 异常→llm_error / device_summary），本地起服 127.0.0.1:8877。
- 运行：`python tests/test_memory.py`、`python tests/test_ws_smoke.py`（.venv 已存在，requirements 仅 aiohttp>=3.9）。
- 部署：克隆到 AstrBot `data/plugins/astrbot_plugin_fairy_voice/`（或目录名 clone），重启 AstrBot，配置 token 等。
- **⚠️ 部署现状待确认**：在 `D:\project\AstrBot\data\plugins` 下未找到 fairy 相关目录（可能部署在其他位置或尚未部署到这台 AstrBot）。

---

## 6. 通信协议 v1.0.0 速查（docs/PROTOCOL.md）

### 帧格式（UTF-8 JSON 文本帧）

| 方向 | 帧 | 字段 |
|---|---|---|
| C→B | hello | type, token, device_id, client_version |
| B→C | hello_ack | type, ok, session_id(成功)/error(失败) |
| C→B | ping | type |
| B→C | pong | type |
| C→B | ask | type, id, text, lang(可选默认zh-CN) |
| B→C | response | type, id, ok, data.text(成功)/error.code+message(失败) |

### 错误码
`invalid_token` / `missing_device_id` / `empty_text` / `busy` / `provider_not_found` / `llm_error` / `internal_error`

### 心跳与超时
- C 每 15s 发 ping；B 回 pong。
- B 60s 未收到设备任何帧 → 判定掉线，清理设备与记忆。
- C 若 3 个心跳周期（45s）未收到 B 任何帧 → 主动断开重连（当前客户端实现：重连由连接断开事件驱动，未实现独立超时检测，M4 可评估补上）。

### 安全
- 握手必须有效 token，无效立即断开（close 4001）。
- 同 device_id 重复接入 → B 关旧连接（close 4000）。
- 传输建议 wss/TLS（生产）；C 端 token 用 EncryptedSharedPreferences 存储（已实现）。

---

## 7. 构建 / 测试 / 运行速查

### C 端（fairy-voice-android）
```bash
cd /d D:\project\fairy-voice-android
set "JAVA_HOME=C:\Users\13370\.jdks\jbr-21.0.11"   # 必须 JDK 17~22；JBR 25 不被 Gradle 8.9 支持
gradlew.bat testDebugUnitTest          # 18 用例（协议7 + 客户端5 + WAV头6）
gradlew.bat assembleDebug              # 产物 app/build/outputs/apk/debug/app-debug.apk
```
- 环境：Android SDK 位于 `C:\Users\13370\AppData\Local\Android\Sdk`（platforms + build-tools 已装）；**系统 PATH 无 java/JAVA_HOME**，构建必须显式指定 JDK。可用 JDK：`C:\Users\13370\.jdks\jbr-21.0.11`（**推荐**，Gradle 8.9 支持）；Android Studio JBR 与 PyCharm JBR 均为 Java 25，**不可用**（报 `25.0.2` 版本错误）。
- 已构建产物：`app-debug.apk` 约 8.3MB（2026-08-13 11:56，M4-1.1）。
- 安装：`adb install -r app/build/outputs/apk/debug/app-debug.apk`。

### B 端（fairy-voice）
```bash
cd /d D:\project\fairy-voice
python tests/test_memory.py        # 记忆策略单测（5 用例）
python tests/test_ws_smoke.py      # WS 冒烟（7 场景，起服 8877）
```
- Python 3.13.14 可用；依赖 aiohttp>=3.9（.venv 已建）。

### 联调方式（M3 已验证）
1. B 端插件在 AstrBot 中加载，配置 ws_port/auth_token。
2. App 填 `ws://<B端IP>:<port>/ws` + token + 设备 ID，点「启动连接」。
3. 通知栏出现常驻通知即连接成功；输入框发 ask 看回复（M3 联调入口）。
4. 唤醒：无障碍音量键组合 / 控制中心磁贴 / 通知栏按钮（**M4-1.1 起三路均直接开始/停止录音**）。
5. **M4-1 录音验证**：点「开始录音（测试）」或任意唤醒 → 状态变「录音中…」→ 再按一次停止 → tvReply 显示「文件名（X.X 秒 / Y KB）」→ 点「分享录音」导出到文件管理/微信验听。

---

## 8. 已知问题 / 坑 / 待办（重要）

1. **✅ 已解决（M4-1 提交）**：`usesCleartextTraffic="true"` 改动此前一直未提交（targetSdk 28+ 默认禁明文，不开则 ws:// 被 OkHttp 直接拒绝、无限重连卡「连接中」），已随 M4-1 一并提交。
2. `build*.log`（含历史 build.log/build.err.log 与临时构建日志）已被 .gitignore 忽略，勿参考其内容（均为已修复的历史失败）。
3. ws:// 明文依赖 usesCleartextTraffic；生产建议 wss。
4. Android 14+ 动态注册广播必须 RECEIVER_NOT_EXPORTED，否则启动即崩（M4-1.1 已删掉该广播，此坑仅历史）。
5. 重复 start() 必须幂等（FairyVoiceClient 已用 loopRunning CAS 处理，勿破坏）。
6. 配置变更必须重建客户端（FairyClientHolder.createOrGet 已处理）。
7. 电源键无法被第三方监听（系统独占），息屏无法音量键唤醒；OPPO 无「电源键+音量上」映射任意 App 能力（详见 C 端 docs/WAKEUP.md）。
8. B 端 MessageType 导入需双路径兼容（v4.27.2+ 新路径），改 import 时小心。
9. 记忆只存内存，B 端重启即清空（符合设计）。
10. C 端本地目录名 fairy-voice-android ≠ GitHub 仓库名 fairy-voice-app，文档表述混用，注意别建错 remote。
11. **⚠️ 构建 JDK**：本机 PATH 无 java；JBR（Android Studio / PyCharm）是 Java 25，Gradle 8.9 会直接失败（报版本号 `25.0.2`）。构建前必须 `set "JAVA_HOME=C:\Users\13370\.jdks\jbr-21.0.11"`。
12. **⚠️ 重连语义（M4-1 修复）**：FairyVoiceClient 握手期间连接断开/超时 = 瞬时故障（静默退避重试），只有 ack.ok=false 才触发 onAuthError。测试重连用例必须「先回 ack 再断开」，否则第二次 onReady 不会发生。
13. **录音权限**：RECORD_AUDIO 运行时权限由 MainActivity 请求（RC_RECORD_AUDIO=101）；后台唤醒路径（磁贴/通知栏）会先拉起 MainActivity 再申请，勿在 Service/Tile 里直接 startRecording（无 Activity 上下文无法弹权限框）。
14. AudioRecord 本身无法 JVM 单测，M4-1 只测了 WAV 头纯逻辑；录音链路需真机冒烟（见第 7 节联调步骤 5）。
15. **⚠️ 唤醒必须由 Intent action 驱动（M4-1.1 修复）**：WakeTrigger.wakeIntent 带 `ACTION_FAIRY_WAKE`，MainActivity 靠 onCreate（冷启动）/onNewIntent（热启动）识别。**勿退回「无 action 的 startActivity + 广播」方案**——那是「点击无反应」的根因；也勿让广播与 onNewIntent 并存（双触发 = 开始立刻停止）。
16. **磁贴是动作按钮**：恒 STATE_INACTIVE（暗态可点击），连接状态只放副标题，勿再按连接状态切 STATE_ACTIVE（用户会误以为「一直亮着」是异常）。
17. **FileProvider**：authority 固定 `${applicationId}.fileprovider`（= com.fairyvoice.app.fileprovider）；白名单 file_paths.xml 仅 recordings/，加新导出目录需同步更新。
18. **录音文件在应用私有沙盒**（filesDir/recordings），文件管理器不可直接访问，属正常现象；导出走「分享录音」（ACTION_SEND + FileProvider）。真机 adb 验证：`adb pull /sdcard/Android/data/com.fairyvoice.app/files/recordings/ xxx.wav`。

---

## 9. M4-1 / M4-1.1 实施总结（2026-08-13，已完成）

**M4-1 任务**（DEV_PLAN.md M4 第一条）：录音（AudioRecord 16kHz/16bit/单声道 → WAV）。

### M4-1 交付内容
- **新包 `audio/`**：`WavHeader.kt`（WAV 头纯逻辑，JVM 单测 6 用例）+ `AudioRecorder.kt`（录音循环，15s 上限，单线程 executor，幂等 start/stop）+ `VoiceController.kt`（语音链路状态机，IDLE↔RECORDING 已实现，其余状态为 M4-2 预留）。
- **唤醒接入**：MainActivity.handleWake = 「请求 RECORD_AUDIO → toggle 录音（再按一次停止）」；WakeTrigger 触发。
- **UI**：M4 录音区（开始/停止按钮 + 状态文本 + 说明），录音结果/错误回显 tvReply。
- **顺带修复**：FairyVoiceClient 握手断开误报 onAuthError（改静默重试）；FairyVoiceClientTest 重连用例设计缺陷（先回 ack 再断开）；构建 JDK 定位（jbr-21.0.11）。
- **验证**：单测 18/18 通过；assembleDebug 成功（APK 8.3MB）。

### M4-1.1 修复（2026-08-13，真机反馈驱动）
- **问题 1：磁贴/通知栏唤醒点击无反应**。根因：WakeTrigger 的 Intent 没带 WAKE action（onNewIntent 识别不了），且广播在冷启动时无人接收。修复：Intent 统一带 `ACTION_FAIRY_WAKE`，MainActivity onCreate（冷启动 post handleWake）/onNewIntent（热启动）直接触发；**移除广播**；通知栏 PendingIntent 改走 wakeIntent；磁贴用 startActivityAndCollapse（API 28+ PendingIntent 变体）。
- **问题 2：磁贴连接后一直亮**。根因：onStartListening 按连接状态设 STATE_ACTIVE。修复：磁贴是「动作按钮」恒 STATE_INACTIVE，连接状态放副标题（「已连接 · 点击唤醒」/「点击唤醒」）。
- **问题 3：录音文件在沙盒无法访问**。修复：onRecorded 回显「文件名（时长 / 大小）」即时确认非空；新增「分享录音」按钮（FileProvider + ACTION_SEND）导出到文件管理/微信等。
- **验证**：构建零警告，单测 18/18，assembleDebug 成功（APK 8.3MB，11:56）。
- **待真机验证**：三路唤醒（音量键/磁贴/通知栏）点击即录音、再按即停止；磁贴恒暗态、副标题随连接状态变化；录完分享导出 WAV 可播放；15s 自动停止。

### 后续任务链（M4-2 起）
ASR（本地 whisper 或云端 API）→ TTS（系统 TTS 或 edge-tts）→ 完整链路（VoiceController 扩展 RECOGNIZING/WAITING_AI/SPEAKING 流转）+ 状态机 UI/通知栏展示 → M5 打磨。

---

## 10. 提交规范（双仓库通用）

- 两个仓库独立提交、独立 push（origin 均指向 silver-wolf-little-wife）。
- 提交信息风格：`feat:` / `fix:` / `docs:` / `refactor:` / `chore:` + 简短中文说明（参考现有历史：`fix: MessageType 双路径导入...`、`docs: 完善 README...`）。
- 代码头部保留 SPDX 注释 `// SPDX-License-Identifier: AGPL-3.0-only`（Kotlin）/ `# SPDX-License-Identifier: AGPL-3.0-only`（Python）。
- 提交前跑通对应单测：C 端 `gradlew.bat testDebugUnitTest`，B 端 `python tests/test_memory.py` + `tests/test_ws_smoke.py`。
- 协议改动必须双端同步更新 PROTOCOL.md 与两端实现。

---

## 附：关键文档索引

| 文档 | 位置 | 内容 |
|---|---|---|
| 开发计划 | B 端 `docs/DEV_PLAN.md` | 里程碑、M4/M5 子任务、验收标准、B 端关键技术点 |
| 通信协议 | B 端 `docs/PROTOCOL.md` | 协议 v1.0.0 全文 |
| C 端部署 | C 端 `docs/DEPLOY.md` | 构建/安装/验证清单/M4 预留 |
| 唤醒方案 | C 端 `docs/WAKEUP.md` | OPPO 调研、四种唤醒方案对比与代码位置 |
| 本文件 | C 端 `docs/PROJECT_CONTEXT.md` | 全上下文（当前文件） |
