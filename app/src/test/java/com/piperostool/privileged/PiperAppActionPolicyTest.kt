package com.piperostool.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PiperAppActionPolicyTest {
    @Test
    fun buildsOnlyTheExpectedPackageActions() {
        assertEquals(
            "pm disable-user --user current 'com.example.app'",
            PiperAppActionPolicy.command(PiperAppActionPolicy.DISABLE_USER_APP, "com.example.app")
        )
        assertEquals(
            "pm enable --user current 'com.example.app'",
            PiperAppActionPolicy.command(PiperAppActionPolicy.ENABLE_USER_APP, "com.example.app")
        )
        assertEquals(
            "pm uninstall --user current 'com.example.app'",
            PiperAppActionPolicy.command(PiperAppActionPolicy.UNINSTALL_USER_APP, "com.example.app")
        )
        assertEquals(
            "am start --user current -n 'com.example.app/com.example.app.HiddenActivity'",
            PiperAppActionPolicy.command(
                PiperAppActionPolicy.LAUNCH_ACTIVITY,
                "com.example.app",
                "com.example.app.HiddenActivity"
            )
        )
    }

    @Test
    fun rejectsShellSyntaxAndUnknownActions() {
        assertThrows(IllegalArgumentException::class.java) {
            PiperAppActionPolicy.command(PiperAppActionPolicy.DISABLE_USER_APP, "com.example;id")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PiperAppActionPolicy.command("arbitrary", "com.example.app")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PiperAppActionPolicy.command(
                PiperAppActionPolicy.LAUNCH_ACTIVITY,
                "com.example.app",
                "com.example.app.Activity;id"
            )
        }
    }

    @Test
    fun explainsOemBlockedEnableWithoutLeakingStackTrace() {
        val message = PiperAppActionPolicy.failureMessage(
            PiperAppActionPolicy.ENABLE_USER_APP,
            "com.google.android.projection.gearhead",
            "Exception occurred while executing 'enable': java.lang.SecurityException: " +
                "Shell cannot change component state for null to 1\n\tat com.android.server.pm.PackageManagerService"
        )

        assertEquals(true, message.contains("ColorOS"))
        assertEquals(true, message.contains("ADB vẫn kết nối"))
        assertEquals(false, message.contains("PackageManagerService"))
        assertEquals(false, message.contains("SecurityException"))
    }

    @Test
    fun reducesOtherFailuresToTheActionableFirstLine() {
        assertEquals(
            "Failure [not allowed]",
            PiperAppActionPolicy.failureMessage(
                PiperAppActionPolicy.DISABLE_USER_APP,
                "com.example.app",
                "Failure [not allowed]\n\tat com.android.server.pm.PackageManagerService"
            )
        )
    }
}
