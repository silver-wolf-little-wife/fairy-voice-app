# 部署说明（DEPLOY）

## 构建 APK

环境要求：JDK 17~22、Android SDK（compileSdk 36）。

> ⚠️ **JDK 注意**：本机 PATH 无 java。Android Studio / PyCharm 自带 JBR 是 Java 25，
> Gradle 8.9 不支持（直接报版本号错误）。构建前必须指定 JDK 21：
> `set "JAVA_HOME=C:\Users\13370\.jdks\jbr-21.0.11"`

```bash
cd /d D:\project\fairy-voice-android
set "JAVA_HOME=C:\Users\13370\.jdks\jbr-21.0.11"
gradlew.bat testDebugUnitTest   # 18 用例
gradlew.bat assembleDebug       # 生成 debug 包
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
- [ ] M4-1 录音：按唤醒键/录音按钮 → 状态「录音中…」→ 再按一次停止 → 生成 WAV
- [ ] M4-1 录音产物可播放（16kHz/16bit/单声道），15 秒自动停止
- [ ] 无 RECORD_AUDIO 权限时弹权限框，拒绝后 Toast 提示不崩溃

## M4 状态

- **M4-1 录音已完成（2026-08-13）**：`audio/` 包（WavHeader/AudioRecorder/VoiceController），
  唤醒/按钮触发录音，RECORD_AUDIO 运行时权限，录音存 `filesDir/recordings/`。
  真机冒烟：点「开始录音（测试）」→ 说话 → 停止 → `adb pull` 验听。
- M4-2 起：ASR 识别（VoiceController 扩展 RECOGNIZING 流转）→ TTS 播报 →
  完整链路 + 状态机 UI/通知栏展示（状态枚举已就位）。
