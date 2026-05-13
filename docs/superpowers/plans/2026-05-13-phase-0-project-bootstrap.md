# Phase 0 Project Bootstrap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the smallest Android + Rust workspace that proves Gradle, Compose, Cargo, JNI packaging, and local tests work.

**Architecture:** The Android app owns UI and loads `libcomic_core.so`. The Rust crate exposes a minimal C ABI smoke function first, then later phases expand it into the CBZ engine. Gradle builds the Rust shared library through a small `Exec` task and packages ABI outputs from `app/src/main/jniLibs`.

**Tech Stack:** Kotlin, Android Gradle Plugin, Jetpack Compose, Cargo, Rust cdylib, JUnit, Rust tests.

---

## File Structure

- Create: `/home/lin/webcomic/settings.gradle.kts` - Gradle plugin management and module registration.
- Create: `/home/lin/webcomic/build.gradle.kts` - top-level Gradle conventions.
- Create: `/home/lin/webcomic/app/build.gradle.kts` - Android app module, Compose, and Rust build tasks.
- Create: `/home/lin/webcomic/app/src/main/AndroidManifest.xml` - app entry point.
- Create: `/home/lin/webcomic/app/src/main/java/com/example/comicdav/MainActivity.kt` - minimal Compose shell.
- Create: `/home/lin/webcomic/comic-core/Cargo.toml` - Rust crate metadata and cdylib config.
- Create: `/home/lin/webcomic/comic-core/src/lib.rs` - smoke function and unit test.
- Create: `/home/lin/webcomic/.gitignore` - build outputs, IDE files, local secrets, worktrees.
- Create: `/home/lin/webcomic/README.md` - bootstrap build and test commands.

## Task 1: Restore Git Baseline

- [ ] **Step 1: Inspect repository root**

Run: `ls -la /home/lin/webcomic`

Expected: directory exists and either has no `.git` or has a broken `.git`.

- [ ] **Step 2: Initialize Git when no valid repository exists**

Run: `git -C /home/lin/webcomic status --short --branch`

Expected when broken: `fatal: not a git repository`.

Run: `git -C /home/lin/webcomic init`

Expected: Git creates `/home/lin/webcomic/.git`.

- [ ] **Step 3: Create ignore rules before any worktree use**

Create `/home/lin/webcomic/.gitignore` with:

```gitignore
.gradle/
build/
**/build/
.idea/
*.iml
local.properties
.DS_Store
.worktrees/
worktrees/
app/src/main/jniLibs/
comic-core/target/
```

- [ ] **Step 4: Commit Git bootstrap**

Run:

```bash
git -C /home/lin/webcomic add .gitignore
git -C /home/lin/webcomic commit -m "chore: initialize repository"
```

Expected: commit succeeds. If Git user identity is missing, configure repository-local `user.name` and `user.email` before retrying.

## Task 2: Create Gradle Workspace

- [ ] **Step 1: Write `settings.gradle.kts`**

Create `/home/lin/webcomic/settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ComicDav"
include(":app")
```

- [ ] **Step 2: Write top-level `build.gradle.kts`**

Create `/home/lin/webcomic/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
```

- [ ] **Step 3: Write `app/build.gradle.kts`**

Create `/home/lin/webcomic/app/build.gradle.kts`:

```kotlin
import org.gradle.api.tasks.Exec

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.comicdav"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.comicdav"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

val rustTargets = mapOf(
    "aarch64-linux-android" to "arm64-v8a",
    "x86_64-linux-android" to "x86_64",
)

tasks.register<Exec>("buildRustDebug") {
    workingDir = file("../comic-core")
    commandLine("cargo", "build")
}

tasks.register("syncRustDebugLibs") {
    dependsOn("buildRustDebug")
    doLast {
        val source = file("../comic-core/target/debug/libcomic_core.so")
        rustTargets.values.forEach { abi ->
            val outDir = file("src/main/jniLibs/$abi")
            outDir.mkdirs()
            source.copyTo(outDir.resolve("libcomic_core.so"), overwrite = true)
        }
    }
}

tasks.named("preBuild") {
    dependsOn("syncRustDebugLibs")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
```

- [ ] **Step 4: Verify Gradle files parse**

Run: `./gradlew projects`

Expected: output lists root project `ComicDav` and project `:app`.

## Task 3: Create Minimal Android App

- [ ] **Step 1: Write manifest**

Create `/home/lin/webcomic/app/src/main/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:allowBackup="true"
        android:label="ComicDav"
        android:supportsRtl="true"
        android:theme="@style/Theme.ComicDav">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 2: Add theme resources**

Create `/home/lin/webcomic/app/src/main/res/values/styles.xml`:

```xml
<resources>
    <style name="Theme.ComicDav" parent="android:style/Theme.Material.Light.NoActionBar" />
</resources>
```

- [ ] **Step 3: Add MainActivity**

Create `/home/lin/webcomic/app/src/main/java/com/example/comicdav/MainActivity.kt`:

```kotlin
package com.example.comicdav

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ComicDavApp() }
    }
}

@Composable
fun ComicDavApp() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(text = "ComicDav")
                Text(text = "WebDAV comic reader bootstrap")
            }
        }
    }
}
```

- [ ] **Step 4: Build debug APK**

Run: `./gradlew :app:assembleDebug`

Expected: task `:app:assembleDebug` succeeds and writes `app/build/outputs/apk/debug/app-debug.apk`.

## Task 4: Create Rust Crate

- [ ] **Step 1: Write Cargo manifest**

Create `/home/lin/webcomic/comic-core/Cargo.toml`:

```toml
[package]
name = "comic-core"
version = "0.1.0"
edition = "2021"

[lib]
name = "comic_core"
crate-type = ["cdylib", "rlib"]

[dependencies]

[dev-dependencies]
```

- [ ] **Step 2: Write smoke library**

Create `/home/lin/webcomic/comic-core/src/lib.rs`:

```rust
#[no_mangle]
pub extern "C" fn comic_core_smoke_value() -> i32 {
    42
}

#[cfg(test)]
mod tests {
    use super::comic_core_smoke_value;

    #[test]
    fn smoke_value_is_stable() {
        assert_eq!(comic_core_smoke_value(), 42);
    }
}
```

- [ ] **Step 3: Run Rust tests**

Run: `cargo test` from `/home/lin/webcomic/comic-core`

Expected: `smoke_value_is_stable` passes.

## Task 5: Document Bootstrap Commands

- [ ] **Step 1: Write README**

Create `/home/lin/webcomic/README.md`:

```markdown
# ComicDav

Android comic reader for WebDAV libraries. Android/Kotlin owns UI and networking. Rust owns CBZ/ZIP parsing and page extraction.

## Build

```bash
./gradlew :app:assembleDebug
```

## Test Rust Core

```bash
cd comic-core
cargo test
```

## Supported ABI Targets

- arm64-v8a
- x86_64
```

- [ ] **Step 2: Commit phase 0**

Run:

```bash
git -C /home/lin/webcomic add settings.gradle.kts build.gradle.kts app comic-core README.md .gitignore
git -C /home/lin/webcomic commit -m "chore: bootstrap android rust workspace"
```

Expected: commit succeeds after Gradle and Cargo verification pass.

## Verification

- [ ] Run `git -C /home/lin/webcomic status --short --branch`.
- [ ] Run `./gradlew :app:assembleDebug` from `/home/lin/webcomic`.
- [ ] Run `cargo test` from `/home/lin/webcomic/comic-core`.
- [ ] Confirm `README.md` contains the build and test commands.
