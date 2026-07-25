package com.hereliesaz.graffitixr.common.wearable

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.hereliesaz.graffitixr.common.sensor.CameraIntrinsics
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class XrealGlassProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : SmartGlassProvider {
    override val cameraIntrinsics: CameraIntrinsics = CameraIntrinsics.UNKNOWN

    override val name: String = "Xreal Air"

    override val capabilities: Set<GlassCapability> = setOf(
        GlassCapability.SPATIAL_DISPLAY,
        GlassCapability.IMU_TRACKING
    )

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    // connect() previously only took a one-time snapshot of the USB device list; nothing observed
    // a physical unplug afterwards, so _connectionState stayed Connected forever even after the
    // glasses were removed — WearableManager kept routing sensor reads to a provider that gave no
    // signal to fall back to the phone camera. This receiver re-checks on both directions of USB
    // hotplug so a mid-session unplug/replug is reflected.
    private var usbReceiver: BroadcastReceiver? = null

    override fun connect() {
        checkUsbConnection()
        registerUsbReceiver()
    }

    override fun disconnect() {
        unregisterUsbReceiver()
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun registerUsbReceiver() {
        if (usbReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                checkUsbConnection()
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        usbReceiver = receiver
    }

    private fun unregisterUsbReceiver() {
        usbReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
                // Already unregistered (e.g. disconnect() called twice) — nothing to do.
            }
        }
        usbReceiver = null
    }

    private fun checkUsbConnection() {
        val deviceList = usbManager.deviceList
        // Xreal Vendor IDs: 0x3318, 0x0483 (Nreal/Xreal)
        val isXrealFound = deviceList.values.any {
            it.vendorId == 0x3318 || it.vendorId == 0x0483
        }

        _connectionState.value = if (isXrealFound) {
            ConnectionState.Connected
        } else {
            ConnectionState.Disconnected
        }
    }
}
