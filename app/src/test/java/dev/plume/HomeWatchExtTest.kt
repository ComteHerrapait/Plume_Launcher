package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeWatchExtTest {
    @Test fun googleTvSetupScreensAreLeftAlone() {
        assertNull(HomeWatchExt().opened(HomeWatch.GOOGLE_TV, "${HomeWatch.GOOGLE_TV}.setup.SetupActivity", 1_000))
    }

    @Test fun googleTvHomeStillJumps() {
        assertEquals(0L, HomeWatchExt().opened(HomeWatch.GOOGLE_TV, "${HomeWatch.GOOGLE_TV}.home.HomeActivity", 1_000))
    }
}
