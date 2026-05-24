package com.example.comicdav.security

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackupPolicySourceTest {
    @Test
    fun manifestDisablesBackup() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(
            "allowBackup must be false",
            manifest.contains("""android:allowBackup="false"""")
        )
    }
}
