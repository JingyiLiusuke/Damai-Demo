# Android 真机校准与验证记录

## 当前基线

- 记录日期：2026-06-07
- 应用包名：`com.local.damaiassistant`
- 真机：华为 NOH-AN00，Android 12 / API 31
- JDK：Temurin 17.0.19+10
- 编译 API：36
- Build Tools：36.0.0
- Platform Tools：37.0.0
- JVM 测试：70 个，0 失败
- 真机仪器测试：3 个，0 失败
- Debug APK：`android-app/app/build/outputs/apk/debug/app-debug.apk`

Debug APK SHA-256：

```text
D42EBF2618E1B31F7D287A18263C0FA84E3494899EEEEDCB3EE7D556DC040E8D
```

## 使用边界

大麦应用及目标页面由用户手动打开。助手不会自动启动或重新导航大麦，以免破坏已进入的项目详情页。

助手只负责：

1. 通过无障碍服务确认当前活动窗口属于 `cn.damai`。
2. 运行三阶段混合状态机。
3. 执行节点点击、标定坐标点击和视觉兜底。
4. 导出截图、节点树、配置和运行日志。

节点检查、节点点击、坐标手势和视觉截图执行前都会重新校验活动窗口。窗口不属于大麦时不会发送动作。

## 安装与启用

```powershell
adb install -r android-app/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.local.damaiassistant/.ui.MainActivity
```

安装或仪器测试后，华为系统可能关闭无障碍权限。需手动开启“大麦自动化助手”，并确认主页显示“无障碍服务：已连接”。

## 安全校准步骤

1. 手动进入大麦对应阶段页面。
2. 返回助手，点击“校准阶段区域”并选择阶段。
3. 在 10 秒内手动切回原大麦页面。
4. 助手确认页面稳定后自动截图，不会重新启动大麦。
5. 截图完成后返回助手，框选对应按钮并保存。
6. 对阶段一“立即购票”、阶段二“确定票价”、阶段三“立即提交”分别校准。

切换提示显示在助手页面内，不使用跨应用 Toast，避免污染校准截图和视觉模板。

## 真机验证结果

2026-06-07 已完成以下只读验证：

- 无障碍服务成功绑定，活动窗口识别为 `cn.damai`。
- 项目详情页识别为 `ProjectDetailActivity`。
- 第一阶段页面的无障碍树可读取，节点资源 ID 为 `cn.damai:id/...`。
- 页面底部“立即购票”未暴露为可点击无障碍节点，因此阶段一使用标定坐标点击。
- 触发调试采集后，通过系统任务切换回原大麦任务，仍保持在同一项目详情页。
- 调试 ZIP 成功生成，`screen.png` 与 `nodes.txt` 均来自同一大麦详情页。
- 助手未点击“立即购票”，未进入下单流程。

## 测试命令

```powershell
cd android-app
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

## 调试文件导出

```powershell
adb shell run-as com.local.damaiassistant ls files/exports
adb exec-out run-as com.local.damaiassistant cat files/exports/<file> > capture.zip
```

调试 ZIP 包含：

- `screen.png`
- `nodes.txt`
- `config.txt`
- `run-log.txt`

导出目录属于应用私有存储，不需要网络或外部存储权限。

## 待完成

- 分别采集阶段二“确定票价”和阶段三“立即提交”的真实节点树。
- 在不产生订单的测试路径中验证完整状态迁移。
- 每条关键延迟路径至少采集 30 个样本，再依据 P95 调整重试间隔。
