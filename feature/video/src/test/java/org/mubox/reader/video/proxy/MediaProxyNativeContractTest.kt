package org.mubox.reader.video.proxy

import java.io.File
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaProxyNativeContractTest {
    @Test
    fun v1MethodsKeepTheirInstanceJniDescriptorsWithoutInitializingNativeObject() {
        val nativeClass = Class.forName(
            "org.mubox.reader.video.proxy.MediaProxyNative",
            false,
            javaClass.classLoader,
        )
        val methods = listOf(
            nativeClass.getDeclaredMethod(
                "proxyCreateV1",
                java.lang.Long.TYPE,
                Integer.TYPE,
                Integer.TYPE,
                Integer.TYPE,
                Integer.TYPE,
                Integer.TYPE,
                Integer.TYPE,
            ) to java.lang.Long.TYPE,
            nativeClass.getDeclaredMethod("proxyStartV1", java.lang.Long.TYPE) to Integer.TYPE,
            nativeClass.getDeclaredMethod("proxyCloseV1", java.lang.Long.TYPE) to Void.TYPE,
            nativeClass.getDeclaredMethod(
                "streamCreateV1",
                java.lang.Long.TYPE,
                MediaProxyNetworkBridge::class.java,
                String::class.java,
                java.lang.Long.TYPE,
                String::class.java,
                java.lang.Boolean.TYPE,
                Integer.TYPE,
            ) to java.lang.Long.TYPE,
            nativeClass.getDeclaredMethod("streamCloseV1", java.lang.Long.TYPE) to java.lang.Boolean.TYPE,
            nativeClass.getDeclaredMethod("streamStatsV1", java.lang.Long.TYPE) to String::class.java,
            nativeClass.getDeclaredMethod("lastErrorMessageV1") to String::class.java,
        )

        methods.forEach { (method, returnType) ->
            assertTrue(Modifier.isNative(method.modifiers))
            assertFalse(Modifier.isStatic(method.modifiers))
            assertEquals(returnType, method.returnType)
        }
    }

    @Test
    fun bridgeCallbacksKeepExactPublicSignatures() {
        val bridgeClass = MediaProxyNetworkBridge::class.java

        assertEquals(LongArray::class.java, bridgeClass.getDeclaredMethod("headV1").returnType)
        assertEquals(
            LongArray::class.java,
            bridgeClass.getDeclaredMethod(
                "openFetchV1",
                java.lang.Long.TYPE,
                java.lang.Long.TYPE,
                java.lang.Long.TYPE,
                Integer.TYPE,
            ).returnType,
        )
        assertEquals(
            Integer.TYPE,
            bridgeClass.getDeclaredMethod(
                "readFetchIntoV1",
                java.lang.Long.TYPE,
                ByteBuffer::class.java,
            ).returnType,
        )
        assertEquals(
            Void.TYPE,
            bridgeClass.getDeclaredMethod("cancelFetchV1", java.lang.Long.TYPE).returnType,
        )
        assertEquals(
            Void.TYPE,
            bridgeClass.getDeclaredMethod("closeFetchV1", java.lang.Long.TYPE).returnType,
        )
    }

    @Test
    fun modulePublishesKeepRulesForNativeAndCallbackMethods() {
        val buildScript = File(repositoryRoot, "feature/video/build.gradle.kts").readText()
        val rules = File(repositoryRoot, "feature/video/consumer-rules.pro").readText()

        assertTrue(buildScript.contains("consumerProguardFiles(\"consumer-rules.pro\")"))
        assertTrue(rules.contains("org.mubox.reader.video.proxy.MediaProxyNative"))
        assertTrue(rules.contains("native <methods>;"))
        assertTrue(rules.contains("public long[] headV1();"))
        assertTrue(rules.contains("public int readFetchIntoV1(long, java.nio.ByteBuffer);"))
        assertTrue(rules.contains("public void cancelFetchV1(long);"))
        assertTrue(rules.contains("public void closeFetchV1(long);"))
    }

    private val repositoryRoot: File by lazy {
        generateSequence(File(".").absoluteFile.normalize()) { directory -> directory.parentFile }
            .firstOrNull { directory -> File(directory, "settings.gradle.kts").isFile }
            ?: error("Could not locate repository root from ${File(".").absolutePath}")
    }
}
