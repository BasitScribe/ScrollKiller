package com.scrollkiller

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test — runs on a real device or emulator (has access to Android
 * APIs like Context). This trivial test only proves the `androidTest` source set
 * is wired up. Run with: ./gradlew connectedAndroidTest (needs a device/emulator).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.scrollkiller", appContext.packageName)
    }
}
