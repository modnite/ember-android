package org.hearth.ember

import org.hearth.ember.data.MediaPlan
import org.hearth.ember.data.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPlanTest {
    @Test fun aBigPhotoIsBroughtToTheLongSideAndKeepsItsShape() {
        assertEquals(1600 to 1200, MediaPlan.fit(4000, 3000, MediaPlan.photoEdge(Quality.Standard)))
        assertEquals(3000 to 4000, MediaPlan.fit(3000, 4000, MediaPlan.photoEdge(Quality.HD)).let { it })
        assertEquals(3072 to 4096, MediaPlan.fit(6000, 8000, MediaPlan.photoEdge(Quality.HD)))
    }

    @Test fun aSmallPhotoIsNeverEnlarged() {
        assertEquals(800 to 600, MediaPlan.fit(800, 600, 1600))
        assertEquals(1600 to 900, MediaPlan.fit(1600, 900, 1600))
    }

    @Test fun hdKeepsMoreThanStandard() {
        assertTrue(MediaPlan.photoEdge(Quality.HD) > MediaPlan.photoEdge(Quality.Standard))
        assertTrue(MediaPlan.photoJpeg(Quality.HD) > MediaPlan.photoJpeg(Quality.Standard))
        assertTrue(MediaPlan.videoBitrate(Quality.HD) > MediaPlan.videoBitrate(Quality.Standard))
    }

    @Test fun videoIsScaledByItsShortSideAndNeverEnlarged() {
        assertEquals(720, MediaPlan.videoHeight(1280 to 720, Quality.Standard)?.let { 720 } ?: 720)
        assertEquals(1280, MediaPlan.videoHeight(1080 to 1920, Quality.Standard)?.let { it })   // portrait 1080p to 720x1280
        assertEquals(720, MediaPlan.videoHeight(1920 to 1080, Quality.Standard))
        assertEquals(1080, MediaPlan.videoHeight(3840 to 2160, Quality.HD))
        assertNull(MediaPlan.videoHeight(1280 to 720, Quality.Standard))
        assertNull(MediaPlan.videoHeight(1920 to 1080, Quality.HD))
    }

    @Test fun theHeightIsEven() {
        assertEquals(0, (MediaPlan.videoHeight(1000 to 2001, Quality.Standard) ?: 2) % 2)
    }
}
