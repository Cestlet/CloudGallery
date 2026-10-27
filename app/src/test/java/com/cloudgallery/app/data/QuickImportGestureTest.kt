package com.cloudgallery.app.data

import org.junit.Assert.*
import org.junit.Test

class QuickImportGestureTest {
    @Test fun eitherOrderAndBoundaryAreAccepted() {
        for ((first, second) in listOf(24 to 25, 25 to 24)) {
            val chord = VolumeChord()
            assertFalse(chord.accept(first, true, 1000))
            assertTrue(chord.accept(second, true, 1250))
        }
    }
    @Test fun sequentialPressesAreNotAChord() {
        val chord = VolumeChord()
        chord.accept(24, true, 1000); chord.accept(24, false, 1050)
        assertFalse(chord.accept(25, true, 1100))
    }
    @Test fun staleHeldKeyAndRepeatAreIgnored() {
        val chord = VolumeChord()
        chord.accept(24, true, 1000)
        assertFalse(chord.accept(24, true, 1100, repeated = true))
        assertFalse(chord.accept(25, true, 1251))
    }
    @Test fun bothKeysMustBeReleasedBeforeNextTrigger() {
        val chord = VolumeChord()
        chord.accept(24, true, 1000); assertTrue(chord.accept(25, true, 1100))
        chord.accept(25, false, 1120)
        assertFalse(chord.accept(25, true, 1140))
        chord.accept(24, false, 1160); chord.accept(25, false, 1170)
        chord.accept(25, true, 1200); assertTrue(chord.accept(24, true, 1210))
    }
    @Test fun leavingSupportedAppResetsHeldKeys() {
        val chord = VolumeChord()
        chord.accept(24, true, 1000); chord.reset()
        assertFalse(chord.accept(25, true, 1100))
        assertFalse(chord.accept(26, true, 1110))
    }
    @Test fun exactImageDescriptionProvidesCurrentPage() {
        assertEquals(XhsImagePage(4, 8), xhsImagePage("图片,第4张,共8张,双指左划或右划即可查看更多内容"))
        assertEquals(XhsImagePage(1, 1), xhsImagePage("图片，第1张，共1张"))
        for (text in listOf("4/8", "视频,第1张,共2张", "图片,第0张,共2张", "图片,第3张,共2张", "图片,第1张,共0张"))
            assertNull(xhsImagePage(text))
    }
    @Test fun systemVolumeOverlayAllowsOnlyForegroundXhs() {
        assertTrue(allowsXhsForeground("com.android.systemui", "com.xingin.xhs"))
        assertTrue(allowsXhsForeground(null, "com.xingin.xhs"))
        assertFalse(allowsXhsForeground("com.android.systemui", "com.tencent.mm"))
        assertFalse(allowsXhsForeground("com.tencent.mm", "com.xingin.xhs"))
        assertFalse(allowsXhsForeground("unknown.overlay", "com.xingin.xhs"))
        assertFalse(allowsXhsForeground(null, null))
    }
    @Test fun releasesDuringAnOverlayAllowTheNextChord() {
        val chord = VolumeChord()
        chord.accept(24, true, 1000)
        assertTrue(chord.accept(25, true, 1100))
        // The service must feed these releases even when rootInActiveWindow is transiently null.
        chord.accept(24, false, 1110); chord.accept(25, false, 1120)
        chord.accept(25, true, 1500)
        assertTrue(chord.accept(24, true, 1550))
    }
}
