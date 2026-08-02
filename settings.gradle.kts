pluginManagement {
    includeBuild("build-logic")

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
        maven {
            name = "vendoredAndroid"
            url = uri(rootDir.resolve("third_party/android"))
            metadataSources {
                mavenPom()
                artifact()
            }
        }
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
include(":ui:directory-listing")
include(":data")
include(":feature:file-directory")
include(":feature:home")
include(":feature:library")
include(":feature:reader")
include(":feature:video")
include(":feature:downloads")
include(":feature:settings")
include(":feature:video-library")
include(":feature:webdav")
include(":test-support")
