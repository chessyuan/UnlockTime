package com.phoneuse.unified

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidDeviceAccessInitializationTest {
    @Test fun modelOnlyOutboundChecksDoNotInitializeAndroidMainDispatcher() {
        // Deliberately no Dispatchers.setMain or Android Context: pure model/Agent tests use this path.
        AndroidDeviceAccess.checkOutbound()
        val callback = AndroidDeviceAccess.outboundGuard()
        callback()
        assertEquals(DeviceOwner.NONE, AndroidDeviceAccess.coordinator.owner.value)
    }
}
