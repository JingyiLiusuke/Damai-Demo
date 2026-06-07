# Android 真机校准与延迟记录

## 当前状态

- 记录日期：2026-06-07
- 应用包名：`com.local.damaiassistant`
- 目标设备：Android 12 真机
- 当前 ADB 状态：未连接设备
- Appium：校准时仅允许只读检查节点，不与 APK 同时执行点击

本文件中的真机延迟数据尚未采集。没有设备数据前，不调整默认重试间隔。

## 本机构建基线

- JDK：Temurin 17.0.19+10
- Android API：36
- Build Tools：36.0.0
- Platform Tools：37.0.0
- JVM 测试：67 个，0 失败
- Debug APK：`android-app/app/build/outputs/apk/debug/app-debug.apk`
- Release APK：`android-app/app/build/outputs/apk/release/app-release-unsigned.apk`

Debug APK SHA-256：

```text
C16ED8D0C169CC4F278D712871D7A204166283FC4FBE5CADAFA3CD81BA8C33E8
```

## 安装与启用

```powershell
adb install -r android-app/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.local.damaiassistant/.ui.MainActivity
```

手动开启 `Damai Assistant Automation` 无障碍服务。主页面必须显示服务已连接，
并且最近前台包为 `cn.damai`，才允许进入待命状态。

## 安全校准步骤

1. 使用非开售或不会产生真实订单的页面路径。
2. 手动进入对应阶段页面，不启动 Appium 点击。
3. 返回助手选择对应校准阶段；助手会切回大麦并延迟截图。
4. 截图完成后手动返回助手，框选页会自动打开。
5. 每个矩形紧贴按钮，最小尺寸为源截图中的 20 x 20 像素。
6. 保存后确认 `files/templates/stage-1.png` 至 `stage-3.png` 均存在。
7. 调试采集同样会先切回大麦；完成后返回助手查看导出路径。
8. 导出调试 ZIP，核对 `nodes.txt` 中的 ID 和文本。
9. 只有三阶段识别均正确后，才进行非下单测试流程。

## 确定性测试

```powershell
cd android-app
.\gradlew.bat :app:testDebugUnitTest `
  --tests '*TicketStateMachineTest' `
  --tests '*AutomationCoordinatorTest'
```

必须覆盖停止、服务断开、超时、点击上限、旧回调、前台包变化和结果确认。

## 延迟采样

每条路径至少记录 30 个样本，单位为毫秒。填写平均值、P95 和最大值。

| 路径 | 样本数 | 平均 | P95 | 最大 |
|---|---:|---:|---:|---:|
| 触发截止时间 -> 调用 `dispatchGesture` | 待测 | 待测 | 待测 | 待测 |
| `dispatchGesture` -> `onCompleted` | 待测 | 待测 | 待测 | 待测 |
| 收到无障碍事件 -> 找到节点 | 待测 | 待测 | 待测 | 待测 |
| 节点 `ACTION_CLICK` -> 下一阶段事件 | 待测 | 待测 | 待测 | 待测 |
| 调用 `takeScreenshot` -> 收到 Bitmap | 待测 | 待测 | 待测 | 待测 |
| 收到 Bitmap -> 生成视觉匹配分数 | 待测 | 待测 | 待测 | 待测 |

仅当 P95 数据证明默认值过快或过慢时，才修改 `AutomationConfig`。

## 独立运行确认

1. 断开 USB。
2. 关闭 Appium Server。
3. 保持手机解锁、亮屏和大麦前台。
4. 使用非下单测试路径启动待命。
5. 确认流程不依赖任何 PC 进程。

## 调试文件导出

```powershell
adb shell run-as com.local.damaiassistant ls files/exports
adb exec-out run-as com.local.damaiassistant cat files/exports/<file> > capture.zip
```

导出目录属于应用私有存储，不需要网络或外部存储权限。
