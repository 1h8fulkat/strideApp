package dev.stride.hud

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import java.util.UUID

/**
 * A Bluetooth chest strap, read directly by the console.
 *
 * The board reports a pulse from the handlebar grips, and it is real, but it
 * costs both hands on the bar — so on a hill, exactly when the number is worth
 * having, it is not there. A strap reads continuously and does not care what
 * you are doing with your arms.
 *
 * **Standard service, no brand support.** `0x180D` and `0x2A37` are the
 * Bluetooth SIG's Heart Rate Service and Measurement characteristic, and every
 * strap worth buying implements them — Polar, Garmin, Wahoo, Coospo, the lot.
 * Scanning filters on the service UUID rather than on names, so the list is
 * "straps", not "Bluetooth devices near a treadmill", and a strap nobody has
 * heard of works on the day it is unboxed.
 *
 * **This connection is the console's own.** It does not go through Home
 * Assistant and does not care whether the broker is up, for the same reason the
 * coach keeps a set of canned lines: a treadmill in a shed with no network is
 * still a treadmill.
 *
 * Reconnection is left to `autoConnect = true`. A strap goes out of range every
 * time its wearer walks off, and the platform's own reconnect is both cheaper
 * and better behaved than a retry loop of ours.
 */
class HeartRate(private val context: Context) {

    companion object {
        val SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

        /** The descriptor every notify-capable characteristic carries. */
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val SCAN_MS = 8_000L

        /**
         * A reading older than this is not a reading.
         *
         * Straps notify about once a second. Ten seconds of silence means the
         * belt is running and the strap is not connected, and showing the last
         * number it managed is worse than showing nothing — it is the same
         * class of mistake as a stale sensor holding yesterday's step count.
         */
        const val STALE_MS = 10_000L
    }

    data class Found(val address: String, val name: String, val rssi: Int)

    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE)
            as? android.bluetooth.BluetoothManager)?.adapter

    private var gatt: BluetoothGatt? = null
    private val seen = LinkedHashMap<String, Found>()

    @Volatile private var lastBpm = 0
    @Volatile private var lastAt = 0L
    @Volatile var battery = -1
        private set
    @Volatile var connected = false
        private set
    @Volatile var scanning = false
        private set

    val available: Boolean get() = adapter?.isEnabled == true

    /** The current pulse, or 0 if there has not been one recently. */
    fun bpm(): Int =
        if (lastBpm > 0 && SystemClock.elapsedRealtime() - lastAt < STALE_MS) lastBpm else 0

    // --- scanning -------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun scan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        if (scanning) return
        seen.clear()
        scanning = true

        // Filtering on the service UUID is what makes this a list of straps.
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
        } catch (e: SecurityException) {
            Log.w(FitProConnection.TAG, "hr: no scan permission (${e.message})")
            scanning = false
            return
        }
        handler.postDelayed({ stopScan() }, SCAN_MS)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            // Nothing to do; the scan stops on its own timeout anyway.
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(type: Int, result: ScanResult) {
            val dev = result.device ?: return
            val name = try {
                dev.name ?: result.scanRecord?.deviceName ?: "Heart rate strap"
            } catch (e: SecurityException) {
                "Heart rate strap"
            }
            seen[dev.address] = Found(dev.address, name, result.rssi)
        }

        override fun onScanFailed(code: Int) {
            Log.w(FitProConnection.TAG, "hr: scan failed ($code)")
            scanning = false
        }
    }

    fun found(): List<Found> = seen.values.sortedByDescending { it.rssi }

    // --- connecting -----------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        if (address.isBlank()) return
        disconnect()
        val dev: BluetoothDevice = try {
            adapter?.getRemoteDevice(address) ?: return
        } catch (e: IllegalArgumentException) {
            Log.w(FitProConnection.TAG, "hr: bad address $address")
            return
        }
        gatt = try {
            // autoConnect: the platform reconnects when the strap comes back
            // into range, which it does every time its wearer walks off.
            dev.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            Log.w(FitProConnection.TAG, "hr: no connect permission")
            null
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        try {
            gatt?.close()
        } catch (e: SecurityException) {
            // Closing without permission is not recoverable and not worth a crash.
        }
        gatt = null
        connected = false
        lastBpm = 0
        battery = -1
    }

    private val callback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            connected = newState == BluetoothProfile.STATE_CONNECTED
            if (connected) {
                try { g.discoverServices() } catch (e: SecurityException) { }
            } else {
                lastBpm = 0
            }
            Log.i(FitProConnection.TAG, "hr: ${if (connected) "connected" else "disconnected"}")
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(SERVICE)?.getCharacteristic(MEASUREMENT) ?: run {
                Log.w(FitProConnection.TAG, "hr: no heart rate characteristic")
                return
            }
            try {
                g.setCharacteristicNotification(ch, true)
                // Subscribing is the descriptor write, not the call above — the
                // call only tells the local stack to pass notifications on.
                ch.getDescriptor(CCCD)?.let { d ->
                    @Suppress("DEPRECATION")
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(d)
                }
            } catch (e: SecurityException) {
                return
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            // Battery is a nice-to-have and is read once, after the thing that
            // matters is already subscribed. Concurrent GATT operations are not
            // allowed, which is why it waits until here.
            val ch = g.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_LEVEL) ?: return
            try { g.readCharacteristic(ch) } catch (e: SecurityException) { }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (ch.uuid == MEASUREMENT) parse(ch.value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray
        ) {
            if (ch.uuid == MEASUREMENT) parse(value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int
        ) {
            if (ch.uuid == BATTERY_LEVEL) battery = ch.value?.firstOrNull()?.toInt() ?: -1
        }
    }

    /**
     * Decode a Heart Rate Measurement.
     *
     * Byte 0 is flags; bit 0 says whether the value that follows is one byte or
     * two. Most straps send one, some send two, and reading a two-byte strap as
     * one byte gives a plausible-looking wrong number rather than an obvious
     * failure — which is why this reads the flag rather than assuming.
     */
    private fun parse(data: ByteArray?) {
        if (data == null || data.isEmpty()) return
        val wide = (data[0].toInt() and 0x01) != 0
        val bpm = if (wide) {
            if (data.size < 3) return
            (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
        } else {
            if (data.size < 2) return
            data[1].toInt() and 0xFF
        }
        // A strap that has just been put on, or has lost skin contact, reports
        // zero. That is "no reading", not a heart that has stopped.
        if (bpm <= 0 || bpm > 250) return
        lastBpm = bpm
        lastAt = SystemClock.elapsedRealtime()
    }
}
