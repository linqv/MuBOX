# AVIF 软件解码实施指令

## 目标

为 MuBOX 的漫画阅读页、书库封面和历史封面接入应用自带的 AVIF 软件解码，绕开系统 AVIF 解码及 MediaCodec，输出普通 `ARGB_8888` Bitmap。

本文用于指导后续代码实施。“必须”和“禁止”均为验收约束。MTK 驱动问题只是待验证原因，不得宣称已经定位或彻底修复。

## 固定方案

- 使用 `io.github.awxkee:avif-coder-coil:2.2.1`；其发布依赖为 Coil `3.4.0`，与本项目一致。
- 保持 Coil `3.4.0`、现有 SDK 和 ABI 配置。禁止使用旧 JitPack 坐标、动态版本或 `3.0.0-alpha*`。
- 复用库内 libavif/dav1d 软件解码。允许增加薄 Kotlin 适配层，处理格式识别、输出选项、错误与取消。
- 禁止引入自建 Rust/JNI 解码器、FFmpeg 图片解码、MediaCodec 或 mpv 截图路径；禁止修改视频播放器及替换 mpv AAR。
- 首版只输出静态 Bitmap；序列 AVIF 只显示主图或首帧，不实现动画播放。

在 `gradle/libs.versions.toml` 的现有分组中增加：

```toml
[versions]
avifCoder = "2.2.1"

[libraries]
avif-coder-coil = { module = "io.github.awxkee:avif-coder-coil", version.ref = "avifCoder" }
```

由持有图片解码适配代码的 Android 模块声明依赖；禁止向所有 feature 重复添加依赖。

## 解码约束

1. 在应用共享 `ImageLoader` 注册 AVIF 专用 Factory，优先于系统图片 Decoder；阅读页和所有漫画封面必须使用该加载链路。
2. Factory 只接管 AVIF。必须按内容识别 `ftyp` 主品牌和兼容品牌，探测不得消费原始输入，且必须限制探测长度。页面缓存使用 `.img`，禁止依赖文件扩展名或 MIME；不得把所有 HEIF 都视为 AVIF。
3. 只对 AVIF 强制 `allowHardware(false)`、`bitmapConfig(Bitmap.Config.ARGB_8888)`、`allowRgb565(false)`，确认底层获得 `PreferredColorConfig.RGBA_8888`。禁止输出 `HARDWARE`、`RGB_565` 或自动选择高位深 Bitmap；不得全局改变其他格式的输出策略。
4. 已识别的 AVIF 解码失败必须产生带原因的 Coil 错误；取消必须传播 `CancellationException`。禁止返回 `null` 让 Coil 尝试系统 Decoder，禁止失败后重试 MediaCodec。
5. 不得直接无封装使用上游 `HeifDecoder.Factory()`：该版本的 Decoder 会捕获异常并返回 `null`。适配层必须保留原始错误、传播取消，并拒绝已识别 AVIF 的空解码结果；允许调用其底层 `HeifCoder` 实现这些要求。
6. 必须尊重 Coil 请求尺寸及 FIT/FILL 语义，保持比例并正确设置 `isSampled`。禁止先转 PNG/JPEG 落盘再交给 Coil；现有页面文件继续缓存原始压缩图片。
7. 必须在后台解码，为压缩输入大小、源图像素数、输出大小及解码并发设置具名上限，并在对应分配前校验。禁止无上限 `readByteArray()` 或无限并发；原生解码可能先分配全尺寸图像，不得假设缩略图请求天然低内存。
8. 解码错误进入现有诊断系统，记录解码器、尺寸和失败原因；不得记录图片内容、凭据或完整远程 URL。软件 Bitmap 仍可由 GPU 绘制，不得将此方案描述为禁用了全部 GPU/驱动路径。

## 仓库改动

| 位置 | 必须完成的改动 |
| --- | --- |
| [image.rs](comic-core/src/image.rs) | 接受 `.avif`，大小写不敏感；将现有拒绝 AVIF 测试改为支持测试。 |
| [index_cache.rs](comic-core/src/cache/index_cache.rs) | 升级索引缓存版本，重新生成此前过滤掉 AVIF 的页面列表。 |
| [ReaderPageCache.kt](feature/reader/src/main/kotlin/org/mubox/reader/feature/reader/ReaderPageCache.kt) | 版本化页文件缓存，阻止新页序复用旧 `page-N.img`。 |
| [WebDavLibraryCoverExtractor.kt](app/src/main/java/org/mubox/reader/infrastructure/library/WebDavLibraryCoverExtractor.kt) 及其他漫画封面缓存入口 | 使旧派生封面失效并可重新生成；不得持续引用失效路径。 |
| [MuBoxApplication.kt](app/src/main/java/org/mubox/reader/MuBoxApplication.kt) 及图片适配代码所属模块 | 注册共享解码链路，覆盖阅读页、书库和历史封面。 |
| [NOTICE](NOTICE) | 按实际发布包补充新增依赖及许可证信息。 |

- 页面识别、索引版本和派生缓存迁移必须作为同一次变更交付；禁止只更新扩展名或只升级索引版本。
- 缓存版本化不得改变书籍身份或删除收藏、阅读历史、原始漫画及下载文件。验证混合 JPG/AVIF 漫画的页序变化，处理可恢复的阅读位置映射。
- 保持现有阅读页 Coil 内存/磁盘缓存策略；不得借此重写预取、Range 请求、ZIP 解包或设置界面。
- 保留工作区已有改动。只修改实现本目标所需文件，不做无关重构。

## 验收

以下各项必须给出实际结果；未执行的项目明确标为“未验证”。

- **依赖与打包：** 检查 Gradle 实际解析结果为插件/核心库 `2.2.1`、Coil `3.4.0`；检查 `arm64-v8a` 和 `x86_64` 原生库及 16 KB 页面对齐，验证 Release 混淆后的 JNI 加载。禁止用任意 `pickFirst` 掩盖原生库冲突。
- **解码行为：** 使用真实 AVIF 样本测试 `.img` 输入、兼容品牌识别、8/10/12 位、透明度、网格、方向/裁剪和颜色；断言输出为 `ARGB_8888`。非 AVIF 必须继续使用原有链路。
- **失败与取消：** 覆盖损坏、截断、超限输入及取消；用可观测的后续 Decoder 验证已识别 AVIF 失败时没有发生回退，而不是只断言“加载失败”。
- **缓存迁移：** 预置旧索引和旧页文件，打开混合格式漫画，验证页数、顺序、显示内容、封面及阅读位置；只测试新安装不算通过。
- **设备体验：** 在天玑 9300+ 真机验证用户失败样本、封面、连续翻页、回翻及缩放，记录耗时和峰值内存；用 Release 构建评估性能，禁止用 Mock 或宿主机测试替代设备解码结果。
- **回归：** 执行 `cargo test --locked --manifest-path comic-core/Cargo.toml`、受影响 Android 模块的单元/仪器测试及构建。测试必须断言行为，不得以源码字符串匹配代替功能验证。

缺少真机、问题样本或 Release 签名时，继续完成独立可执行工作，并在交付中列出具体缺口；不得据此宣称兼容性问题已解决。

## 交付

交付实现代码、必要回归样本与测试、缓存迁移及依赖声明。最终说明只需包含：修改内容、已通过检查、未验证事项、相同 ABI 下的包体增量及真机结果。禁止把方案建议代替代码交付。

## 核查依据

- [Coil 插件 2.2.1 发布依赖](https://repo.maven.apache.org/maven2/io/github/awxkee/avif-coder-coil/2.2.1/avif-coder-coil-2.2.1.pom)
- [Coil 插件 2.2.1 发布源码](https://repo.maven.apache.org/maven2/io/github/awxkee/avif-coder-coil/2.2.1/avif-coder-coil-2.2.1-release-sources.jar)
- [核心库 2.2.1 AVIF 解码入口](https://github.com/awxkee/avif-coder/blob/2.2.1/avif-coder/src/main/cpp/JniDecoder.cpp)

核查日期：2026-09-05。以固定版本发布物为准，禁止照搬主仓库中仍使用 Coil 2 API 的旧插件示例。
