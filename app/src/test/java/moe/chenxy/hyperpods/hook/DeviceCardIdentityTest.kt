package moe.chenxy.hyperpods.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCardIdentityTest {
    @Test fun validMacIsAuthoritativeAndCaseInsensitive() {
        assertTrue(matchesDeviceCard("aa:bb:cc:dd:ee:ff", "AirPods",
            "AA:BB:CC:DD:EE:FF", "Different name"))
        assertFalse(matchesDeviceCard("aa:bb:cc:dd:ee:ff", "AirPods",
            "AA:BB:CC:DD:EE:00", "AirPods"))
    }

    @Test fun nonMacDeviceCenterIdFallsBackToExactDisplayedName() {
        assertTrue(matchesDeviceCard("device-center-uuid", "Redmi buds 8 pro",
            "AA:BB:CC:DD:EE:FF", "Redmi buds 8 pro"))
        assertFalse(matchesDeviceCard("device-center-uuid", "Redmi buds 8 pro",
            "AA:BB:CC:DD:EE:FF", "AirPods"))
    }

    @Test fun tokenComparisonStillWorksWhenMacFormattingDiffers() {
        assertTrue(matchesDeviceCard("AA-BB-CC-DD-EE-FF", null,
            "aa:bb:cc:dd:ee:ff", null))
    }
}
