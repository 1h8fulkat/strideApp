package dev.stride.hud

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * USB transport to the brainboard.
 *
 * The board is request/response: write a frame to the OUT endpoint, then read
 * the reply from IN. It never speaks unprompted, so every exchange is driven
 * from here.
 */
class FitProConnection(private val context: Context) {

    companion object {
        const val TAG = "Stride"
        const val VID = 0x213c
        const val PID = 0x0002
        private const val READ_TIMEOUT_MS = 1000   // ResponseTimeoutMs in iFit
        private const val WRITE_TIMEOUT_MS = 200
    }

    private var connection: UsbDeviceConnection? = null
    private var iface: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null

    val isOpen: Boolean get() = connection != null

    fun findDevice(): UsbDevice? {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return manager.deviceList.values.firstOrNull { it.vendorId == VID && it.productId == PID }
    }

    fun hasPermission(device: UsbDevice): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return manager.hasPermission(device)
    }

    /** Claim interface 0 and locate the interrupt endpoints. */
    fun open(device: UsbDevice): String? {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val conn = manager.openDevice(device) ?: return "openDevice() null — another app holds it"

        for (i in 0 until device.interfaceCount) {
            val candidate = device.getInterface(i)
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (e in 0 until candidate.endpointCount) {
                val ep = candidate.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_INT) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
            }
            if (inEp != null && outEp != null) {
                if (!conn.claimInterface(candidate, true)) {
                    conn.close()
                    return "claimInterface() failed"
                }
                connection = conn; iface = candidate; epIn = inEp; epOut = outEp
                return null
            }
        }
        conn.close()
        return "no interrupt IN+OUT pair found"
    }

    fun close() {
        iface?.let { connection?.releaseInterface(it) }
        connection?.close()
        connection = null; iface = null; epIn = null; epOut = null
    }

    /**
     * Send a frame and read the reply. Returns null on timeout or if not open.
     * Not synchronised — call from a single worker thread.
     */
    fun exchange(frame: ByteArray): ByteArray? {
        val conn = connection ?: return null
        val out = epOut ?: return null
        val inn = epIn ?: return null

        val sent = conn.bulkTransfer(out, frame, frame.size, WRITE_TIMEOUT_MS)
        if (sent < 0) {
            Log.w(TAG, "write failed: ${FitPro.hex(frame)}")
            return null
        }

        val buf = ByteArray(FitPro.MAX_MSG)
        val n = conn.bulkTransfer(inn, buf, buf.size, READ_TIMEOUT_MS)
        if (n <= 0) return null
        return buf.copyOf(n)
    }

    fun describe(device: UsbDevice): String = buildString {
        append("${device.manufacturerName} / ${device.productName}\n")
        append("vid=0x%04X pid=0x%04X".format(device.vendorId, device.productId))
    }
}
