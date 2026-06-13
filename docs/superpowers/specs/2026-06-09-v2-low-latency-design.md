# V2 低延迟大麦助手设计

## 1. 目标

V2 的目标是在当前 V1 混合状态机和 Shizuku 点击通道已跑通的基础上，降低热路径延迟，并让每次实际运行后的性能数据可分析。

核心目标：

- 阶段一从项目详情页进入票档页时，优先使用 Shizuku 坐标点击，不再依赖 UI tree 识别隐藏按钮。
- 阶段二和阶段三优先走预校准坐标和快速状态监听，节点点击与视觉检测只作为兜底。
- 将每次点击、页面推进、状态识别的耗时记录为可导出的性能事件。
- 保留 V1 的安全边界：用户手动登录、手动进入目标页面、手动开启无障碍，不绕过验证、登录、支付或服务端规则。

## 2. 现状结论

实机验证已经证明：

- 大麦项目详情页中的“立即购票/立即预定”按钮不稳定暴露在 UI tree 中。
- Accessibility `dispatchGesture()` 能回调成功，但大麦可能不接受该输入。
- Shizuku shell 权限下的输入能命中隐藏按钮，阶段一可进入 `NcovSkuActivity`。
- 当前 Shizuku UserService 使用 `/system/bin/input tap x y`，每次 tap 需要启动系统命令进程，仍有不必要延迟。
- 当前状态机较保守，节点识别、视觉 fallback、重试调度可能进入正常热路径。

因此 V2 不应重写整体应用，而应收紧热路径：提前准备、直接注入、快速观测、延迟兜底。

## 3. 运行前置条件

V2 仍需要：

- APK 已侧载安装。
- 用户手动开启无障碍服务。
- 用户手动登录大麦，并进入目标项目详情页。
- 用户完成三个阶段坐标校准。
- 手机保持亮屏、解锁，大麦在触发前可切回前台。
- Shizuku 已运行，并授权 `com.local.damaiassistant`。

Shizuku 启动限制：

- 无 root 情况下，App 不能自己启动 Shizuku server，因为启动 Shizuku 需要 adb shell 或 root 权限。
- 如果 Shizuku 已通过 adb 启动，USB 线可以拔掉，助手 App 后续可脱机运行，直到 Shizuku server 被杀、手机重启或权限状态失效。
- 手机重启后通常必须再次通过 adb 命令启动 Shizuku。
- 如果无线调试启动不可用，则每次重启后仍需要 PC 连接并执行 adb 启动命令。
- 只有 root 方案或可用的 Shizuku 无线调试本机启动方案，才能减少对 PC adb 的依赖。

当前设备的 adb 启动命令为：

```powershell
adb shell /data/app/~~qWmjDTsgLWNWQ3eMcqkA9Q==/moe.shizuku.privileged.api-lHq1NjSROQ-WgiyuWF2aiA==/lib/arm64/libshizuku.so
```

## 4. 架构

V2 保留现有模块边界：

- `MainActivity`：配置、启动、状态展示。
- `DamaiAccessibilityService`：无障碍事件入口、前台包名和窗口状态观察。
- `TicketStateMachine` / runtime coordinator：状态流转。
- `ShizukuShellTapper` / Shizuku UserService：输入通道。
- `VisualGateway`：截图模板匹配兜底。
- `RunLogger`：运行日志。

新增或改造以下组件：

- `FastInputService`：Shizuku UserService 内的低延迟输入服务。
- `FastTapper`：App 侧输入客户端，负责绑定、预热、tap、批量 tap、fallback。
- `HotPathCoordinator`：热路径调度器，避免截图和重日志进入关键阶段。
- `PerformanceTraceLogger`：专门记录纳秒级事件点。
- `RecoveryCoordinator`：处理节点点击、视觉匹配、超时、失败恢复。

## 5. 输入通道设计

### 5.1 快路径输入

V2 优先在 Shizuku UserService 进程内直接注入输入事件，避免每次执行 `/system/bin/input`。

候选实现：

- 在 shell 权限进程内反射调用 `InputManager.injectInputEvent`。
- 构造 `MotionEvent.ACTION_DOWN` 和 `MotionEvent.ACTION_UP`。
- 默认 down/up 间隔 1 到 5ms，可配置。
- 输入服务启动时执行 `warmUp()`，提前解析反射对象，避免首次点击时初始化。

接口草案：

```aidl
interface IFastInputService {
    boolean warmUp();
    boolean tap(int x, int y, int downUpDelayMs);
    boolean tapBatch(in int[] xs, in int[] ys, int intervalMs, int downUpDelayMs);
    String status();
}
```

### 5.2 fallback 输入

如果 direct inject 失败：

1. 回退到当前 `/system/bin/input tap x y`。
2. 如果 Shizuku 不可用，再回退到 Accessibility `dispatchGesture()`。
3. 日志必须记录当前输入模式：`DIRECT_INJECT`、`SHELL_INPUT`、`ACCESSIBILITY_GESTURE`。

## 6. 热路径状态机

V2 将运行逻辑分为热路径和恢复路径。

热路径只允许执行：

- 定时触发。
- 坐标点击。
- 前台 Activity / 包名观察。
- 极少量状态事件记录。

热路径禁止执行：

- 截图。
- 全量 UI tree dump。
- 大量日志写入。
- 阻塞式节点遍历。
- 运行期重新绑定 Shizuku。

### 6.1 阶段一

触发时间到达后：

1. 直接点击阶段一校准坐标。
2. 监听大麦 Activity 是否进入 `NcovSkuActivity`。
3. 进入后立即切到阶段二。
4. 未推进时按短间隔有限重试。
5. 超过热路径窗口后交给恢复路径。

### 6.2 阶段二

进入票档页后：

1. 优先点击阶段二校准坐标。
2. 如果已有可见节点“确定”，可以使用节点点击，但不得阻塞热路径。
3. 点击后监听是否进入确认/提交相关页面。
4. 页面未推进时，恢复路径再检查票档是否不可选、按钮是否禁用、是否需要视觉匹配。

### 6.3 阶段三

进入提交页后：

1. 优先点击阶段三校准坐标。
2. 同步尝试精确文本或 view ID 节点点击作为补充。
3. 点击后进入 `DONE_PENDING_RESULT`。
4. 只有观察到配置结果文本时进入 `DONE`。

## 7. 触发调度

启动待命时提前完成：

- 计算三个阶段的像素坐标。
- 绑定并预热 Shizuku 输入服务。
- 检查 Shizuku server、权限、输入模式。
- 获取必要的 wakelock，降低休眠风险。
- 将 UI 更新和重日志写入移出热路径。

到点前调度：

- 远离目标时间时低频等待。
- 临近目标时间时切到专用 `HandlerThread` 或 `ScheduledExecutor`。
- 最后几十毫秒使用 `SystemClock.elapsedRealtimeNanos()` 对齐目标时间。

## 8. 性能埋点

每次运行至少记录以下事件：

- `armed_at`
- `shizuku_warmup_start`
- `shizuku_warmup_end`
- `trigger_deadline`
- `trigger_fired`
- `stage1_tap_start`
- `stage1_tap_end`
- `sku_activity_observed`
- `stage2_tap_start`
- `stage2_tap_end`
- `stage3_observed`
- `stage3_tap_start`
- `stage3_tap_end`
- `result_observed`
- `cancelled_or_failed`

每个事件记录：

- `System.currentTimeMillis()`
- `SystemClock.elapsedRealtimeNanos()`
- 当前阶段
- 输入模式
- 前台 Activity
- 简短原因

导出时计算相邻事件耗时，便于判断慢点来自定时、输入、页面加载还是状态识别。

## 9. UI 与配置

V2 UI 增加一个“低延迟模式”区域：

- Shizuku 状态：未运行、未授权、已绑定、direct inject 可用、fallback 中。
- 输入模式：direct / shell input / accessibility。
- 热路径开关：默认开启。
- 视觉兜底延迟：默认点击后 250 到 400ms 再启用。
- 性能日志导出按钮。

已有中文说明保留，并明确提示：

- 手机重启后需要重新启动 Shizuku。
- Shizuku 已运行时可拔掉 USB 使用。
- 实际抢票前应先确认 Shizuku 状态为“已绑定”。

## 10. 测试计划

### 单元测试

- `FastTapper` 输入模式选择。
- Shizuku direct 失败时 fallback 顺序。
- 热路径状态机阶段流转。
- 性能事件耗时计算。
- 配置持久化兼容 V1。

### 实机测试

- Shizuku adb 启动后，App 能绑定输入服务。
- 拔掉 USB 后，App 仍可通过 Shizuku 点击。
- 手机重启后，App 正确提示 Shizuku 未运行。
- 阶段一项目详情页点击进入 `NcovSkuActivity`。
- 阶段二有可选票档时进入下一页。
- 阶段三点击后进入 `DONE_PENDING_RESULT` 或识别到结果页。
- Shizuku server 被杀时，App 正确 fallback 或失败提示。

## 11. 风险

- `InputManager.injectInputEvent` 是系统内部路径，不同 Android/OEM 版本可能限制不同。
- 华为系统可能在某些 adb activity/task 命令下触发 framework 异常，因此 V2 不依赖 `am stack move-task`。
- 大麦页面结构、Activity 名称和按钮逻辑可能随版本变化。
- 热路径过快可能在页面未完成布局时误点，因此阶段二、阶段三仍需要校准区域和有限重试。
- 无 root 时无法彻底摆脱 Shizuku 启动前置条件。

## 12. 分阶段实施

第一步：

- 实现 `FastInputService` direct inject。
- 保留 shell input fallback。
- 增加 Shizuku 状态展示。

第二步：

- 增加热路径 coordinator。
- 阶段一、二、三坐标优先。
- 视觉 fallback 延后。

第三步：

- 增加性能埋点和导出。
- 用实际抢票或安全测试数据调参。

第四步：

- 根据真实日志优化重试间隔、阶段超时、视觉兜底触发条件。
