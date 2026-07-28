pluginManagement {
    repositories {
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        google()
        mavenCentral()
    }
}

rootProject.name = "MuBOX"
include(":app")
include(":core:model")
include(":core:diagnostics")
include(":nativebridge")
include(":webdav")
include(":ui")
include(":data")
include(":feature:directory-listing")
include(":feature:file-directory")
include(":feature:library")
include(":feature:reader")
include(":feature:video")
include(":feature:downloads")
include(":feature:settings")
include(":feature:video-library")
include(":feature:webdav")
