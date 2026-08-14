# 新方案实施计划：C 端 OneBot V11 直连 + 本地 ASR（替代 B 端插件）

> 状态：**P0、P1 已完成（2026-08-14）**，P2（本地 ASR）待开工
> 核心思路：**砍掉自研 B 端插件（fairy-voice）**，C 端改造为最小 OneBot 11「反向 WebSocket」客户端，
> 直接把 ASR 后的文本以私聊消息上报给 AstrBot 的 aiocqhttp（OneBot V11）适配器，走 AstrBot 原生 LLM/工具/会话链路；
> 同时把 ASR 从 B 端（faster-whisper）移植到 C 端（sherpa-onnx 本地识别），实现全链路离线、数据不出手机。

---

## 1. 结论与动机

- **技术可行性已确认**（2026-08-14 查证 `D:\project\AstrBot` 源码）：
  1. AstrBot 内置 `aiocqhttp`（OneBot V11）适配器，`use_ws_reverse=True`，监听 `ws_reverse_host:ws_reverse_port`（默认 `0.0.0.0:6199`），等待 OneBot 实现端**主动外连**——与现有 C 端穿透 NAT 方式一致。
  2. 私聊默认直答：`friend_message_needs_wake_prefix=False`，上报 private 消息事件即触发 LLM，无需唤醒前缀。
  3. 回复经 `send_private_msg`（OneBot API 调用）从 `/ws/api/` 下发，C 端须实现该 API 并回包。
- **收益**：自研后端归零；白嫖 AstrBot 多 provider / 工具市场 / 会话管理 / dashboard；ASR 本地化（隐私 + 离线）；协议标准有社区保底。
- **成本**：ASR 重做（faster-whisper 无 Android 支持，换 sherpa-onnx）+ 补 OneBot 反向 WS 客户端。估 2~4 周。

---

## 2. 目标架构

```
手机（C 端 = 最小 OneBot 客户端 + 本地 ASR）           AstrBot（原生，零自研插件）
┌──────────────────────────────────┐   反向 WS 主动外连   ┌──────────────────────────────┐
│ 唤醒(音量键/磁贴/通知栏) → 录音    │ ──/ws/event/──▶    │ aiocqhttp (OneBot V11) 适配器 │
│   ↓ C 端本地 ASR (sherpa-onnx)    │   private 消息事件   │   ↓ 原生 LLM / 工具 / 会话记忆 │
│   ↓ 识别文本                       │                    │   ↓ 回复                       │
│ OneBotClient (OkHttp WS)          │ ◀─/ws/api/─────    │ send_private_msg API 调用    │
│   ↓ 回复 → 悬浮窗/流体云/未来TTS    │                    │                              │
└──────────────────────────────────┘                    └──────────────────────────────┘
```

---

## 3. OneBot 11 反向 WS 协议规格（C 端实现依据，P0 已实测确认）

> 依据：OneBot 11 反向 WS 标准 + aiocqhttp 1.4.4（AstrBot `requirements.txt: aiocqhttp>=1.4.4`）。
> **P0 实测确认（2026-08-14）**：aiocqhttp 源码 + 端到端脚本验证，以下规格已确凿。

### 3.1 连接：推荐 universal 单连接

| 端点 | 说明 |
|---|---|
| `ws://<astrbot-host>:<port>/ws` | **universal 单连接（推荐）**：一条 WS 既上报事件又收 API 调用 |
| `ws://<astrbot-host>:<port>/ws/event` | 事件专用通道 |
| `ws://<astrbot-host>:<port>/ws/api` | API 专用通道 |

尾斜杠可选（`strict_slashes=False`）。

**握手 Headers（P0 实测确认）**：

| Header | 值 | 说明 |
|---|---|---|
| `X-Client-Role` | `universal` / `event` / `api` | 通道角色，必须 |
| `X-Self-ID` | 固定纯数字（如 `10086`） | 适配器按此路由 API 调用，必须 |
| `Authorization` | `Bearer <token>` | 仅配置 `ws_reverse_token` 时需要，空则不校验 |

**P0 端到端实测结果**：最小 C 端连 `ws://127.0.0.1:6199/ws`（`X-Client-Role: universal` + `X-Self-ID: 10086`）→
上报 private 事件 → aiocqhttp 触发 `on_message('private')` → `send_private_msg` API 调用下发给 C 端（`echo={'seq':1}`）→
C 端回 `{status:'ok', retcode:0, data, echo}` 成功。**全链路通。**

### 3.1b 备用：两条连接（如后续需要分通道）

| 连接 | 端点 | 用途 |
|---|---|---|
| 事件通道 | `/ws/event` | 上报事件 |
| API 通道 | `/ws/api` | 收 API 请求并回包 |

### 3.2 事件上报帧（C → AstrBot）

```json
{
  "post_type": "message",
  "message_type": "private",
  "time": 1690000000,
  "self_id": 10086,
  "message_id": 1000000001,
  "user_id": 10001,
  "message": [{"type": "text", "data": {"text": "今天天气怎么样"}}],
  "raw_message": "今天天气怎么样",
  "sender": {"user_id": 10001, "nickname": "FairyVoice"}
}
```

- **必须 array 格式**（适配器要求 `post-format=array`，字符串格式会被拒绝）。
- `user_id` / `self_id` 必须**纯数字**（适配器 `_dispatch_send` 依赖 `session_id.isdigit()`）。

### 3.3 API 调用帧（AstrBot → C，C 需回包）

请求（**实测** `echo` 为对象 `{"seq": N}`）：
```json
{
  "action": "send_private_msg",
  "params": {"user_id": 10001, "message": [{"type": "text", "data": {"text": "AI 回复内容"}}]},
  "echo": {"seq": 1}
}
```

响应（`echo` 原样回传）：
```json
{
  "status": "ok",
  "retcode": 0,
  "data": {"message_id": 1000000002},
  "echo": {"seq": 1}
}
```

### 3.4 C 端需实现的 API 最小集

| action | 触发条件 | C 端行为 |
|---|---|---|
| `send_private_msg` | AstrBot 回复（核心） | 显示/播报回复，关联 pending 指令 |
| `send_msg` | 兜底发送 | 同上 |
| `get_stranger_info` | 仅消息含 at 段时 | 返回最小 stub `{user_id, nickname}`（我方不发自触发不了） |
| `get_msg` | 仅消息含 reply 段时 | 返回最小 stub（同上） |
| `send_private_forward_msg` / `send_group_*` | 合并转发/群场景 | 不实现，返回 `{status:"failed", retcode:100}` |

### 3.5 指令↔回复关联

OneBot 是异步消息模型。单用户场景下用**简化关联**：
- C 端固定 `user_id=10001`（可配）；上报事件后维护一个 pending 指令（含发送时间）。
- 收到 `send_private_msg` 且 `params.user_id == 固定 user_id` → 视为该指令的回复 → 悬浮窗/流体云展示 → 清 pending。
- 超时（默认 60s，可配）未收到 → 报「B 端响应超时」。
- 说明：比原协议 `id` 同步关联弱，但单用户 + 串行唤醒下足够；多指令并发场景暂不支持（唤醒即串行，天然满足）。

---

## 4. ASR 本地化落地

**faster-whisper 无法移植**（CTranslate2 Python 绑定，无 Android）。选 sherpa-onnx：

| 项 | 选型（P0 已实测） |
|---|---|
| 框架 | **sherpa-onnx 1.13.5**（k2-fsa，官方 Android AAR，免 NDK 编译） |
| 模型 | **paraformer-zh-small**（int8，74MB，中文专用）——P0 实测识别精准，5.6s 音频推理约 23ms（PC CPU） |
| 备选 | paraformer-zh int8 2025-10-07（217MB，质量更高）/ sherpa-whisper / whisper.cpp / 云端 API |
| 加载 | 懒加载：首次唤醒加载进内存；模型放 `assets/` 或首次启动下载到 `filesDir/models/` |
| 线程 | 识别跑后台线程（sherpa-onnx 离线推理，实时率远小于 1，延迟非瓶颈） |
| 副作用 | APK +74MB（small）；识别瞬时内存峰值超常驻线（<150MB 验收按「空闲态」口径） |

**识别流程（VoiceController 改造）**：
`录音完成 → 读 WAV → OnnxAsr.recognize(wav) → 得到文本 → 校验非空 → 上报事件`。
识别失败/文本为空 → 悬浮窗提示「未识别到语音」，不进 LLM（对齐 M4-1.3 无效唤醒语义）。

---

## 5. 实施阶段（估 2~4 周）

### P0 技术验证（硬门槛，**2026-08-14 全部通过**）
- [x] 实测 aiocqhttp 反向 WS 端点与帧格式——**端到端脚本验证通过**（universal 单连接 + `X-Client-Role`/`X-Self-ID` 握手；事件上报→`send_private_msg` 下发→结果回传全通）
- [x] 确认私聊直答（`friend_message_needs_wake_prefix=False`）+ 无 ID 白名单拦截（`enable_id_white_list` 默认关）
- [x] sherpa-onnx AAR（1.13.5）在 AGP 8.7.3 / compileSdk 37 / JDK 21 下 `assembleDebug` **编译通过**（19s）；已加 `abiFilters`（arm64-v8a + armeabi-v7a）把 APK 从 128MB 裁剪到 ~51MB
- [x] paraformer-zh-small 中文识别验证——**质量好**（测试集中文/中英混合精准）、推理 23ms/5.6s 音频、模型 74MB
- 出口：全绿，可进入 P1

### P1 OneBot 客户端（**已完成 2026-08-14**）
- [x] `protocol/OneBotClient.kt`：**universal 单连接**（复用 OkHttp）、token 鉴权、指数退避重连、事件上报、API 请求分发/响应、pending 指令关联与超时
- [x] `protocol/OneBotFrame.kt`：事件/API 帧构造与解析，纯 JVM 单测
- [x] `ConnectionService` 挂接 OneBotClient；`Prefs` 增加 astrbot url/token、self_id/user_id 配置
- [x] 配置 UI：主界面配置区改为 AstrBot OneBot 连接参数（地址/token/self_id/user_id）
- [x] 单测：帧构造/解析、API 分发、pending 关联/超时、断线重连（MockWebServer）——`OneBotFrameTest` 9 例 + `OneBotClientTest` 7 例全绿，全量 39 例通过

### P2 本地 ASR（3~5 天）
- [ ] sherpa-onnx AAR 集成、模型打包/懒加载
- [ ] `audio/OnnxAsr.kt` 封装（识别后台线程、状态回调、错误码：model_loading / asr_failed / no_speech）
- [ ] `VoiceController.askWithVoice` 改造：录音 → 本地 ASR → 文本 → OneBotClient 上报 → 收回复
- [ ] 悬浮窗/流体云展示识别文本 + 回复（复用 M4-1.3 三形态）

### P3 交互收尾 + 验收（2~3 天）
- [ ] 长回复完整可读（流体云截断 + 悬浮卡片滚动全文 + 复制）——继承 M4-2.1 C 端任务
- [ ] 错误提示完善（模型加载中 / 未连接 / 识别失败 / 响应超时）
- [ ] M4 验收标准逐条核对（见 §7）

### P4 TTS 播报（可选，2~3 天，原 M4-3）
- [ ] 方案 A：AstrBot 回传 `record` 段（CQ 码 base64）→ C 端 MediaPlayer 播放
- [ ] 方案 B：C 端系统 TTS 播报回复文本（最简单）

---

## 6. 代码改造映射

| 现有文件 | 处置 | 新文件/改动 |
|---|---|---|
| `protocol/FairyVoiceClient.kt` | 冻结（保留作回退） | `protocol/OneBotClient.kt`（反向 WS×2 + 重连 + API 分发） |
| `protocol/Protocol.kt` | 冻结帧构造 | `protocol/OneBotFrame.kt`（事件/API 帧，纯 JVM 可测） |
| `audio/VoiceController.kt` | 改 `askWithVoice` | 本地 ASR → OneBot 上报 |
| — | 新增 | `audio/OnnxAsr.kt`（sherpa-onnx 封装） |
| `service/ConnectionService.kt` | 改持有 OneBotClient | 常驻/通知栏/唤醒按钮不变 |
| `util/Prefs.kt` | 增配置 | host/port/token、user_id/self_id、响应超时 |
| `MainActivity.kt` | 改配置区 | AstrBot 连接参数 + ASR 状态展示 |
| 悬浮窗/唤醒/磁贴 | **不动** | — |
| B 端插件 `D:\project\fairy-voice` | **冻结不删**（回退通道） | — |

---

## 7. M4 验收标准（对齐 DEV_PLAN）

- [ ] 按热键 → 3s 内开始录音
- [ ] 识别文本 3s 内返回（本地 ASR 应满足；paraformer-zh 快于网络往返）
- [ ] AI 回复从发送到展示/TTS < 10s（视模型速度 + 本地 ASR 已省一段网络）
- [ ] WS 断线 15s 内自动重连（反向 WS 复用现有退避骨架）
- [ ] 连续 10 次语音问答无超时/断开
- [ ] 100+ 字回复流体云不丢内容、点开可看全文
- [ ] 空闲态内存 < 150MB / CPU < 1%（ASR 瞬时峰值另计）

---

## 8. 联调清单

1. AstrBot：启用 `aiocqhttp` 平台适配器，填 `ws_reverse_host=0.0.0.0 / port / token`；确认 provider 可用、`friend_message_needs_wake_prefix=False`、无 ID 白名单拦截
2. C 端连 `/ws/event/`，上报一条测试文本 → AstrBot dashboard 日志确认收到并触发 LLM
3. AstrBot 回复 → C 端 `/ws/api/` 收到 `send_private_msg` → 悬浮窗显示 → 清 pending
4. 全链路：唤醒 → 录音 → 本地 ASR → 文本上报 → LLM → 回复回显
5. 断网/杀进程恢复：重连后会话延续（AstrBot 会话记忆）

---

## 9. 风险与回退

| 风险 | 应对 |
|---|---|
| aiocqhttp 端点路径差异 | P0 实测；备选 `/ws/` 混合通道；实在不行 AstrBot 端加日志定位路由 |
| sherpa-onnx AAR 与 AGP 8.7 / compileSdk 37 不兼容 | P0 验证；不行降 compileSdk 36 或换 whisper.cpp |
| 中文识别质量 < faster-whisper | 实机对比；换 paraformer-large / SenseVoice；极端情况切云端 ASR |
| AstrBot 私聊被平台/白名单拦截 | P0 验证配置；必要时给消息加唤醒前缀 |
| OneBot 异步关联弱（并发指令串扰） | 唤醒即串行，天然避免；超时窗口兜底 |
| **整体回退** | B 端插件 + `FairyVoiceClient` 冻结保留；切回原协议仅需换配置与 `VoiceController` 一处分发 |
