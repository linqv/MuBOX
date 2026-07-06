# 漫画阅读器临时横屏模式设计

## 目标

为漫画阅读器增加一个当前阅读会话内的横屏切换按钮。用户打开一本漫画后，可以在阅读器控制层里临时进入横屏或退出横屏并恢复竖屏；横屏模式默认允许传感器在两个横向方向之间切换，并提供一个顶部锁按钮关闭该传感器切换。这个选择不写入设置，不影响下一次打开漫画的默认行为。

## 非目标

- 不在设置页增加默认横屏开关。
- 不修改 `AndroidManifest.xml` 的 `screenOrientation`。
- 不改变阅读方向设置。`从左到右`、`从右到左`、`纵向翻页`、`纵向滚动`仍只决定页面翻动方式。
- 不改变图片解码、页面缓存、预取、进度保存或自动翻页逻辑。
- 不支持反向竖屏。

## 当前状态

MuBOX 是单 Activity Compose 应用，漫画阅读器通过 `isReaderOpen` 切换到 `ReaderRoute`。全局“屏幕旋转锁定”已经由 `ComicDavApp` 在运行时设置 `Activity.requestedOrientation`，并通过 `mainAppRequestedOrientation(screenRotationLockEnabled)` 映射到 `SCREEN_ORIENTATION_LOCKED` 或 `SCREEN_ORIENTATION_UNSPECIFIED`。

Android 16 行为测试要求 Manifest 不声明固定方向，所以漫画横屏模式也必须走运行时 `requestedOrientation`。

阅读器 UI 的控制层目前由点击页面显示或隐藏。显示时顶部有日志和关闭按钮，底部有页码、自动翻页和进度条。

## 方案

采用会话级状态和 Activity 级方向控制。

在 `ComicDavApp` 增加 `readerLandscapeModeEnabled` 和 `readerLandscapeOrientationLocked`，使用 `rememberSaveable` 保存。`ReaderRoute` 接收这些状态和切换回调，并传给 `ReaderScreen`。`ReaderScreen` 只展示按钮并发出回调，不直接修改 Activity 方向。

`ComicDavApp` 根据阅读器状态统一计算主 Activity 的请求方向：

- 阅读器打开且 `readerLandscapeModeEnabled == true`、方向未锁定：请求 `ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE`。
- 阅读器打开且 `readerLandscapeModeEnabled == true`、方向已锁定：请求 `ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE`。
- 用户从阅读器横屏退出横屏：请求一次临时 `ActivityInfo.SCREEN_ORIENTATION_PORTRAIT`，避免阅读器或主界面继续停在横屏。
- 其他情况：恢复 `mainAppRequestedOrientation(appSettings.screenRotationLockEnabled)`。

这保持 Android 平台方向行为集中在 Activity 所在层，避免 Reader UI 直接操作系统状态。

## 阅读器交互

当阅读器控制层可见时，在顶部栏增加一个按钮：

- 未处于临时横屏：显示 `横屏`，点击后进入横屏。
- 已处于临时横屏：显示 `退出横屏`，点击后退出临时横屏并恢复竖屏。
- 已处于临时横屏时额外显示一个锁按钮。未锁定时显示 `锁定方向`，横屏跟随传感器在两个横向方向之间切换；锁定时显示 `解锁方向`，横屏固定，不再跟随传感器翻转。

横屏按钮、锁按钮与现有 `日志`、`关闭`按钮同级。控制层隐藏时按钮也隐藏，保持阅读画面干净。

关闭阅读器、取消打开、按返回退出阅读器时，清空 `readerLandscapeModeEnabled` 和 `readerLandscapeOrientationLocked`。这样临时横屏不会泄漏到应用其他页面或下一次阅读。

## 数据流

```text
ReaderTopBar button
  -> ReaderScreen(onLandscapeModeChange)
  -> ReaderRoute
  -> ComicDavApp.readerLandscapeModeEnabled / readerLandscapeOrientationLocked
  -> requestedOrientation effect
  -> Activity.requestedOrientation
```

`rememberSaveable` 让临时横屏状态在方向切换导致的 Activity 重建后仍能保留。状态只在阅读器关闭路径清掉。

## 错误处理与恢复

方向设置通过 `(context as? Activity)?.requestedOrientation` 执行。如果当前 context 不是 Activity，则不崩溃，直接跳过平台方向请求；这种情况下按钮仍可改变 Compose 状态，但不会产生系统方向效果。

`DisposableEffect` 或生命周期恢复逻辑需要在 Activity 回到前台时重新应用当前方向。阅读器关闭后重新进入前台时应使用全局旋转锁定设置，而不是旧的临时横屏状态。

## 测试

采用 TDD 增加或更新 JVM 单元测试：

- 方向映射测试：阅读器横屏开启且未锁定时返回 `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`；锁定时返回 `SCREEN_ORIENTATION_LANDSCAPE`；关闭或阅读器未打开时返回全局 `mainAppRequestedOrientation()` 结果。
- Reader UI 控制测试：阅读器顶部控制项包含 `横屏` 或 `退出横屏`；横屏时包含 `锁定方向` 或 `解锁方向`；并保留 `日志`、`关闭`。
- 导航状态测试：关闭阅读器的纯函数或可测 helper 会清掉临时横屏状态；从横屏切回非横屏会触发临时竖屏恢复。

Compose 真实旋转由 Android 系统执行，当前单元测试不直接驱动设备旋转；实现会把方向计算和控制标签抽成小的纯函数，用单元测试覆盖核心行为。

验证命令：

```bash
./gradlew :app:testDebugUnitTest
```

## 预计修改文件

生产代码：

- `app/src/main/java/com/example/comicdav/AppNavigation.kt`
- `app/src/main/java/com/example/comicdav/AppContentRoutes.kt`
- `app/src/main/java/com/example/comicdav/MainActivity.kt`
- `app/src/main/java/com/example/comicdav/feature/reader/ReaderScreen.kt`

测试代码：

- `app/src/test/java/com/example/comicdav/AppNavigationTest.kt` 或现有同级测试
- `app/src/test/java/com/example/comicdav/feature/reader/ReaderScreenTest.kt` 或现有 reader UI helper 测试
