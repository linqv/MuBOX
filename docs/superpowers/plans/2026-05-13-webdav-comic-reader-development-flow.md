# WebDAV 漫画阅读器开发流程实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 做一款 Android 漫画阅读器，支持 WebDAV 书库浏览，并能在线流畅阅读 `.cbz` / `.zip` 漫画。

**架构：** Android/Kotlin 负责 UI、WebDAV 网络、账号、生命周期、取消任务、图片展示和系统集成。Rust 负责 CBZ/ZIP 索引、ZIP64 解析、页面提取、缓存元数据、Range 规划和预取调度。Kotlin 与 Rust 通过窄 JNI 接口通信，主要传递 handle、页码请求、错误码和缓存文件路径，不传递完整解码后的大图。

**技术栈：** Kotlin、Jetpack Compose、Navigation Compose、OkHttp、Coil、Room 或 DataStore、Android Keystore、Rust、JNI/C ABI、Deflate、Gradle、Cargo、JUnit、Rust 单元测试。

---

## 范围判断

这是完整产品的总开发流程，不是单个小功能计划。它覆盖多个相对独立的子系统：

- Android 应用壳和阅读器 UI
- WebDAV 客户端和账号管理
- Rust CBZ/ZIP 解析核心
- JNI 桥接
- 整本下载缓存 MVP
- 远程 Range 在线阅读
- 缓存、预取、取消和性能优化
- 兼容性与发布准备

因此实施时应该按阶段拆成更细的计划。本文档负责确定阶段顺序、验收口径、风险门槛和拆分边界。不要在阶段 1、阶段 2、阶段 3、阶段 4 跑通之前直接做阶段 5 的远程 Range 阅读。

## 开发原则

- 按垂直切片推进。每个阶段都要产出能运行或能测试的东西。
- 网络留在 Kotlin。OkHttp 负责 TLS、认证、代理、重定向、取消任务和连接复用。
- 压缩包逻辑留在 Rust。Rust 负责 ZIP 结构、页面排序、压缩字节范围、缓存 key 和页面提取。
- JNI 不传完整解码 bitmap。Rust 输出页面缓存文件路径，Android 用 Coil 解码和展示。
- 默认 WebDAV 服务器行为不可靠。`HEAD`、`Range`、`ETag`、`Content-Range`、路径编码都要逐项验证。
- 性能必须可测量。影响阅读速度的阶段都要能解释首屏时间、页面缓存命中率、请求数量和取消任务行为。
- 当前 `/home/lin/webcomic` 目录下的 `.git` 不能被 `git status` 识别为可用仓库。开始提交前需要先修复仓库或重新初始化 Git。

## 目标产品行为

第一版内测目标：

- 添加并测试 WebDAV 账号。
- 浏览远程目录。
- 筛选并打开 `.cbz` / `.zip` 文件。
- 服务器支持 Range 时，不整本下载即可打开远程漫画。
- 通过读取文件尾部和 Central Directory 建立 ZIP 页面索引。
- 快速显示第一页。
- 保存阅读进度。
- 缓存解析后的索引。
- 缓存解压后的页面图片文件。
- 围绕当前页预取前后页面。
- 快速翻页时取消或降低过期任务优先级。
- Range 不可用或返回异常时，回退到整本下载缓存阅读。

第一版暂不做：

- CBR/RAR
- PDF
- 7z
- 双页模式
- 复杂书架刮削
- 多设备同步
- WebDAV 上传

## 性能目标

用这些指标判断“在线阅读是否流畅”：

- 首次远程打开且无缓存：稳定 Wi-Fi 和普通 WebDAV 服务器上，1 到 3 秒内显示第一页。
- 二次远程打开且索引/页面缓存命中：0.5 到 1 秒内显示第一页。
- 顺序阅读：正常向后翻页时，下一页缓存命中率至少 80%。
- 远程打开请求数：通常在第一页请求前完成 2 到 4 个 HTTP 请求，具体取决于 `HEAD` 是否成功和 Central Directory 大小。
- 页面请求：相邻压缩 range 间距较小时合并，但合并后的请求不能超过配置上限。
- 内存：Kotlin 不长期持有页面大 `ByteArray`。UI 主线程不做网络、ZIP 解析或阻塞 JNI 调用。

## 推荐仓库结构

开始实现时创建以下结构：

```text
ComicDav/
  app/
    src/main/java/<package>/
      ui/
      feature/
        bookshelf/
        reader/
        webdav/
      data/
      domain/
      nativebridge/
      network/
    build.gradle.kts
  comic-core/
    Cargo.toml
    src/
      lib.rs
      error.rs
      ffi.rs
      cbz/
      zip/
      cache/
      scheduler/
      sort/
  build.gradle.kts
  settings.gradle.kts
  docs/
    superpowers/
      plans/
```

目录职责：

- `app/feature/webdav/`：账号页面、连接测试、远程目录浏览。
- `app/network/`：OkHttp WebDAV 实现、PROPFIND、HEAD fallback、Range GET 校验。
- `app/feature/reader/`：Compose 阅读器、分页器、进度、重试 UI。
- `app/nativebridge/`：JNI 包装、handle 生命周期、线程调度、错误映射。
- `comic-core/src/zip/`：EOCD、ZIP64 EOCD、Central Directory、Local Header、压缩方式处理。
- `comic-core/src/cbz/`：页面索引、页面排序、页面提取入口。
- `comic-core/src/cache/`：索引缓存、页面缓存、缓存 key 生成。
- `comic-core/src/scheduler/`：Range 规划、任务优先级、预取窗口。
- `docs/superpowers/plans/`：分阶段实施计划。

## 阶段 0：项目初始化

**目标：** 建立可构建的 Android + Rust 工作区，先证明工具链可用。

**文件：**

- 创建：`settings.gradle.kts`
- 创建：`build.gradle.kts`
- 创建：`app/build.gradle.kts`
- 创建：`comic-core/Cargo.toml`
- 创建：`comic-core/src/lib.rs`
- 创建：`.gitignore`

**步骤：**

- [ ] 创建 Android Gradle 项目和 `app` 模块。
- [ ] 创建 Rust crate：`comic-core`。
- [ ] 配置 Android ABI：`arm64-v8a` 和 `x86_64`。
- [ ] 配置最小 native library 构建链路，能产出 `libcomic_core.so`。
- [ ] 添加最小 Compose `MainActivity`，显示应用壳。
- [ ] 添加 Rust smoke test，验证 crate 能构建。
- [ ] 运行 `./gradlew :app:assembleDebug`。
- [ ] 在 `comic-core/` 下运行 `cargo test`。
- [ ] 如果 Git 仓库不可用，先修复或初始化 Git。
- [ ] 提交：`chore: bootstrap android rust workspace`。

**验收：**

- Android debug APK 能构建。
- Rust crate 测试通过。
- README 记录本地构建和测试命令。

## 阶段 1：Android 壳与 WebDAV 探测

**目标：** 证明 App 可以浏览 WebDAV，并能对真实服务器执行字节范围读取。

**文件：**

- 创建：`app/src/main/java/<package>/network/WebDavClient.kt`
- 创建：`app/src/main/java/<package>/network/WebDavModels.kt`
- 创建：`app/src/main/java/<package>/feature/webdav/WebDavAccountScreen.kt`
- 创建：`app/src/main/java/<package>/feature/webdav/WebDavBrowserScreen.kt`
- 创建：`app/src/main/java/<package>/feature/webdav/WebDavViewModel.kt`
- 创建：`app/src/test/java/<package>/network/WebDavClientTest.kt`

**核心接口：**

```kotlin
interface WebDavClient {
    suspend fun list(path: String): List<WebDavItem>
    suspend fun head(path: String): RemoteFileInfo
    suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray
}

data class WebDavItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long?,
    val etag: String?,
    val lastModified: Long?
)

data class RemoteFileInfo(
    val path: String,
    val size: Long,
    val etag: String?,
    val lastModified: Long?,
    val supportsRange: Boolean
)
```

**步骤：**

- [ ] 使用固定版本添加 OkHttp 依赖。
- [ ] 实现 `PROPFIND Depth: 1`。
- [ ] 用 XML parser 解析 WebDAV XML 响应。
- [ ] 实现 `HEAD`。
- [ ] `HEAD` 失败时，从 `PROPFIND` 提取 size、ETag、lastModified。
- [ ] 实现 `GET` + `Range: bytes=start-end`。
- [ ] 校验 `206 Partial Content` 和 `Content-Range`。
- [ ] Range 请求返回 `200 OK` 时，默认判定为 Range 不支持。
- [ ] 添加账号测试页面：URL、用户名、密码、连接结果。
- [ ] 添加目录浏览页面，显示文件夹和 `.cbz` / `.zip`。
- [ ] 添加诊断动作：读取选中文件尾部 64 KiB。
- [ ] 为 XML 解析和 Range 响应校验写单元测试。
- [ ] 至少连接一个真实 WebDAV 服务手动测试。
- [ ] 提交：`feat: add webdav browser and range probe`。

**验收：**

- 用户能添加 WebDAV 账号。
- 用户能浏览目录。
- 用户能看到 `.cbz` / `.zip`。
- App 能通过 Range GET 读取远程文件尾部。
- App 能明确报告服务器不支持 Range。

## 阶段 2：Rust 本地 CBZ/ZIP 核心

**目标：** 不接 Android、不接 JNI，先让 Rust 能解析和提取本地 `.cbz` / `.zip`。

**文件：**

- 创建：`comic-core/src/error.rs`
- 创建：`comic-core/src/zip/mod.rs`
- 创建：`comic-core/src/zip/eocd.rs`
- 创建：`comic-core/src/zip/central_directory.rs`
- 创建：`comic-core/src/zip/local_header.rs`
- 创建：`comic-core/src/zip/zip64.rs`
- 创建：`comic-core/src/cbz/mod.rs`
- 创建：`comic-core/src/cbz/index.rs`
- 创建：`comic-core/src/cbz/page.rs`
- 创建：`comic-core/src/sort/mod.rs`
- 创建：`comic-core/src/sort/natural.rs`
- 创建：`comic-core/tests/cbz_local.rs`

**核心接口：**

```rust
pub trait RangeReader {
    fn size(&self) -> anyhow::Result<u64>;
    fn read_range(&self, start: u64, end_inclusive: u64) -> anyhow::Result<Vec<u8>>;
}

pub struct CbzPageEntry {
    pub name: String,
    pub local_header_offset: u64,
    pub data_offset: Option<u64>,
    pub compressed_size: u64,
    pub uncompressed_size: u64,
    pub compression_method: u16,
    pub crc32: u32,
}

pub struct CbzIndex {
    pub pages: Vec<CbzPageEntry>,
}
```

**步骤：**

- [ ] 实现 `FileRangeReader`。
- [ ] 为普通 ZIP 的 EOCD 查找写测试。
- [ ] 实现从文件尾部 256 KiB 查找 EOCD。
- [ ] 为 Central Directory 解析写测试。
- [ ] 实现 Central Directory parser。
- [ ] 为自然排序写测试：`1.jpg`、`2.jpg`、`10.jpg`。
- [ ] 实现图片 entry 过滤：`jpg`、`jpeg`、`png`、`webp`。
- [ ] 实现 Local File Header 解析和 `data_offset` 计算。
- [ ] 实现 Store method 页面提取。
- [ ] 实现 Deflate method 页面提取。
- [ ] 增加嵌套目录、中文文件名、Store、Deflate、空压缩包、无图片压缩包测试。
- [ ] 增加 ZIP64 fixture 测试，再启用远程 ZIP64 支持路径。
- [ ] 在 `comic-core/` 运行 `cargo test`。
- [ ] 提交：`feat: add local cbz zip core`。

**验收：**

- Rust 能打开本地 CBZ。
- Rust 能返回 page count。
- Rust 能按自然排序列出图片 entry。
- Rust 能提取指定页面为原始图片 bytes 或写入缓存文件。
- 普通 ZIP、Store、Deflate、嵌套目录、中文文件名、自然排序测试通过。

## 阶段 3：JNI 桥接与本地阅读

**目标：** Kotlin 接入 Rust，并用 Coil 显示本地 CBZ 页面。

**文件：**

- 创建：`comic-core/src/ffi.rs`
- 修改：`comic-core/src/lib.rs`
- 创建：`app/src/main/java/<package>/nativebridge/ComicNative.kt`
- 创建：`app/src/main/java/<package>/nativebridge/ComicEngine.kt`
- 创建：`app/src/main/java/<package>/feature/reader/ReaderScreen.kt`
- 创建：`app/src/main/java/<package>/feature/reader/ReaderViewModel.kt`

**FFI 形态：**

```rust
#[repr(C)]
pub struct ComicBuffer {
    pub ptr: *mut u8,
    pub len: usize,
}

pub type ComicHandle = u64;
```

```kotlin
object ComicNative {
    init {
        System.loadLibrary("comic_core")
    }

    external fun openLocal(path: String): Long
    external fun pageCount(handle: Long): Int
    external fun loadPageToFile(handle: Long, pageIndex: Int, cacheDir: String): String
    external fun close(handle: Long)
}
```

**步骤：**

- [ ] 添加 Rust session 存储，用 `ComicHandle` 索引。
- [ ] 实现 `comic_open_local`。
- [ ] 实现 `comic_page_count`。
- [ ] 实现 `comic_load_page_to_file`。
- [ ] 实现 `comic_close`。
- [ ] 为 native 分配的 buffer 和 string 添加释放函数。
- [ ] 优先使用 JNI `RegisterNatives`，避免 Kotlin 包名变化破坏 native 链接。
- [ ] 添加 Kotlin wrapper，把 native 错误码映射为类型化异常。
- [ ] 添加 Reader 页面，使用 `HorizontalPager`。
- [ ] 通过 `Dispatchers.IO` 加载当前页、下一页、上一页。
- [ ] 用 Coil 显示缓存文件。
- [ ] 在 `ViewModel.onCleared()` 释放 native handle。
- [ ] 在 `arm64-v8a` 真机或模拟器、`x86_64` 模拟器验证。
- [ ] 提交：`feat: connect rust core to android reader`。

**验收：**

- Android 能加载 `libcomic_core.so`。
- Kotlin 能打开本地 CBZ。
- Kotlin 能获取 page count。
- Reader 能显示指定本地页面。
- 关闭 Reader 会释放 native handle。

## 阶段 4：整本下载缓存 MVP

**目标：** 先做出可用阅读路径：从 WebDAV 下载整本漫画到缓存，再用本地阅读链路打开。

**文件：**

- 创建：`app/src/main/java/<package>/data/ComicDownloadCache.kt`
- 创建：`app/src/main/java/<package>/feature/reader/OpenComicUseCase.kt`
- 修改：`app/src/main/java/<package>/feature/webdav/WebDavBrowserScreen.kt`
- 修改：`app/src/main/java/<package>/feature/reader/ReaderViewModel.kt`

**步骤：**

- [ ] 用 OkHttp streaming 实现整本下载。
- [ ] 先写入临时文件。
- [ ] 下载完成后原子 rename 为正式缓存文件。
- [ ] 显示下载进度。
- [ ] 支持 UI 取消下载。
- [ ] 下载完成后用 Rust 打开本地文件。
- [ ] 以 remote path + size + ETag 或 lastModified 保存阅读进度。
- [ ] 重新打开同一本远程漫画时回到保存页码。
- [ ] 清理未完成的临时文件。
- [ ] 提交：`feat: add whole file cached reading mvp`。

**验收：**

- 用户能从 WebDAV 打开远程 CBZ。
- App 能下载到本地缓存。
- Reader 能打开并显示页面。
- 阅读进度能持久化。
- 下载取消可用。

## 阶段 5：远程 Range 在线阅读

**目标：** 服务器支持 Range 时，不下载整本文件也能打开并阅读远程 CBZ/ZIP。

**文件：**

- 创建：`app/src/main/java/<package>/nativebridge/RangeProvider.kt`
- 创建：`app/src/main/java/<package>/nativebridge/RangeProviderRegistry.kt`
- 创建：`app/src/main/java/<package>/network/WebDavRangeProvider.kt`
- 修改：`app/src/main/java/<package>/nativebridge/ComicNative.kt`
- 修改：`comic-core/src/ffi.rs`
- 创建：`comic-core/src/remote/mod.rs`
- 创建：`comic-core/src/remote/jni_range_reader.rs`

**核心接口：**

```kotlin
interface RangeProvider {
    fun size(fileId: Long): Long
    fun readRange(fileId: Long, start: Long, endInclusive: Long): ByteArray
}
```

```kotlin
data class OpenRemoteComicRequest(
    val accountId: String,
    val remotePath: String,
    val size: Long,
    val etag: String?,
    val lastModified: Long?,
    val cacheDir: String
)
```

**步骤：**

- [ ] 添加 `RangeProviderRegistry`，把 `fileId` 映射到存活的 provider。
- [ ] 基于现有 WebDAV `readRange` 实现 `WebDavRangeProvider`。
- [ ] 确保 provider 只在 IO 线程执行。
- [ ] 把 reader 请求取消和 OkHttp `Call` 取消连起来。
- [ ] 实现 Rust `JniRangeReader`。
- [ ] native worker thread 回调 Kotlin 前先 attach JVM。
- [ ] 实现 `comic_open_remote`。
- [ ] 远程打开时只读取文件尾部和 Central Directory。
- [ ] 从远程 range 解析 page table。
- [ ] 请求指定页面时读取远程 range，解压后写入页面缓存文件。
- [ ] Range 不支持或校验失败时，回退到阶段 4 的整本下载模式。
- [ ] 记录远程打开请求数和下载字节数。
- [ ] 提交：`feat: add remote range cbz reading`。

**验收：**

- Range-capable 服务器上，打开远程 CBZ 不下载整本。
- App 通过 Range 读取 EOCD 和 Central Directory。
- App 能在线显示第一页。
- App 能在未完整下载文件的情况下连续翻页。
- Range 不支持时能回退整本下载缓存阅读。

## 阶段 6：缓存、预取与流畅度

**目标：** 通过索引缓存、页面缓存、Range 合并和预取，让正常阅读过程足够顺滑。

**文件：**

- 创建：`comic-core/src/cache/mod.rs`
- 创建：`comic-core/src/cache/index_cache.rs`
- 创建：`comic-core/src/cache/page_cache.rs`
- 创建：`comic-core/src/scheduler/mod.rs`
- 创建：`comic-core/src/scheduler/range_planner.rs`
- 创建：`comic-core/src/scheduler/prefetch.rs`
- 修改：`app/src/main/java/<package>/feature/reader/ReaderViewModel.kt`
- 修改：`app/src/main/java/<package>/nativebridge/ComicEngine.kt`

**缓存 key：**

```text
comicKey = sha256(accountId + "\n" + remotePath + "\n" + size + "\n" + etagOrLastModified)
```

ETag 缺失时使用 `size + lastModified`。如果 ETag 和 lastModified 都缺失，把 accountId 和 remotePath 纳入 key，并把缓存标记为弱校验缓存。

**步骤：**

- [ ] 添加索引缓存格式：version、comic key、file size、validator、page entries。
- [ ] 读取远程文件尾和 Central Directory 前，先尝试加载索引缓存。
- [ ] size 或 validator 变化时，让索引缓存失效。
- [ ] 按 comic key 建立页面缓存目录。
- [ ] 页面缓存存在且有效时，立即返回缓存路径。
- [ ] 添加缓存容量上限和 LRU 清理。
- [ ] 基于压缩 entry 的物理 offset 实现 Range planner。
- [ ] 两个 range 间隔小于 64 KiB 且合并后小于 8 MiB 时合并。
- [ ] 添加优先级队列：当前页、下一页、上一页、前向窗口、后向窗口。
- [ ] Kotlin 向 Rust 发送 viewport update。
- [ ] 对不再有价值的预取任务执行取消或降级。
- [ ] 根据 Wi-Fi、移动网络、弱网选择不同预取窗口。
- [ ] 记录页面缓存命中率、预取命中率、请求数、读取字节数和首屏时间。
- [ ] 提交：`feat: add comic cache and prefetch scheduler`。

**验收：**

- 二次打开能命中索引缓存。
- 已访问页面能从页面缓存加载。
- 稳定 Wi-Fi 下，顺序向后阅读的下一页缓存命中率至少 80%。
- 快速滑动不会产生无上限网络请求。
- App 提供可见或可配置的缓存清理路径。

## 阶段 7：兼容性与异常处理

**目标：** 常见 WebDAV、ZIP、图片和网络异常都能被明确处理，不崩溃、不冻结 UI、不破坏缓存状态。

**文件：**

- 创建：`app/src/main/java/<package>/feature/settings/SettingsScreen.kt`
- 创建：`app/src/main/java/<package>/feature/reader/PageErrorView.kt`
- 创建：`app/src/main/java/<package>/diagnostics/ReaderDiagnostics.kt`
- 修改：`app/src/main/java/<package>/network/WebDavClient.kt`
- 修改：`comic-core/src/zip/zip64.rs`
- 修改：`comic-core/src/cbz/page.rs`
- 修改：`comic-core/src/cache/page_cache.rs`

**WebDAV 场景：**

- `HEAD` 不支持。
- `Range` 不支持。
- Range 返回 `200 OK`。
- `Content-Range` 的 start、end、total size 不正确。
- ETag 跨会话变化。
- ETag 缺失。
- 页面读取中途网络中断。
- 认证失效。
- 路径包含空格、中文和 URL 保留字符。
- 用户选择允许自签证书。

**ZIP/CBZ 场景：**

- ZIP64。
- Data Descriptor。
- UTF-8 filename flag。
- UTF-8 解码失败后的 GBK 文件名 fallback。
- 空压缩包。
- 没有支持的图片。
- 损坏 entry。
- 加密 ZIP。
- 分卷 ZIP。

**图片场景：**

- JPG。
- PNG。
- WebP。
- 超大图。
- 超长图。
- 损坏图片文件。

**步骤：**

- [ ] 添加面向用户的错误分类：网络、认证、压缩包不支持、页面损坏、服务器不支持。
- [ ] 为可重试页面失败添加重试按钮。
- [ ] 添加从在线 Range 模式回退到整本缓存模式的路径。
- [ ] 添加加密 ZIP 不支持提示。
- [ ] 添加分卷 ZIP 不支持提示。
- [ ] 添加 ZIP64 测试并启用 ZIP64 路径。
- [ ] 添加 UTF-8 和 GBK fallback 文件名解码测试。
- [ ] 添加诊断页面或可导出的诊断摘要。
- [ ] 添加缓存管理页面。
- [ ] 测试多个 WebDAV 服务。
- [ ] 运行 Android instrumentation smoke test。
- [ ] 提交：`feat: harden webdav and archive compatibility`。

**验收：**

- 预期不支持的格式会给出清晰提示。
- 损坏页面不会导致 Reader 崩溃。
- 网络失败可重试。
- Range fallback 可用。
- 常见 WebDAV 服务器有通过/失败记录。

## 阶段 8：发布准备

**目标：** 准备内部测试或公开测试版本，并让验证过程可重复。

**文件：**

- 创建：`README.md`
- 创建：`docs/testing/webdav-compatibility.md`
- 创建：`docs/testing/performance-checklist.md`
- 创建：`docs/release/internal-test.md`
- 修改：`app/build.gradle.kts`

**步骤：**

- [ ] 设置 app name、package name、min SDK、target SDK 和签名配置。
- [ ] 添加 release build type 和 shrinker rules。
- [ ] 记录支持格式。
- [ ] 记录不支持格式。
- [ ] 记录已测试 WebDAV 服务器。
- [ ] 对小、中、大三类漫画记录性能指标。
- [ ] 分别记录 Wi-Fi 和移动网络表现。
- [ ] 验证 clean install、添加账号、浏览、打开、阅读、关闭、重新打开、缓存清理。
- [ ] 构建 debug 和 release APK。
- [ ] 提交：`chore: prepare internal test release`。

**验收：**

- 内测包能安装到真实 Android 设备。
- clean install 后主 WebDAV 阅读流程可用。
- 已知限制已记录。
- 性能检查清单完整。

## 详细计划拆分

编码前按顺序创建这些细分实施计划：

1. `docs/superpowers/plans/2026-05-13-phase-0-project-bootstrap.md`
2. `docs/superpowers/plans/2026-05-13-phase-1-webdav-probe.md`
3. `docs/superpowers/plans/2026-05-13-phase-2-rust-cbz-core.md`
4. `docs/superpowers/plans/2026-05-13-phase-3-jni-local-reader.md`
5. `docs/superpowers/plans/2026-05-13-phase-4-whole-file-mvp.md`
6. `docs/superpowers/plans/2026-05-13-phase-5-remote-range-reader.md`
7. `docs/superpowers/plans/2026-05-13-phase-6-cache-prefetch-performance.md`
8. `docs/superpowers/plans/2026-05-13-phase-7-compatibility-hardening.md`
9. `docs/superpowers/plans/2026-05-13-phase-8-release-prep.md`

每个细分计划都要包含精确文件路径、失败测试、最小实现步骤、验证命令和提交步骤。

## 验证矩阵

阶段 5 存在后开始跑这张矩阵：

| 区域 | 必测场景 | 通过信号 |
| --- | --- | --- |
| WebDAV list | 普通目录、空目录、中文路径、带空格路径 | 条目显示正确 |
| WebDAV size | `HEAD` 成功、`HEAD` 失败后 PROPFIND fallback | 打开前能拿到 size |
| Range | 正确 `206`、错误 `Content-Range`、`200 OK` fallback | 模式选择正确 |
| CBZ parse | 普通 ZIP、嵌套目录、中文名、无图片 | page count 正确或错误清晰 |
| Compression | Store、Deflate | 页面能正确提取 |
| Cache | 首次打开、二次打开、回看页面 | 索引/页面缓存命中有日志 |
| Prefetch | 慢速翻页、快速滑动、反向翻页 | 有价值的请求优先执行 |
| Cancellation | 离开阅读器、切换漫画、快速跳页 | 过期任务停止或结果被丢弃 |
| Memory | 大图、长时间阅读 | 无明显 UI 卡顿或无上限增长 |

## 停止条件

出现以下情况时暂停实现并重新审视架构：

- 常见目标 WebDAV 服务器既不能提供 size，也不支持 Range，而整本下载 fallback 又不符合产品定位。
- 完成 Range 合并和页面缓存后，JNI 回调开销或内存拷贝仍主导页面加载时间。
- Rust session 生命周期在普通阅读导航中造成不可恢复 native crash。
- 页面缓存清理会破坏当前可见页或即将显示的页面。
- UI 主线程执行网络、ZIP 解析或阻塞 native 调用。

## 当前下一步

下一份最有价值的文档是阶段 0 详细计划。它应该创建 Android/Rust 工作区，锁定依赖版本，证明 native library 打包链路，并建立构建与测试命令。
