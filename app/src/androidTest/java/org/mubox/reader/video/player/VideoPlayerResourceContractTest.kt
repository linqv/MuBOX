package org.mubox.reader.video.player

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mubox.reader.video.R

@RunWith(AndroidJUnit4::class)
class VideoPlayerResourceContractTest {
    @Test
    fun packagedMpvLayoutInflatesTheRealSurfaceViewWithParentLayoutParams() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val parent = FrameLayout(context)

        val inflated = LayoutInflater.from(context)
            .inflate(R.layout.view_mubox_mpv, parent, false)
        val factoryView = MuBoxMpvView.create(context)

        assertTrue(inflated is MuBoxMpvView)
        assertNull(inflated.parent)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, inflated.layoutParams.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, inflated.layoutParams.height)
        assertNull(factoryView.parent)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, factoryView.layoutParams.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, factoryView.layoutParams.height)
    }

    @Test
    fun packagedManifestLeavesPlayerOrientationToTheRuntimeSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val activityInfo = context.packageManager.getActivityInfo(
            ComponentName(context, VideoPlayerActivity::class.java),
            PackageManager.GET_META_DATA,
        )

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activityInfo.screenOrientation)
        assertTrue(activityInfo.configChanges and ActivityInfo.CONFIG_ORIENTATION != 0)
        assertTrue(activityInfo.configChanges and ActivityInfo.CONFIG_SCREEN_SIZE != 0)
    }
}
