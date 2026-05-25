# 漫画阅读双指缩放设置设计

## 目标

为漫画阅读器增加一个可关闭的双指缩放能力。该功能默认关闭，用户需要在“设置 > 漫画设置”里手动开启。关闭时阅读器行为必须与现状一致：点击显示或隐藏控制层、Pager/LazyColumn 翻页、纵向连续滚动、音量键翻页和自动翻页不受影响。

## 非目标

- 不增加双击缩放、缩放倍率快捷按钮或独立工具栏。
- 不保存每页缩放状态，离开页面或切换页面后恢复默认 1x。
- 不改变图片解码、缓存、预取或页面加载策略。
- 不重写阅读器分页结构。

## 设置模型

在 `AppSettings` 增加 `readerPinchZoomEnabled: Boolean = false`，并在 `AppSettingsStore` 增加对应 DataStore key 和更新方法。

设置页在“漫画设置”分组中加入一行开关：

- 标题：`双指缩放`
- 副标题：`在阅读时用双指放大并拖动查看细节`
- 默认值：关闭

`SettingsTabContent` 将开关更新委托给 `AppSettingsStore.updateReaderPinchZoomEnabled()`。`ReaderRoute` 将 `appSettings.readerPinchZoomEnabled` 传入 `ReaderScreen`。

## 阅读器行为

`ReaderScreen` 增加参数 `pinchZoomEnabled: Boolean = false`。

当开关关闭时，`ReaderImagePage` 不安装缩放手势处理，保持现有布局和手势冲突行为。

当开关开启时，每个 `ReaderImagePage` 维护页面本地缩放和平移状态：

- 初始缩放为 `1f`。
- 缩放范围限制为 `1f..4f`。
- 缩放比例回到 `1f` 时平移归零。
- 缩放比例大于 `1f` 时允许拖动已放大的图片。
- 页面重新组合到不同 `page` 或不同 `pageFile` 时重置状态。

缩放应作用在图片内容层，而不是整个 Pager 或整条连续列表。这样可以避免影响页码上报、预取判断和连续阅读的可见页计算。

## 手势冲突

阅读器外层仍保留点击切换控制层的 `detectTapGestures`。

缩放手势只在 `pinchZoomEnabled` 为 true 时安装到图片页内部。实现需要避免单指普通翻页被缩放层吞掉；只有实际多指缩放或缩放后拖动时才消费变换手势。缩放回 `1f` 后，单指拖动应继续交给 Pager/LazyColumn。

## 测试

采用 TDD 增加以下测试：

- `AppSettingsStoreTest`：默认关闭，更新后可读回。
- `SettingsScreenUiTest`：漫画设置布局包含“双指缩放”。
- `ReaderScreenSettingsTest`：缩放倍率会被限制在 `1f..4f`，缩回 `1f` 时平移归零。

Compose 手势的真实多指交互难以在当前单元测试环境完整模拟，因此核心数学逻辑抽成小的纯函数测试；Compose 层保持薄封装并由构建验证覆盖。

## 风险与缓解

主要风险是缩放手势和 Pager/LazyColumn 翻页抢手势。缓解方式是将缩放状态限制在单页图片层，并只在开关开启时安装该逻辑；关闭时不存在行为改变。

连续纵向阅读中，放大后的页面高度仍由原始图片布局决定，缩放只改变绘制层，不改变列表测量高度。这避免可见页上报和滚动位置被缩放动态改变。
