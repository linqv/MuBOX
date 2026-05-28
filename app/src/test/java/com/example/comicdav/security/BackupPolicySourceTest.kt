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

    @Test
    fun manifestUsesExplicitAndroid12DataExtractionRules() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val rules = File("src/main/res/xml/data_extraction_rules.xml").readText()

        assertTrue(
            "Android 12+ backup and transfer behavior must be explicit",
            manifest.contains("""android:dataExtractionRules="@xml/data_extraction_rules""""),
        )
        assertTrue(rules.contains("<cloud-backup"))
        assertTrue(rules.contains("<device-transfer"))
        assertTrue(rules.contains("""domain="root""""))
        assertTrue(rules.contains("""path=".""""))
    }

    @Test
    fun manifestUsesLegacyBackupRulesForPreAndroid12Devices() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val rules = File("src/main/res/xml/full_backup_content.xml").readText()

        assertTrue(
            "Pre-Android 12 backup behavior must stay explicit when dataExtractionRules is present",
            manifest.contains("""android:fullBackupContent="@xml/full_backup_content""""),
        )
        assertTrue(rules.contains("<exclude"))
        assertTrue(rules.contains("""domain="root""""))
        assertTrue(rules.contains("""path=".""""))
    }
}
