package moe.chenxy.hyperpods.hook

import moe.chenxy.hyperpods.pods.EarDetectionStatus
import moe.chenxy.hyperpods.utils.data.BatteryParams
import moe.chenxy.hyperpods.utils.data.EarDetectionParams
import moe.chenxy.hyperpods.utils.data.PodBatteryParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XiaomiAirPodsStateBridgeTest {
    @Test fun nativeStateUsesTheFormatReadByOfflineFindProvider() {
        val battery = BatteryParams(
            left = PodBatteryParams(91, isCharging = true, isConnected = true),
            right = PodBatteryParams(93, isCharging = false, isConnected = true),
            case = PodBatteryParams(87, isCharging = true, isConnected = false),
        )
        assertEquals(5, XiaomiAirPodsStateBridge.chargeMask(battery))
        assertEquals("91|93|-|5|2|2420",
            XiaomiAirPodsStateBridge.statePayload("91", "93", "-", 5, 2, "2420"))
    }

    @Test fun wearMaskMatchesNativeBitFlags() {
        assertEquals(0, XiaomiAirPodsStateBridge.earMask(
            EarDetectionParams(EarDetectionStatus.OUT_OF_EAR, EarDetectionStatus.IN_CASE)))
        assertEquals(1, XiaomiAirPodsStateBridge.earMask(
            EarDetectionParams(EarDetectionStatus.IN_EAR, EarDetectionStatus.OUT_OF_EAR)))
        assertEquals(3, XiaomiAirPodsStateBridge.earMask(
            EarDetectionParams(EarDetectionStatus.IN_EAR, EarDetectionStatus.IN_EAR)))
    }

    @Test fun ancCodesMatchTheNativeSettingsPlugin() {
        assertEquals("01", XiaomiAirPodsStateBridge.ancCode(1))
        assertEquals("02", XiaomiAirPodsStateBridge.ancCode(2))
        assertEquals("03", XiaomiAirPodsStateBridge.ancCode(3))
        assertNull(XiaomiAirPodsStateBridge.ancCode(4))
        assertEquals(1, XiaomiAirPodsStateBridge.nativeAncMode("01"))
        assertEquals(2, XiaomiAirPodsStateBridge.nativeAncMode("02"))
        assertEquals(3, XiaomiAirPodsStateBridge.nativeAncMode("03"))
        assertNull(XiaomiAirPodsStateBridge.nativeAncMode("04"))
    }

    @Test fun unavailableBatteryIsRenderedAsDashRatherThanZero() {
        assertEquals("-", XiaomiAirPodsStateBridge.batteryValue(null))
        assertEquals("-", XiaomiAirPodsStateBridge.batteryValue(PodBatteryParams()))
        assertEquals("0", XiaomiAirPodsStateBridge.batteryValue(
            PodBatteryParams(0, isConnected = true)))
    }
}