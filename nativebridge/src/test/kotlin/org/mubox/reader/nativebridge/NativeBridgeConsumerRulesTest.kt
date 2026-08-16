package org.mubox.reader.nativebridge

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeBridgeConsumerRulesTest {
    @Test
    fun modulePublishesJniKeepRulesToConsumers() {
        val buildScript = File(repositoryRoot, "nativebridge/build.gradle.kts").readText()
        val consumerRules = File(repositoryRoot, "nativebridge/consumer-rules.pro").readText()
        val appRules = File(repositoryRoot, "app/proguard-rules.pro").readText()

        assertTrue(buildScript.contains("consumerProguardFiles(\"consumer-rules.pro\")"))
        assertTrue(consumerRules.contains("org.mubox.reader.nativebridge.ComicNative"))
        assertTrue(consumerRules.contains("native <methods>;"))
        assertTrue(consumerRules.contains("-keepnames class org.mubox.reader.nativebridge.RangeProviderRegistry"))
        assertTrue(
            consumerRules.contains(
                "public static int fetchRangeIntoV1(long, long, long, long, java.nio.ByteBuffer);",
            ),
        )
        assertTrue(consumerRules.contains("public static void cancelRangeFetchV1(long, long);"))
        assertFalse(consumerRules.contains("readRange"))
        assertFalse(consumerRules.contains("readCachedRange"))
        assertFalse(consumerRules.contains("cancelRangeRequestsV1"))
        assertFalse(appRules.contains("org.mubox.reader.nativebridge"))
    }

    private val repositoryRoot: File by lazy {
        generateSequence(File(".").absoluteFile.normalize()) { directory -> directory.parentFile }
            .firstOrNull { directory -> File(directory, "settings.gradle.kts").isFile }
            ?: error("Could not locate repository root from ${File(".").absolutePath}")
    }
}
