# fairy-voice-android

fairy-voice 的 C 端 Android 语音终端（**现行：OneBot V11 直连 AstrBot + 本地 ASR**）。

> **现行方案（2026-08-14 起）**：C 端伪装成最小 OneBot V11 客户端直连 AstrBot 原生适配器（aiocqhttp），
> ASR 本地化（sherpa-onnx），**替代自研 B 端插件**。P0 技术验证已通过，见下方「现行方案」。
> **历史实现**（2026-08-14 前）：基于自研 B 端插件 `fairy-voice`（已归档）的自研 WS 协议，见文末「老方案（历史归档）」。
> 本仓库替代原 Python 桌面版 `fairy-voice-app` 作为手机端终端：WS 常驻连接 + 实体键唤醒，M4 起挂接录音/识别/TTS。

## 现行方案（OneBot V11 直连 AstrBot + 本地 ASR）

> 完整计划与阶段划分见 `docs/PLAN_ONEBOT_MIGRATION.md`。

**动机**：砍掉自研 B 端插件，C 端伪装成最小 OneBot V11 客户端直连 AstrBot 原生适配器（aiocqhttp），
白嫖多 provider / 工具 / 会话记忆 / dashboard；ASR 本地化（sherpa-onnx），数据不出手机。

```
手机（C 端 = 最小 OneBot 客户端 + 本地 ASR）           AstrBot（原生，零自研插件）
┌──────────────────────────────────┐   反向 WS 主动外连   ┌──────────────────────────────┐
│ 唤醒 → 录音 → C 端本地 ASR        │ ──/ws/──▶          │ aiocqhttp (OneBot V11) 适配器 │
│   ↓ 识别文本 → 私聊事件上报        │   universal 单连接  │   ↓ 原生 LLM / 工具 / 会话记忆 │
│ OneBotClient (OkHttp WS)          │ ◀───────────────── │ send_private_msg API 调用    │
│   ↓ 回复 → 悬浮窗/流体云/未来TTS    │                    │                              │
└──────────────────────────────────┘                    └──────────────────────────────┘
```

### P0 技术验证结果（2026-08-14，全部通过）

| 项 | 结果 |
|---|---|
| OneBot 反向 WS 端点/鉴权 | ✅ `aiocqhttp 1.4.4` 支持 `/ws`、`/ws/event`、`/ws/api`；握手 Headers `X-Client-Role` + `X-Self-ID` + `Authorization: Bearer`；**universal 单连接已端到端实测通过**（连接→事件上报→`send_private_msg` 下发→结果回传） |
| AstrBot 私聊直答 | ✅ `friend_message_needs_wake_prefix=False`（默认私聊直答）；ID 白名单默认关闭 |
| ASR 选型 | ✅ `sherpa-onnx 1.13.5` + `paraformer-zh-small`（74MB）：中文识别精准（测试集中文/中英混合），5.6s 音频推理约 23ms（PC CPU），实时率远小于 1 |
| Android 集成 | ✅ AAR（`sherpa-onnx-1.13.5.aar`）在 AGP 8.7.3 / compileSdk 37 / JDK 21 下 `assembleDebug` 编译通过；已加 `abiFilters`（arm64-v8a + armeabi-v7a）裁剪 APK |

### 实施阶段（P1 起）

| 阶段 | 内容 | 状态 |
|---|---|---|
| **P0** | 技术验证（WS 端点/私聊直答/ASR 选型/Android 集成） | ✅ 全部通过 |
| P1 | OneBot 客户端：`OneBotClient.kt`（universal 单连接 + 事件上报 + API 分发 + 重连）+ `OneBotFrame.kt` + ConnectionService/Prefs/配置 UI | ⬜ |
| P2 | 本地 ASR：`audio/OnnxAsr.kt`（sherpa-onnx），`VoiceController` 链路改造（录音→ASR→上报→收回复） | ⬜ |
| P3 | 交互收尾：长回复全文 / 错误提示 / M4 验收 | ⬜ |
| P4 | TTS 播报（OneBot record 段 or 系统 TTS，可选） | ⬜ |

**规划模块变更**：`protocol/FairyVoiceClient.kt`（自研协议）→ 冻结保留作回退，新增 `protocol/OneBotClient.kt`；
`audio/VoiceController.askWithVoice` → 本地 ASR → OneBot 上报；新增 `audio/OnnxAsr.kt`；B 端插件冻结不删。

## 构建

```bash
# 需要 JDK 17~22（本机用 jbr-21.0.11）与 Android SDK（compileSdk 37）
set "JAVA_HOME=C:\Users\13370\.jdks\jbr-21.0.11"
gradlew.bat assembleDebug          # 产物 app/build/outputs/apk/debug/app-debug.apk
gradlew.bat testDebugUnitTest      # 协议 + WS 客户端单测（本地 MockWebServer 模拟 B 端）
```

## 使用（迁移完成前的老链路，仍可用）

> P1 完成后将改为填 AstrBot 的 OneBot 连接参数（host/port/token + 固定 user_id），以下为当前说明。

1. 安装 APK，填写 B 端地址（`ws://<host>:<port>/ws`）、token、设备 ID。
2. 点「启动连接」，通知栏出现常驻通知即连接成功。
3. 在系统设置开启「Fairy Voice」无障碍服务（实体键唤醒依赖）。
4. 同时按住 **音量上 + 音量下 0.5 秒** 唤醒（M3 唤醒 = 拉起主界面；M4 改为直接开始录音）。
5. M3 联调：在输入框输入指令文本，点「发送 ask」，查看 AI 回复。

## 实体键唤醒的边界（重要）

- **电源键（息屏键）事件被 Android 系统独占**，任何第三方 APP（含前台）都无法监听，因此
  「电源键 + 音量上」无法由 APP 内实现（无 root）。
- 本 APP 使用**无障碍服务**监听音量键组合，亮屏时任意界面可用；不拦截按键（音量调节正常）。
- 息屏状态下第三方 APP 收不到任何按键事件，仅耳机媒体键可唤醒（M4 预留）。
- 部分 ROM 支持把「电源键 + 音量上」映射为启动应用，可近似实现你的需求，步骤见 `docs/WAKEUP.md`。

## 许可

AGPL-3.0-only（与 B 端仓库一致）

---

# 老方案（历史归档：自研 B 端插件 fairy-voice）

> **以下为 2026-08-14 前基于自研 B 端插件 `fairy-voice`（GitHub 已归档）的实现，已被上方「现行方案」取代，仅供历史参考。**

## 架构（旧）

```
手机（本 APP，Kotlin）                         B 端（AstrBot 插件，Python）
┌────────────────────────────┐                ┌──────────────────────────┐
│ 无障碍服务监听实体键组合     │                │ aiohttp WS 服务端         │
│   ↓ 触发唤醒               │   WS 主动外连    │   ↓ hello/token 握手      │
│ 前台服务 ConnectionService │ ─────────────▶ │   ↓ ask 请求              │
│   ↓ sendAsk(文本)          │    JSON 帧      │ llm_generate / 记忆策略    │
│   ↓ 收到 response          │ ◀───────────── │   ↓ AI 回复回传           │
│ (M4: TTS 播报)             │                │                          │
└────────────────────────────┘                └──────────────────────────┘
```

## 模块（旧）

| 模块 | 说明 |
|---|---|
| `protocol/Protocol.kt` | 协议帧模型（hello/ask/response/hello_ack），纯 Kotlin 可单测 |
| `protocol/FairyVoiceClient.kt` | WS 客户端：握手/心跳/ask/指数退避重连，纯 Kotlin + OkHttp |
| `service/ConnectionService.kt` | 前台常驻服务，持有客户端 |
| `wake/WakeKeyService.kt` | 无障碍服务，实体键组合唤醒 |
| `MainActivity.kt` | 配置 + 状态 + M3 联调入口（手动输入指令触发 ask） |
