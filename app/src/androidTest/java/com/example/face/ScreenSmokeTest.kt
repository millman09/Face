package com.example.face

import android.app.Activity
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opens every screen and lets its background work run for a few seconds.
 * A crash anywhere (like the Deep search one) fails the test and the CI build.
 */
@RunWith(AndroidJUnit4::class)
class ScreenSmokeTest {

    private fun <T : Activity> open(type: Class<T>) {
        val intent = Intent(ApplicationProvider.getApplicationContext(), type)
        ActivityScenario.launch<T>(intent).use { scenario ->
            Thread.sleep(3000)
            assertNotEquals(Lifecycle.State.DESTROYED, scenario.state)
        }
    }

    @Test fun mainScreenOpens() = open(MainActivity::class.java)
    @Test fun deepSearchOpens() = open(WebScanActivity::class.java)
    @Test fun exposureCheckOpens() = open(ExposureCheckActivity::class.java)
    @Test fun matchScreenOpens() = open(IdentityMatchActivity::class.java)
    @Test fun savedImagesOpens() = open(SavedImagesActivity::class.java)
    @Test fun privateViewerOpens() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), PrivateViewerActivity::class.java)
            .putExtra("com.example.face.EXTRA_PRIVATE_URL", "https://example.com/")
        ActivityScenario.launch<PrivateViewerActivity>(intent).use { scenario ->
            Thread.sleep(3000)
            assertNotEquals(Lifecycle.State.DESTROYED, scenario.state)
        }
    }
}
