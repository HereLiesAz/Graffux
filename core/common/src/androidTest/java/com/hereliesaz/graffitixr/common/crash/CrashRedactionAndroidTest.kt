package com.hereliesaz.graffitixr.common.crash

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs against Android's ICU regex implementation, which differs from the desktop JVM engine.
 * A malformed eager redaction pattern must fail here rather than on application startup.
 */
@RunWith(AndroidJUnit4::class)
class CrashRedactionAndroidTest {
    @Test fun macRedactionCompilesAndPreservesBoundariesOnAndroid() {
        assertEquals(
            "bssid [REDACTED_MAC] seen",
            CrashReporter.redactSensitive("bssid 3c:5a:b4:12:9f:ee seen"),
        )
        assertEquals(
            "bssid [REDACTED_MAC] seen",
            CrashReporter.redactSensitive("bssid 3C-5A-B4-12-9F-EE seen"),
        )
        val identifier = "prefix_3c:5a:b4:12:9f:ee"
        assertEquals(identifier, CrashReporter.redactSensitive(identifier))
    }
}
