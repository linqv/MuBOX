# MuBOX

MuBOX 是一款 Android 本地媒体阅读器，支持本地文件夹和 WebDAV 远程库。UI 与网络层使用 Kotlin/Jetpack Compose 实现，CBZ/ZIP 解析与页面提取由 Rust 原生库（`comic-core`）完成。

Android 包名：`org.mubox.reader`

## 功能

### 漫画 / 文档阅读
- 支持格式：CBZ、ZIP、CB7、7Z、CBT/TAR、PDF、EPUB、MOBI、AZW3
- 基于 Rust JNI 库的高性能 ZIP/7Z 解压与页面提取
- PDF / EPUB / MOBI / AZW3 由 MuPDF（fitz）渲染
- 支持捏合缩放、翻页手势，阅读进度自动保存

### 视频 / 音频播放
- 基于 libmpv 的全格式视频播放器，支持 MP4、MKV、WebM、AVI、MOV 等主流格式
- 支持音频：MP3、FLAC、AAC、OGG、Opus 等
- 支持外挂字幕：SRT、ASS/SSA、VTT、SUB
- 自动检测同目录下的同名字幕文件（sidecar subtitle）

### 媒体库管理
- 本地文件夹浏览：通过 Android SAF 授权访问任意目录
- WebDAV 远程库：支持添加多个 WebDAV 账户，凭据使用 Android Keystore 加密存储
- 远程文件按需下载缓存，支持 HTTP Range 请求流式读取 CBZ，无需完整下载即可翻页
- 下载记录管理与缓存清理

### 其他
- Material You 动态主题（Jetpack Compose Material 3）
- 阅读方向、缩放模式等个性化设置
- 最低支持 Android 8.0（API 26）

## 环境要求

| 工具 | 版本 |
|------|------|
| JDK | 17 |
| Android SDK | compileSdk 36，targetSdk 36，minSdk 26 |
| Android NDK | 任意已安装版本（自动检测最新） |
| Rust 工具链 | stable，需安装 `aarch64-linux-android` / `x86_64-linux-android` target |

安装 Rust Android target：

```bash
rustup target add aarch64-linux-android x86_64-linux-android
```

## 关键依赖

- WebDAV 网络层使用 OkHttp 5.3.2，通过 `okhttp-bom` 统一约束 `okhttp` 与测试用 `mockwebserver` 版本。
- Gradle 会按 OkHttp 5 的 module metadata 为 Android 构建解析 Android 变体，运行时实际使用 `okhttp-android`。
- 当前保留旧坐标 `com.squareup.okhttp3:mockwebserver`，以匹配现有 JUnit 4 测试；OkHttp 5 的 `mockwebserver3` 可作为后续测试重构目标。
- OkHttp 5 的 Kotlin API 将 `Response.body` 暴露为非空类型，WebDAV client 不再做旧版的空 body 分支。

## 构建

### 调试包

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:assembleDebug
```

### 优化 ARM64 发布包

先在项目根目录创建 `keystore.properties`（不要提交此文件）：

```properties
storeFile=/absolute/path/to/mubox-release.jks
storePassword=your-store-password
keyAlias=mubox
keyPassword=your-key-password
```

也可以通过环境变量传入：`MUBOX_RELEASE_STORE_FILE`、`MUBOX_RELEASE_STORE_PASSWORD`、`MUBOX_RELEASE_KEY_ALIAS`、`MUBOX_RELEASE_KEY_PASSWORD`。

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:assembleRelease -PtargetAbi=arm64-v8a
```

未配置签名时构建会直接报错，不会产生未签名 APK。

### 支持的 ABI

- `arm64-v8a`
- `x86_64`

使用 `-PtargetAbi=<abi>` 只构建单一 ABI。

## 测试 Rust 核心

```bash
cd comic-core
cargo test
```

## 发布准备

发布 APK 前请阅读 `RELEASE.md`。

## 致谢

MuBOX 的视频播放器部分在架构和实现上大量参考了 **[mpvEx](https://github.com/mpvExProject/mpvEx)** 项目。mpvEx 是一个基于 libmpv 的开源 Android 视频播放器，其对 mpv 的 Android JNI 封装、播放控制逻辑以及字幕处理方案为本项目提供了重要参考，在此向 mpvEx 的全体贡献者表示诚挚感谢。

## 许可证

MuBOX 以 GNU 通用公共许可证第 3 版或更高版本（GPLv3-or-later）发布。详见 `LICENSE` 与 `NOTICE`。
