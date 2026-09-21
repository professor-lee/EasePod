package app.easepod.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WheelGeometryTest {
    @Test fun seamCrossingPreservesDirection() {
        assertEquals(2f, WheelGeometry.delta(179f, -179f))
        assertEquals(-2f, WheelGeometry.delta(-179f, 179f))
    }
    @Test fun centerNeverBecomesDirectionalTap() {
        assertEquals(WheelKey.CENTER, WheelGeometry.key(0f, 20f, 100f))
        assertEquals(WheelKey.PLAY, WheelGeometry.key(0f, 80f, 100f))
        assertEquals(WheelKey.MENU, WheelGeometry.key(0f, -80f, 100f))
        assertNull(WheelGeometry.key(101f, 0f, 100f))
    }
}
