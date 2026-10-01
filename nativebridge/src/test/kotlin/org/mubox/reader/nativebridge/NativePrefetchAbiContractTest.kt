package org.mubox.reader.nativebridge

import java.io.File
import java.nio.ByteBuffer
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePrefetchAbiContractTest {
    @Test
    fun v1MethodKeepsItsInstanceJniDescriptor() {
        val method = ComicNative::class.java.getDeclaredMethod(
            "reconcilePrefetchPlanV1",
            java.lang.Long.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            java.lang.Long.TYPE,
            LongArray::class.java,
            LongArray::class.java,
        )

        assertTrue(Modifier.isNative(method.modifiers))
        assertFalse(Modifier.isStatic(method.modifiers))
        assertEquals(LongArray::class.java, method.returnType)
    }

    @Test
    fun v2MethodAddsBackwardWindowWithoutChangingV1() {
        val method = ComicNative::class.java.getDeclaredMethod(
            "reconcilePrefetchPlanV2",
            java.lang.Long.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            java.lang.Long.TYPE,
            LongArray::class.java,
            LongArray::class.java,
        )

        assertTrue(Modifier.isNative(method.modifiers))
        assertFalse(Modifier.isStatic(method.modifiers))
        assertEquals(LongArray::class.java, method.returnType)
    }

    @Test
    fun viewportAndPlannedRangeDescriptorsPassBothWindows() {
        val parameters = arrayOf(
            java.lang.Long.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
        )
        val viewport = ComicNative::class.java.getDeclaredMethod("updateViewport", *parameters)
        val planned = ComicNative::class.java.getDeclaredMethod("plannedRanges", *parameters)

        assertTrue(Modifier.isNative(viewport.modifiers))
        assertTrue(Modifier.isNative(planned.modifiers))
        assertEquals(Integer.TYPE, viewport.returnType)
        assertEquals(String::class.java, planned.returnType)
    }

    @Test
    fun nativeRangeBundleMethodsKeepVersionedInstanceJniDescriptors() {
        val open = ComicNative::class.java.getDeclaredMethod(
            "openRemoteCachedV1",
            java.lang.Long.TYPE,
            java.lang.Long.TYPE,
            String::class.java,
            String::class.java,
            String::class.java,
        )
        val prefetch = ComicNative::class.java.getDeclaredMethod(
            "prefetchRemoteRangeV1",
            java.lang.Long.TYPE,
            java.lang.Long.TYPE,
            java.lang.Long.TYPE,
            Integer.TYPE,
            LongArray::class.java,
        )
        val cancel = ComicNative::class.java.getDeclaredMethod(
            "cancelRemoteIoV1",
            java.lang.Long.TYPE,
        )

        listOf(open, prefetch, cancel).forEach { method ->
            assertTrue(Modifier.isNative(method.modifiers))
            assertFalse(Modifier.isStatic(method.modifiers))
        }
        assertEquals(java.lang.Long.TYPE, open.returnType)
        assertEquals(Integer.TYPE, prefetch.returnType)
        assertEquals(Void.TYPE, cancel.returnType)
    }

    @Test
    fun rangeProviderV1CallbacksKeepStaticJniDescriptors() {
        val fetch = RangeProviderRegistry::class.java.getDeclaredMethod(
            "fetchRangeIntoV1",
            java.lang.Long.TYPE,
            java.lang.Long.TYPE,
            java.lang.Long.TYPE,
            java.lang.Long.TYPE,
            ByteBuffer::class.java,
        )
        val cancel = RangeProviderRegistry::class.java.getDeclaredMethod(
            "cancelRangeFetchV1",
            java.lang.Long.TYPE,
            java.lang.Long.TYPE,
        )

        assertTrue(Modifier.isStatic(fetch.modifiers))
        assertTrue(Modifier.isStatic(cancel.modifiers))
        assertEquals(Integer.TYPE, fetch.returnType)
        assertEquals(Void.TYPE, cancel.returnType)
    }

    @Test
    fun rustRegistersTheVersionedAbiWithoutNameBasedCompatibilitySymbols() {
        val source = File(repositoryRoot, "comic-core/src/ffi/jni.rs").readText()

        listOf(
            "\"reconcilePrefetchPlanV1\"",
            "\"reconcilePrefetchPlanV2\"",
            "\"openRemoteCachedV1\"",
            "\"prefetchRemoteRangeV1\"",
            "\"cancelRemoteIoV1\"",
        ).forEach { method ->
            assertTrue("Missing registered JNI method: $method", source.contains(method))
        }
        assertFalse(source.contains("Java_org_mubox_reader_nativebridge_ComicNative_"))
        assertFalse(source.contains("\"openRemote\""))
        assertTrue(source.contains("\"(JIIII)I\""))
        assertTrue(source.contains("\"(JIIII)Ljava/lang/String;\""))
        assertTrue(source.contains("\"(JIIIIJ[J[J)[J\""))
    }

    private val repositoryRoot: File by lazy {
        generateSequence(File(".").absoluteFile.normalize()) { directory -> directory.parentFile }
            .firstOrNull { directory -> File(directory, "settings.gradle.kts").isFile }
            ?: error("Could not locate repository root from ${File(".").absolutePath}")
    }
}
