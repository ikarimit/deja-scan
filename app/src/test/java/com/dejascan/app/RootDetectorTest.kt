package com.dejascan.app

import com.dejascan.app.scan.RootDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootDetectorTest {

    @Test
    fun testHasTestKeys() {
        assertTrue(RootDetector.hasTestKeys("test-keys"))
        assertTrue(RootDetector.hasTestKeys("foo,test-keys,bar"))
        assertFalse(RootDetector.hasTestKeys("release-keys"))
        assertFalse(RootDetector.hasTestKeys(null))
        assertFalse(RootDetector.hasTestKeys(""))
    }

    @Test
    fun testHasSuBinary() {
        val foundPaths = mutableSetOf("/system/xbin/su")
        assertTrue(RootDetector.hasSuBinary { it in foundPaths })

        val magiskPaths = mutableSetOf("/data/adb/magisk")
        assertTrue(RootDetector.hasSuBinary { it in magiskPaths })

        assertFalse(RootDetector.hasSuBinary { false })
    }

    @Test
    fun testHasSuInPath() {
        val pathEnv = "/sbin:/vendor/bin:/system/bin"
        assertTrue(RootDetector.hasSuInPath(pathEnv) { it == "/system/bin/su" })
        assertFalse(RootDetector.hasSuInPath(pathEnv) { false })
        assertFalse(RootDetector.hasSuInPath(null) { true })
        assertFalse(RootDetector.hasSuInPath("") { true })
    }

    @Test
    fun testCanExecuteSu() {
        assertTrue(RootDetector.canExecuteSu { it == "su" })
        assertFalse(RootDetector.canExecuteSu { false })
    }

    @Test
    fun testCheckRootIntegration() {
        val notRooted = RootDetector.checkRoot(
            buildTags = "release-keys",
            pathEnv = "/usr/bin:/bin",
            fileChecker = { false },
            commandChecker = { false },
        )
        assertFalse(notRooted)

        val rootedByTags = RootDetector.checkRoot(
            buildTags = "test-keys",
            pathEnv = "/usr/bin:/bin",
            fileChecker = { false },
            commandChecker = { false },
        )
        assertTrue(rootedByTags)

        val rootedByFile = RootDetector.checkRoot(
            buildTags = "release-keys",
            pathEnv = "/usr/bin:/bin",
            fileChecker = { it == "/system/bin/su" },
            commandChecker = { false },
        )
        assertTrue(rootedByFile)

        val rootedByPath = RootDetector.checkRoot(
            buildTags = "release-keys",
            pathEnv = "/custom/bin:/bin",
            fileChecker = { it == "/custom/bin/su" },
            commandChecker = { false },
        )
        assertTrue(rootedByPath)

        val rootedByCommand = RootDetector.checkRoot(
            buildTags = "release-keys",
            pathEnv = "/usr/bin:/bin",
            fileChecker = { false },
            commandChecker = { it == "su" },
        )
        assertTrue(rootedByCommand)
    }
}
