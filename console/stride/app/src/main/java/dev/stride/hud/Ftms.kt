package dev.stride.hud

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * The treadmill, as a standard Bluetooth fitness machine.
 *
 * Zwift, Kinomap and the rest do not know what a NordicTrack is. What they know
 * is FTMS, the Bluetooth SIG's Fitness Machine Service, and a machine speaking
 * it turns up in their device list next to the commercial ones. This publishes
 * that service so the console can be paired with directly, with no phone in the
 * middle and nothing installed anywhere else.
 *
 * **Why this can exist at all.** Being found by Zwift means being a BLE
 * *peripheral*, and a peripheral has to advertise. Plenty of cheap MediaTek
 * parts can only be a central, which is the role [HeartRate] already uses for
 * the strap, and on one of those this file would be dead code. This console
 * answers `isMultipleAdvertisementSupported = true`, on the same mt8163 Argon
 * silicon that ciarancoffey/nordictrack-ftms-bridge runs on. That project is
 * where the idea came from and is credited in the README. It is AGPL and this
 * is Apache-2.0, so nothing was taken from it: the encoding below is the
 * published FTMS spec, and the field order is the spec's order because the
 * spec says it is normative.
 *
 * **Telemetry only, on purpose.** FTMS also defines a Control Point that lets
 * the app on the other end set speed and incline. It is deliberately not here.
 * The rule the whole guided walk is built on is that nothing sets the belt
 * speed except the person standing on the belt (see MainActivity's setSpeed),
 * and handing that to software on another device, in another room, is a
 * different decision that nobody has asked for yet. Reading is safe. Driving
 * is not the same thing.
 */
class Ftms(private val context: Context) {

    companion object {
        private val TAG = FitProConnection.TAG

        /** Fitness Machine Service. */
        val SERVICE: UUID = uuid16(0x1826)
        /** Fitness Machine Feature: what this machine can report. Read. */
        val FEATURE: UUID = uuid16(0x2ACC)
        /** Treadmill Data: the telemetry itself. Notify. */
        val TREADMILL_DATA: UUID = uuid16(0x2ACD)
        /** Client Characteristic Configuration, the subscribe switch. */
        val CCCD: UUID = uuid16(0x2902)

        /**
         * Running Speed and Cadence, and why a treadmill publishes it too.
         *
         * FTMS is the protocol a *bike* trainer speaks to Zwift, and it is what
         * every treadmill vendor's marketing points at. Zwift's running side
         * grew up around footpods instead, which speak this: their own Runn
         * sensor is an RSC device, and QZ exists in large part to re-broadcast
         * other machines as RSC so that Zwift will look at them.
         *
         * So both are published off the one GATT server. FTMS carries incline,
         * calories and elapsed time, which RSC has no fields for, and RSC is
         * the one Zwift Run actually pairs with. A client takes whichever it
         * understands.
         */
        val RSC_SERVICE: UUID = uuid16(0x1814)
        /** RSC Measurement: speed, cadence, distance. Notify. */
        val RSC_MEASUREMENT: UUID = uuid16(0x2A53)
        /** RSC Feature: which of those are real. Read. */
        val RSC_FEATURE: UUID = uuid16(0x2A54)

        /**
         * RSC Feature bits: total distance (1) and walking-or-running status
         * (2). Stride length is not claimed, because nothing here measures a
         * stride and inventing one from speed would be a number with a
         * plausible shape and no source.
         */
        private const val RSC_FEATURES = (1 shl 1) or (1 shl 2)

        /**
         * Above this the walking-or-running status bit flips to running.
         *
         * Somebody has to pick, the spec does not, and the bit is a single
         * boolean that Zwift uses to choose an animation. Eight km/h is the
         * usual walk-to-jog crossover and it is wrong for anyone who
         * race-walks, which costs them a running avatar and nothing else.
         */
        private const val RUNNING_KPH = 8.0

        private fun uuid16(id: Int): UUID =
            UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", id))

        /**
         * Fitness Machine Feature, 4 bytes, then Target Setting Feature, 4
         * bytes. Little endian, one bit per capability.
         *
         * Claimed: average speed (bit 0), total distance (bit 2), inclination
         * (bit 3), heart rate (bit 10), elapsed time (bit 12). Target settings
         * are all zero, which is how a client is told there is no point
         * writing to a control point that isn't there.
         *
         * **This must agree with [FLAGS], field for field.** The first version
         * claimed expended energy and did not claim average speed, while the
         * frame sent average speed and no energy. Zwift read the feature, read
         * a frame that contradicted it, and hung up 96ms after subscribing.
         * A client is entitled to believe what a machine says about itself.
         */
        private const val FEATURES = (1 shl 0) or (1 shl 2) or (1 shl 3) or
                (1 shl 10) or (1 shl 12)

        /**
         * Treadmill Data flags, 16 bits, saying which fields follow.
         *
         * Bit 0 is the odd one: it means *More Data*, and instantaneous speed
         * is present when it is **clear**. So bit 0 stays 0 and the speed goes
         * in. The rest name what follows it: average speed (1), total distance
         * (2), inclination and ramp angle (3), heart rate (8), elapsed time
         * (10). Must agree with [FEATURES].
         */
        private const val FLAGS = (1 shl 1) or (1 shl 2) or (1 shl 3) or
                (1 shl 8) or (1 shl 10)

        /**
         * The advertised payload that makes this a *treadmill* rather than an
         * unspecified fitness machine: flags byte (bit 0, machine available),
         * then the machine type as uint16 (bit 0, treadmill).
         */
        private val SERVICE_DATA = byteArrayOf(0x01, 0x01, 0x00)
    }

    private val manager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter = manager?.adapter

    private var server: BluetoothGattServer? = null
    private var data: BluetoothGattCharacteristic? = null
    private var rsc: BluetoothGattCharacteristic? = null
    private var advertiser: android.bluetooth.le.BluetoothLeAdvertiser? = null

    /**
     * Who is subscribed to what. A notify to nobody is just radio, and a
     * client that paired as a footpod has no interest in the FTMS frame.
     */
    private val ftmsSubs = LinkedHashSet<BluetoothDevice>()
    private val rscSubs = LinkedHashSet<BluetoothDevice>()

    /**
     * Services still to be registered.
     *
     * `addService` is asynchronous and only one may be in flight: adding the
     * second before [BluetoothGattServerCallback.onServiceAdded] fires for the
     * first drops it, with no error anywhere. The queue exists so the two
     * services actually both arrive.
     */
    private val pending = ArrayDeque<BluetoothGattService>()

    /** The last frame's worth of numbers, so a new subscriber gets an answer
     *  straight away instead of waiting for the next poll. */
    @Volatile private var last: Snapshot? = null

    @Volatile var advertising = false
        private set

    /** How many clients are listening, for the settings screen to show. */
    val listeners: Int
        get() = synchronized(ftmsSubs) { ftmsSubs.size } +
                synchronized(rscSubs) { rscSubs.size }

    fun start() {
        val a = adapter ?: run { Log.i(TAG, "ftms: no bluetooth adapter"); return }
        if (!a.isEnabled) { Log.i(TAG, "ftms: bluetooth is off"); return }
        if (!a.isMultipleAdvertisementSupported) {
            // Not a failure worth shouting about. It is a property of the
            // radio, it will never change, and everything else still works.
            Log.i(TAG, "ftms: this radio cannot advertise, skipping")
            return
        }
        if (server != null) return

        val srv = manager?.openGattServer(context, callback) ?: run {
            Log.w(TAG, "ftms: could not open a GATT server"); return
        }
        server = srv

        val feature = BluetoothGattCharacteristic(
            FEATURE,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).apply { value = featureBytes() }

        val treadmill = BluetoothGattCharacteristic(
            TREADMILL_DATA,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            0,   // notify carries no read permission of its own
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ or
                        BluetoothGattDescriptor.PERMISSION_WRITE,
                )
            )
        }
        data = treadmill

        val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(feature)
        service.addCharacteristic(treadmill)

        // Running Speed and Cadence, the one Zwift Run actually pairs with.
        val rscFeature = BluetoothGattCharacteristic(
            RSC_FEATURE,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).apply { value = byteArrayOf((RSC_FEATURES and 0xFF).toByte(), 0) }

        val rscData = BluetoothGattCharacteristic(
            RSC_MEASUREMENT,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            0,
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ or
                        BluetoothGattDescriptor.PERMISSION_WRITE,
                )
            )
        }
        rsc = rscData

        val running = BluetoothGattService(RSC_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        running.addCharacteristic(rscFeature)
        running.addCharacteristic(rscData)

        pending.addLast(service)
        pending.addLast(running)
        addNext()

        advertise(a)
    }

    /** Register the next service, one at a time. See [pending]. */
    private fun addNext() {
        val srv = server ?: return
        val next = pending.removeFirstOrNull() ?: return
        srv.addService(next)
    }

    private fun advertise(a: android.bluetooth.BluetoothAdapter) {
        advertiser = a.bluetoothLeAdvertiser
        val adv = advertiser ?: run { Log.w(TAG, "ftms: no advertiser"); return }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        // The name goes in the scan response rather than the advertisement.
        // An advertisement is 31 bytes, the service UUID and its service data
        // take 16 of them, and a console called EwayMediatekArgon2 does not
        // fit in what is left. A client that wants the name asks for it.
        val payload = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE))
            .addServiceUuid(ParcelUuid(RSC_SERVICE))
            .addServiceData(ParcelUuid(SERVICE), SERVICE_DATA)
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        adv.startAdvertising(settings, payload, scanResponse, advertiseCallback)
    }

    /**
     * Put the advertisement back after a client has come and gone.
     *
     * Stopping first because the stack may or may not consider the old one
     * finished, and starting twice returns ALREADY_STARTED rather than
     * advertising twice. Both calls are wrapped: neither failing is worth
     * taking the console down for.
     */
    private fun restartAdvertising() {
        val a = adapter ?: return
        if (!a.isEnabled) return
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) { }
        advertising = false
        try { advertise(a) } catch (e: Exception) {
            Log.w(TAG, "ftms: could not resume advertising: ${e.message}")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            advertising = true
            Log.i(TAG, "ftms: advertising as a treadmill")
        }
        override fun onStartFailure(errorCode: Int) {
            advertising = false
            Log.w(TAG, "ftms: advertising refused (error $errorCode)")
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            val name = if (service.uuid == RSC_SERVICE) "running speed and cadence"
                       else "fitness machine"
            if (status == BluetoothGatt.GATT_SUCCESS) Log.i(TAG, "ftms: published $name")
            else Log.w(TAG, "ftms: $name refused (status $status)")
            addNext()
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                synchronized(ftmsSubs) { ftmsSubs.remove(device) }
                synchronized(rscSubs) { rscSubs.remove(device) }
                Log.i(TAG, "ftms: ${device.address} disconnected")
                // Android stops a connectable advertisement the moment it is
                // accepted, and never restarts it. So one phone connecting once
                // takes the treadmill off the air for everything else until the
                // app is restarted: pair with a scanner to check it works, and
                // it is invisible to Zwift by the time you switch over. Put it
                // straight back.
                restartAdvertising()
            } else if (newState == BluetoothGatt.STATE_CONNECTED) {
                // Same reason. A second client can only find us if we are still
                // advertising while the first one is connected, and on this
                // stack we are not.
                Log.i(TAG, "ftms: ${device.address} connected")
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = characteristic.value ?: ByteArray(0)
            server?.sendResponse(
                device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                if (offset < value.size) value.copyOfRange(offset, value.size) else ByteArray(0),
            )
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (descriptor.uuid == CCCD) {
                val on = value.isNotEmpty() && value[0].toInt() != 0
                val running = descriptor.characteristic?.uuid == RSC_MEASUREMENT
                val set = if (running) rscSubs else ftmsSubs
                synchronized(set) { if (on) set.add(device) else set.remove(device) }
                Log.i(TAG, "ftms: ${device.address} " +
                        "${if (on) "subscribed to" else "unsubscribed from"} " +
                        if (running) "running speed" else "treadmill data")
                // Answer immediately rather than at the next poll. A client
                // that subscribes and then sits in silence has no way to tell
                // a working treadmill from a broken one, and some give up in
                // well under a second.
                if (on) last?.let { s ->
                    val srv = server ?: return@let
                    push(srv, if (running) rsc else data, listOf(device)) {
                        if (running) rscFrame(s) else frame(s)
                    }
                }
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            val set = if (descriptor.characteristic?.uuid == RSC_MEASUREMENT) rscSubs else ftmsSubs
            val on = synchronized(set) { set.contains(device) }
            server?.sendResponse(
                device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                if (on) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE,
            )
        }
    }

    /**
     * Push one frame to everyone listening.
     *
     * Called from the poll loop, so it runs at whatever rate the board is being
     * read at and costs nothing when nobody has subscribed.
     */
    fun update(s: Snapshot) {
        last = s
        val srv = server ?: return
        push(srv, data, synchronized(ftmsSubs) { ftmsSubs.toList() }) { frame(s) }
        push(srv, rsc, synchronized(rscSubs) { rscSubs.toList() }) { rscFrame(s) }
    }

    private inline fun push(
        srv: BluetoothGattServer,
        c: BluetoothGattCharacteristic?,
        who: List<BluetoothDevice>,
        body: () -> ByteArray,
    ) {
        if (c == null || who.isEmpty()) return
        c.value = body()
        for (d in who) {
            try {
                srv.notifyCharacteristicChanged(d, c, false)
            } catch (e: Exception) {
                Log.w(TAG, "ftms: notify to ${d.address} failed: ${e.message}")
            }
        }
    }

    /**
     * One RSC Measurement: flags, speed, cadence, total distance.
     *
     * Cadence is sent as zero because nothing here counts steps. The board has
     * no step sensor and deriving one from speed would be a number with a
     * plausible shape and no source behind it, which is worse than a gap.
     */
    private fun rscFrame(s: Snapshot): ByteArray {
        val b = ByteArray(8)
        var i = 0
        // Total distance present (bit 1); bit 2 is the walking/running status.
        var flags = (1 shl 1)
        if (s.speed >= RUNNING_KPH) flags = flags or (1 shl 2)
        b[i++] = flags.toByte()
        // Speed, metres per second at 1/256.
        i = le16(b, i, Math.round(s.speed / 3.6 * 256.0).toInt().coerceIn(0, 0xFFFF))
        b[i++] = 0                       // cadence, steps per minute
        // Total distance, tenths of a metre.
        le32(b, i, Math.round(s.distance * 10.0).toInt().coerceAtLeast(0))
        return b
    }

    private fun featureBytes(): ByteArray {
        val b = ByteArray(8)
        le32(b, 0, FEATURES)
        le32(b, 4, 0)          // no target settings: nothing here can be driven
        return b
    }

    /**
     * One Treadmill Data notification, in the order [FLAGS] promises.
     *
     * The speed sent is the setpoint rather than the odometer estimate, for the
     * same reason the console's own dial shows it: see Snapshot.speed. The
     * estimate wanders by a few tenths whenever the deck tilts, and a receiving
     * app has no way to know that and would draw the wander as real.
     */
    private fun frame(s: Snapshot): ByteArray {
        val b = ByteArray(16)
        var i = 0
        i = le16(b, i, FLAGS)
        // Instantaneous speed, km/h at 0.01 resolution.
        i = le16(b, i, Math.round(s.speed * 100.0).toInt().coerceIn(0, 0xFFFF))
        // Average speed, same units. Already tracked for the summary screen.
        i = le16(b, i, Math.round(s.avgSpeed * 100.0).toInt().coerceIn(0, 0xFFFF))
        // Total distance, metres, three bytes.
        i = le24(b, i, s.distance.toInt().coerceIn(0, 0xFFFFFF))
        // Inclination, percent at 0.1, signed. The deck declines, so this goes
        // negative and the field is sint16 for exactly that reason.
        i = le16(b, i, Math.round(s.incline * 10.0).toInt().coerceIn(-32768, 32767))
        // Ramp angle setting. The board reports grade, not an angle, and the
        // spec pairs the two fields under one flag, so this is the "unknown"
        // value rather than a number nobody measured.
        i = le16(b, i, 0x7FFF)
        // Heart rate, bpm, whatever the strap is saying.
        b[i++] = (s.pulse.coerceIn(0, 255)).toByte()
        // Elapsed time, seconds.
        i = le16(b, i, s.elapsed.toInt().coerceIn(0, 0xFFFF))
        return b
    }

    private fun le16(b: ByteArray, at: Int, v: Int): Int {
        b[at] = (v and 0xFF).toByte()
        b[at + 1] = ((v shr 8) and 0xFF).toByte()
        return at + 2
    }

    private fun le24(b: ByteArray, at: Int, v: Int): Int {
        b[at] = (v and 0xFF).toByte()
        b[at + 1] = ((v shr 8) and 0xFF).toByte()
        b[at + 2] = ((v shr 16) and 0xFF).toByte()
        return at + 3
    }

    private fun le32(b: ByteArray, at: Int, v: Int) {
        b[at] = (v and 0xFF).toByte()
        b[at + 1] = ((v shr 8) and 0xFF).toByte()
        b[at + 2] = ((v shr 16) and 0xFF).toByte()
        b[at + 3] = ((v shr 24) and 0xFF).toByte()
    }

    fun stop() {
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) { }
        advertising = false
        synchronized(ftmsSubs) { ftmsSubs.clear() }
        synchronized(rscSubs) { rscSubs.clear() }
        try { server?.close() } catch (_: Exception) { }
        server = null
        data = null
    }
}
