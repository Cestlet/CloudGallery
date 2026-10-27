package com.cloudgallery.app.data

import org.junit.Assert.*
import org.junit.Test

class XhsImageDimensionsTest {
    @org.junit.Test fun acceptsObservedHighResolutionDirectSourceCap() {
        org.junit.Assert.assertTrue(acceptableXhsSourceDimensions(2560, 2081, 3366, 2736, 1, true))
        org.junit.Assert.assertTrue(acceptableXhsSourceDimensions(2081, 2560, 3366, 2736, 6, true))
    }
    @org.junit.Test fun rejectsPreviewsCropsAndUnverifiedSizeReduction() {
        for ((w, h) in listOf(240 to 195, 1080 to 878, 2560 to 1500, 2048 to 300))
            org.junit.Assert.assertFalse(acceptableXhsSourceDimensions(w, h, 3366, 2736, 1, true))
        org.junit.Assert.assertFalse(acceptableXhsSourceDimensions(2560, 2081, 3366, 2736, 1, false))
        org.junit.Assert.assertFalse(acceptableXhsSourceDimensions(2081, 2560, 3366, 2736, 1, true))
    }

    @Test fun acceptsLargerPublicImageObservedOnEmulator() {
        assertTrue(sufficientXhsDimensions(2650, 3533, 1920, 2560))
    }
    @Test fun acceptsExactDimensions() {
        assertTrue(sufficientXhsDimensions(2838, 3840, 2838, 3840))
    }
    @Test fun rejectsSmallerRendition() {
        assertFalse(sufficientXhsDimensions(1440, 1920, 1920, 2560))
    }
    @Test fun rejectsOtherAspectRatioOrCrop() {
        assertFalse(sufficientXhsDimensions(3000, 3000, 1920, 2560))
    }
    @Test fun rejectsInvalidOrOversizedDimensions() {
        assertFalse(sufficientXhsDimensions(-1, -1, 1920, 2560))
        assertFalse(sufficientXhsDimensions(30000, 30000, 1920, 2560))
        assertFalse(sufficientXhsDimensions(100, 100, 0, 0))
    }
    @Test fun acceptsExplicitQuarterTurnButNeverGuessesRotation() {
        assertTrue(sufficientOrientedXhsDimensions(3840, 2838, 2838, 3840, 6))
        assertTrue(sufficientOrientedXhsDimensions(3840, 2838, 2838, 3840, 8))
        assertFalse(sufficientOrientedXhsDimensions(3840, 2838, 2838, 3840, 1))
        assertFalse(sufficientOrientedXhsDimensions(1920, 1440, 2838, 3840, 6))
        assertFalse(sufficientOrientedXhsDimensions(3000, 3000, 2838, 3840, 6))
    }
}
