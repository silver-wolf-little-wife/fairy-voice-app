# C 端流式改造计划（协议 v2.0，无 TTS）

> 状态：2026-08-17 定稿。与 B 端 `fairy-voice/docs/DEV_PLAN.md` 配套。
> 背景：OneBot 直连方案（PLAN_ONEBOT_MIGRATION.md，P0~P4 已完成）因无法 token 级流式，
> 切回自研 `FairyVoiceClient` 协议并升级 v2.0；砍 TTS，纯文字流式输出。

## 1. 架构变化

```
旧（OneBot 直连，冻结为回退）:  录音 → 本地ASR → OneBot 事件上报 → send_private_msg 单帧回复 → TTS
新（v2.0 流式）:                录音 → 本地ASR → FairyVoiceClient ask → stream_begin/delta/end 增量上屏
```

- `OneBotClient` / `OneBotFrame`：冻结不删，作回退；`ConnectionService` 改连 fairy-voice WS
- `FairyVoiceClient`：从 v1.1.0 升级流式（v1.1.0 的 ask/voice_ask/response 帧模型保留）
- `VoiceController`：删除 SPEAKING/TTS 分支，状态 = IDLE/RECORDING/RECOGNIZING/WAITING_AI

## 2. 改造点

| 文件 | 改动 |
|---|---|
| `protocol/FairyVoiceClient.kt` | 流式回调 onStreamBegin/onStreamDelta/onStreamEnd；按 id 聚合；断流兜底 |
| `protocol/Protocol.kt` | 新增 StreamBegin/StreamDelta/StreamEnd 帧模型与解析（纯 JVM 可测） |
| `ChatMessage.kt` / `ChatHistory` | 流式消息：同 id 增量追加，结束置为 finalized |
| `ChatFragment.kt` / `ChatAdapter` | 增量刷新，打字机效果 |
| `audio/VoiceController.kt` | 砍 TTS（MediaPlayer/onTts/SPEAKING），简化状态机 |
| `service/ConnectionService.kt` | 连接参数切回 fairy-voice（url/token/device_id） |
| `util/Prefs.kt` / `SettingsFragment.kt` | 配置项对齐 fairy-voice 协议 |
| `OneBotClient.kt` / `OneBotFrame.kt` | 冻结，仅保留编译与单测 |

## 3. 里程碑（与 B 端 S2~S4 对齐）

- S2（第 1~2 周）：FairyVoiceClient 流式 + 帧模型 + 单测；连接切回
- S3（第 2 周）：ChatHistory/ChatAdapter 增量渲染；悬浮卡增量更新
- S4（第 2~3 周）：状态机简化收尾；全量回归 + Release 打包

## 4. 验收标准

- 500 字回复首 token 上屏 < 2s，全程增量无闪跳
- 流式中断 3s 内收尾，已收内容完整可读
- 唤醒→录音→本地 ASR→流式上屏全链路通；断线重连回归
- 无 TTS 残留（无 SPEAKING 状态、无 record 解析调用）

## 5. 提交约定

- 本仓库 remote: `silver-wolf-little-wife/fairy-voice-app`，main 分支，随里程碑提交
- 协议变更必须与 B 端 `fairy-voice/docs/PROTOCOL.md` 同步
