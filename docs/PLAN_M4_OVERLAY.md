# M4-1.2 计划：悬浮窗 / 流体云交互改造

> 状态：**计划待确认**（2026-08-13 拟定，等用户确认 ColorOS 版本与首版范围后开工）
> 目标：磁贴/通知栏/音量键唤醒后**不再拉起全屏 App**，直接开始录音，通过**悬浮胶囊 + 展开卡片**呈现状态与 AI 回复（小布/Siri/小艺风格）；优先接入 ColorOS 流体云，接不进则悬浮窗兜底。

---

## 1. 现状与差距

- 现状（M4-1.1）：唤醒 → `WakeTrigger` → `startActivity(MainActivity 全屏)` → handleWake 录音；录音结果只回显在 App 内 tvReply。
- 差距：
  1. 唤醒会打断当前使用（全屏跳 App），不符合语音助手直觉；
  2. 录音/识别/AI 回复没有悬浮层呈现；
  3. 后台直接录音的能力其实已具备（AudioRecorder 只需 Context，不依赖 Activity），只是唤醒链路没走服务。

## 2. 流体云接入调研结论（2026-08-13 查证）

| 路径 | 条件 | 成本 | 结论 |
|---|---|---|---|
| **L1：Android 16 Live Updates（ColorOS 16 原生兼容）** | 设备 ColorOS 16+；targetSdk/compileSdk 36；用 API 36 通知实时活动能力 | 低，纯原生 API，无审批 | **优先路线** |
| **L0：悬浮窗（TYPE_APPLICATION_OVERLAY）** | `SYSTEM_ALERT_WINDOW` 权限（设置页引导），全 Android 通用 | 低 | **兜底 / 首版落地** |
| L2：流体云卡片 SDK（Pantanal/Seedling，ColorOS 14+ API2.0） | OPPO 开发者认证 + 应用创建 + **卡片服务权限授权码需联系商务审批** + 独立 UPK 工程（oml/JS 模板） | 高（审批不确定、独立工程、宿主 APK 申请） | 远期评估，不做主路径 |

要点：
- OPPO 官方确认 ColorOS 16 完整接入原生 Android 16 Live Updates API，**遵循谷歌实时活动规范的应用可直接适配流体云**（状态栏胶囊 + 展开卡片，即用户要的形态），且在其他非 ColorOS 设备上自动降级为普通通知——与悬浮窗天然互补。
- L2 的授权码申请明确要求「联系商务协调对接人审批」，对个人项目是硬门槛，且需要 Pantanal DevStudio 独立工程，改造量大，仅当 L1 不可用且用户坚持时再评估。

## 3. 目标架构

```
触发（磁贴 / 通知栏 / 音量键）
  ├─ 权限检查：RECORD_AUDIO（首次走 MainActivity 授权，授权后记住）
  │            SYSTEM_ALERT_WINDOW（首次跳设置页引导）
  └─ FairyOverlayService（前台服务，常驻）
       ├─ VoiceController 状态机（录音 → 识别 → ask → 回复 → 播报）
       ├─ 悬浮胶囊（状态文案：录音中… / 识别中… / 等待 Fairy… / 播报中…）
       └─ 展开卡片（AI 回复文本；操作：收起 / 复制；M4-3 起加「播报」）
  └─（可选 L1）Live Updates 通知：ColorOS 16 上以流体云胶囊形态出现，
                点击展开系统流体云卡片显示回复；其他设备降级为普通通知
```

## 4. 改造点清单（按文件）

1. **`service/FairyOverlayService.kt`（新）**：前台服务；WindowManager 添加 `TYPE_APPLICATION_OVERLAY` 悬浮窗；胶囊 View + 卡片 View；`VoiceController.Listener` 驱动 UI（onStateChanged / onRecorded / onReply 回调）；交互：点胶囊=展开/收起卡片，点卡片=收起/复制文本。
2. **`wake/WakeTrigger.kt`**：trigger 改为「检查权限 → 启动 FairyOverlayService + `VoiceController.startRecording`」；无 RECORD_AUDIO → 拉起 MainActivity（仅首次授权）；无悬浮窗权限 → 跳 `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`（仅首次，记 Prefs）。
3. **`wake/FairyVoiceTileService.kt`**：onClick → 走新 trigger（不再 `startActivityAndCollapse` 全屏）；磁贴副标题随录音状态变化（「点击唤醒」/「录音中…」）。
4. **`service/ConnectionService.kt`**：通知栏「唤醒」按钮 PendingIntent 改为触发同一 trigger（改为显式启动 OverlayService 的 Intent 或保留 activity 仅作权限兜底）。
5. **`audio/VoiceController.kt`**：状态机加录音完成后的流转钩子（M4-2 ASR 未接入前，先用占位文本走 ask 链路，验证卡片显示回复）；`onReply` 回调透出。
6. **`MainActivity.kt`**：保留连接配置/联调；唤醒引导改为「权限引导」；可加「悬浮窗预览」按钮。
7. **`AndroidManifest.xml`**：注册 FairyOverlayService（前台服务类型按实际用途：dataSync 或 specialUse）；`SYSTEM_ALERT_WINDOW` uses-permission。
8. **资源**：悬浮窗布局（胶囊 + 卡片，深色主题 fairy_bg/fairy_blue，圆角）、strings 文案。
9. **（L1 启用时）**：targetSdk 34 → 36（compileSdk 已 36）；Live Updates 通知实现（以 API 36 文档为准）；**回归**通知权限、前台服务类型等既有行为；真机验证降级路径。
10. **（L2 评估时）**：Pantanal DevStudio + UPK 工程 + 授权码申请，另行评估，不阻塞主路径。

## 5. 权限与引导

| 权限 | 获取方式 | 备注 |
|---|---|---|
| RECORD_AUDIO | MainActivity 首次弹框授权 | 已实现；授权后 Prefs 记录，后台路径不再拉起界面 |
| SYSTEM_ALERT_WINDOW | `Settings.canDrawOverlays()` 检测 → `ACTION_MANAGE_OVERLAY_PERMISSION` 引导 | ColorOS 注意「设置→应用→特殊访问权限→悬浮窗」总开关 |
| 电池白名单（建议） | 引导加入 ColorOS 电池优化白名单 | 防后台杀悬浮窗/服务 |

## 6. 里程碑拆分

- **M4-1.2a 悬浮窗骨架**：FairyOverlayService + 悬浮胶囊（录音中/停止交互）+ 后台直接录音（权限已授时）。验收：磁贴点击不弹全屏 App，出现胶囊，再点停止。
- **M4-1.2b 卡片交互**：胶囊点击展开卡片，显示 ask 回复文本（ASR 未接前用占位文本链路），支持收起/复制。
- **M4-1.2c 流体云 L1**：确认设备 ColorOS 16 → targetSdk 36 + Live Updates 接入（含非 16 降级回归）；设备 ≤15 则挂起，等系统升级后补。
- **M4-1.2d（评估）L2**：Pantanal 授权码商务审批可行性，通过再立项。
- 与 M4-2 ASR / M4-3 TTS 衔接：卡片依次显示「识别文本 → AI 回复」；TTS 接入后加「播报中…」状态与播报按钮。

## 7. 验收标准

- 磁贴/通知栏/音量键唤醒 → 不出现全屏 App，悬浮胶囊「录音中…」出现；再触发一次停止
- 录音完成 → 胶囊状态流转（识别中→等待 Fairy→完成）
- AI 回复到达 → 点胶囊展开卡片显示文本；支持收起/复制
- ColorOS 16：流体云胶囊形态出现（L1 启用时）；其他设备：悬浮窗正常，通知降级不崩溃
- 权限首次引导流畅；拒绝任一权限不崩溃、有 Toast 提示
- 既有功能回归：单测全绿、连接/重连/磁贴状态正常

## 8. 风险与对策

| 风险 | 对策 |
|---|---|
| 设备非 ColorOS 16，L1 不可用 | 先 L0 悬浮窗落地，L1 等升级（用户已接受悬浮窗兜底） |
| Live Updates API 细节以 API 36 为准，可能需微调 | 开工时先起最小 Demo 验证胶囊形态再全量接入 |
| targetSdk 36 行为变化（通知/前台服务） | 升级后全量回归清单（单测 + 真机） |
| 悬浮窗被 ColorOS 后台限制/杀进程 | 前台服务 + 电池白名单引导 + START_STICKY |
| L2 商务审批不确定 | 不作为主路径，仅评估 |

## 9. 待确认事项

1. **手机 ColorOS 版本**（决定 L1 是否现在做：ColorOS 16 → 直接做；≤15 → 先 L0）
2. 首版范围：先做 a+b（悬浮窗胶囊+卡片，占位文本链路），L1 并行评估？
3. 悬浮窗样式偏好（胶囊大小/位置/颜色，默认 Fairy 蓝深色主题）
