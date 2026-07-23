# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

打卡喵（DaKaMiao）是一个安卓工具，通过**无障碍服务**自动打开钉钉（`com.alibaba.android.rimet`）并完成每日打卡。设计原则见 `README.md` 与用户记忆 `dakamiao-scope.md`：

- **不写死坐标**——按界面上的**文字**找按钮并点击，换机型/换钉钉版本都不怕；文字读不到（图片按钮）时用离线 OCR 兜底。
- **不伪造定位、不伪造人脸**——遇到人脸/活体识别界面立即停止，交还本人。
- **四重中断**——任何时候都能叫停：悬浮红色「停止」按钮、通知栏「停止」、音量下键急停、每步之间检查停止标志。

## 常用命令

构建 Debug APK（唯一常用构建目标，产物 `app/build/outputs/apk/debug/app-debug.apk`）：
```bash
./gradlew :app:assembleDebug
```

运行单元测试 / 单个测试：
```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:testDebugUnitTest --tests "com.haitang000.dakamiao.ExampleUnitTest"
```

Lint：
```bash
./gradlew :app:lintDebug
```

工具链：Gradle 9.5、AGP 9.3（内置 Kotlin，无需单独 apply kotlin-android）、编译需 JDK 21，编译目标 Java 11 / JVM 11。`compileSdk=36`、`minSdk=24`、`targetSdk=36`。CI（`.github/workflows/android.yml`）在 push/PR 到 `main` 时跑 `assembleDebug`；打 `v*` tag 会创建 Release 并附上 APK。

## 架构

单进程、单模块（`:app`）应用，无 UI 框架依赖，纯 View + ViewBinding。数据流围绕一个**核心无障碍服务单例**展开。

### 触发路径（两条入口，最终都进核心服务）

1. **定时**：`AlarmScheduler`（`AlarmManager` 精确一次性闹钟）→ `ClockAlarmReceiver` 触发。Receiver 的关键约定：**先重排明天的同一闹钟再干别的**，保证「每日循环」即使本次异常也不断（闹钟是一次性的，靠自我重排续期）。`BootReceiver` 在开机/更新后重排；工作日模式下周末在 Receiver 里跳过。
2. **手动**：`MainActivity` 直接调用服务的 `startClockIn`。

两条路径都通过 `AutoClockAccessibilityService.instance`（`@Volatile` 单例，为 null 即表示无障碍未开启）触达核心服务。定时到点时走 `handleScheduledTrigger`：已解锁 → 5 秒倒计时确认框 → 打卡；锁屏/息屏 → 点亮屏幕 + 全屏强提醒，挂起本次，监听 `ACTION_USER_PRESENT`，等用户解锁后自动继续（安全锁无法被 App 自动解锁，这是安卓限制）。

### 核心服务 `AutoClockAccessibilityService.kt`

整个应用的重心，约 1300 行，其余文件都是它的支撑。打卡流程在**后台线程**跑（`runSequence`），全程用 `sleepChecked()` 分片睡眠并反复查停止标志：

1. 可选先 `killBackgroundProcesses` 退出钉钉后台，冷启动到干净状态；
2. 启动钉钉，等待渲染；
3. 按用户配置的**步骤列表**逐个 `findAndClick`；
4. 点完最后一步 `detectResult` 轮询判定结果。

**节点查找**不依赖 `onAccessibilityEvent` 事件流，而是主动轮询 `rootInActiveWindow` 并 BFS 遍历树。`findNodeByText` 用**打分**选最优候选（完全相等 > 可点击 > 已启用 > 多余文字少 > 越靠屏幕上方）。点击优先 `ACTION_CLICK` 到最近可点击祖先，自绘按钮兜底 `dispatchGesture` 手势点击。

**结果判定优先级**（`detectResult`）：拦截/失败关键词 > 人脸关键词 > 成功关键词。成功需**持续存在数秒**才认定，避免残留的历史「打卡成功」抢在拦截框弹出前误判；页面还在「定位中/加载中」会续期等待。

### 步骤 DSL（存在 SharedPreferences 里的纯文本，一行一步）

用户在设置界面把步骤改成自己钉钉的实际按钮文案。`Prefs.kt` 是所有配置读写的**唯一入口**，用 `parseLines` 按行/逗号拆分。步骤行的特殊前缀（解析散落在 `AutoClockAccessibilityService` 的 `isScrollTopDirective` / `isBackHomeDirective` / `findNodeByText`）：

- `@回到主页` / `@顶部` 等 **`@` 开头 = 动作指令**（返回键回主界面 / 滚动到顶部），不是找按钮；
- **`=` 开头 = 精确匹配**（只点文字完全相等的节点），用于「打卡」这类在消息列表里也常出现的短词，避免误点进会话；
- 其余为**包含匹配**。

默认路径：`@回到主页 → =工作台 → =考勤打卡 → 上/下班打卡`。除步骤外，`Prefs` 还管理成功/人脸/需确认（如外勤，会阻塞线程弹确认框）/失败拦截四组关键词，均可用户覆盖。

### OCR 兜底 `ScreenTextLocator.kt`

仅当无障碍常规查找失败、且按钮可能是纯图片时启动。`AccessibilityService.takeScreenshot`（**安卓 11 / API 30+** 才有，静默无快门声）→ ML Kit 中文识别器（模型打包进 APK，**离线不联网**）→ 返回文字行的屏幕物理像素矩形，正好可直接喂给 `dispatchGesture`。限频约 1.3 秒一次；精确匹配步骤跳过 OCR。

### 其余支撑文件

- `BorderMarqueeView.kt`——屏幕四边流光边框跑马灯，蓝=正常操作、红=受阻。悬浮窗铺满全屏但 `FLAG_NOT_TOUCHABLE`，触摸全部穿透，绝不挡住钉钉或自动点击。
- `SoundFx.kt`——受阻/失败时的柔和合成提示音。
- `KeepAliveService.kt`——`specialUse` 前台服务保活，提升定时可靠性；进程被杀后闹钟唤醒时会重新拉起。
- `OnboardingActivity.kt`——首次全屏权限引导。`StopReceiver.kt`——通知栏「停止」入口。`DaKaMiaoApp.kt`——Application。

## 关键约束与坑

- **定时那一刻手机需可交互**：屏幕全黑或安全锁屏时钉钉界面渲染不出来，点击会失败——这是无障碍 + 安卓的硬限制，不是 bug。锁屏场景只能挂起等用户解锁。
- **权限依赖多**：无障碍（核心）、悬浮窗（停止按钮/边框/各类弹窗，无则退化为 Toast）、精确闹钟、通知、忽略电池优化。改动涉及这些能力时注意各分支的降级路径。
- 悬浮弹窗/确认框用 `TYPE_APPLICATION_OVERLAY`（O+）覆盖在钉钉之上，需悬浮窗权限；无权限时确认类操作**保守当作取消**。
- 调试适配新钉钉界面：服务里有 `scheduleDump` / `buildNodeDump`，可导出当前前台窗口的节点树（类名/文字/描述/标志位/坐标），用于对照配置步骤文案。
- 代码注释与用户可见文案均为中文，且 README 面向普通用户——改动行为时同步更新 README 对应说明。
