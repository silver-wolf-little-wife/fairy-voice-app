# 部署说明（DEPLOY）

## 构建 APK

环境要求：JDK 17+、Android SDK（compileSdk 36）。

```bash
# 生成 debug 包
gradle assembleDebug
# 产物
app/build/outputs/apk/debug/app-debug.apk
```

## 安装

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 连接 B 端

1. B 端插件 `fairy-voice` 已配置 `ws_port` / `auth_token`。
2. APP 内填写 `ws://<B端地址>:<端口>/ws`（局域网 IP 或 frp 公网域名，生产建议 wss）。
3. 启动连接，观察通知栏状态。

## 验证清单（对应 DEV_PLAN 验收标准）

- [ ] WS 断线 15 秒内自动重连（指数退避 1s→60s），重连后状态恢复
- [ ] 音量上+音量下 0.5s 唤醒（亮屏任意界面）
- [ ] ask → response 链路（单测覆盖 5 用例 + 真机输入框联调）
- [ ] 握手 token 错误 → `onAuthError` 提示，不崩溃
- [ ] 常驻内存占用（M3 骨架 < 100MB，M4 录音后观察）

## M4 预留

- `RECORD_AUDIO` 权限已声明；`WakeKeyService.triggerWake()` 是唤醒入口，
  M4 在此处启动录音 → faster-whisper 类识别 → `sendAsk` → TTS 播报。
- 状态机（空闲/录音中/识别中/等待AI/播报中）建议挂在 `ConnectionService`。
