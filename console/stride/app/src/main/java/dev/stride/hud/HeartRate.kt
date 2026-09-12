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
 * The board has a pulse field, and on machines that wire contact grips into
 * it the number is real, but it costs both hands on the bar — so on a hill,
 * exactly when the number is worth having, it is not there. Plenty of machines
 * have no grips at all, including the C 1750 this was built on, where the field
 * sits at zero through an entire walk. A strap reads continuously, does not
 * care what your arms are doing, and is the only source that works everywhere.
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
 * **Reconnection is left to `autoConnect = true`; the first connection is not.**
 * A strap goes out of range every time its wearer walks off, and the platform's
 * own reconnect is both cheaper and better behaved than a retry loop of ours.
 * But `autoConnect` is a *background* connection: the controller looks for the
 * device on a slow duty cycle, and for an address it has never resolved before
 * that means minutes, not seconds. Pairing measured two minutes flat on this
 * console — long enough that the walk it was paired for had already finished.
 * So the first attempt is direct, and `autoConnect` takes over only once there
 * is a connection to re-establish.
 */
class HeartRate(
    private val context: Context,
    /**
     * True while the radio is committed elsewhere and a strap hunt would cost
     * more than it is worth. See [seek].
     */
    private val busy: () -> Boolean = { false },
) {

    companion object {
        val SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

        /** The descriptor every notify-capable characteristic carries. */
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val SCAN_MS = 8_000L

        /**
         * How long to let the stack settle before asking for services.
         *
         * The platform starts its own discovery the moment a link comes up on a
         * device it has no GATT cache for, and it gets there before our
         * connection callback runs — measured at 14ms ahead on this console.
         * `discoverServices()` called into that window returns false, and a
         * refused discovery means `onServicesDiscovered` never fires, which
         * means the subscription below never happens. The strap then sits
         * connected and silent forever, which is exactly the shape of bug that
         * looks like "the strap is broken".
         */
        const val DISCOVERY_DELAY_MS = 600L
        const val DISCOVERY_RETRY_MS = 1_000L
        const val DISCOVERY_TRIES = 5

        /**
         * How long to hunt for a strap at full speed before going quiet.
         *
         * A seek is an active scan, so it is not free and must not run all day
         * on the chance that a strap in a drawer wakes up. A minute covers the
         * cases that matter — the strap slipping, the wearer stepping off the
         * back, a console restart mid-walk — and everything past that is
         * better served by the background connection, which costs nothing and
         * has all the time in the world.
         */
        const val SEEK_MS = 60_000L

        /**
         * How long to let a background connection sit before going back to an
         * active seek.
         *
         * `connectGatt(autoConnect = true)` hands back a client immediately and
         * then says nothing whether or not it ever finds the strap, so a
         * background connection that will never land looks exactly like one
         * that is about to. Nothing times it out and no callback fires, which
         * left the console holding a dead client, believing it had a strap, and
         * declining to look for one — and the only way out was to forget the
         * strap and pair it again, because changing the address is the one
         * thing that got past the guard in [connect].
         *
         * So the background attempt is given a finite go, and then the
         * cheap-and-patient mode and the fast-and-expensive one take turns for
         * as long as a strap is wanted.
         *
         * **Thirty seconds, and it used to be five minutes.** The reasoning for
         * five was that the background connection "usually does work and is
         * much the kinder of the two on the radio". The first half is not borne
         * out. Pairing through this path was measured at 98–108 seconds, and on
         * 2026-09-12 a watch whose sharing was switched back on during a
         * background window was not found at all: the console opened the client
         * at 14:14:37 and then did nothing whatever for five minutes, because
         * it had committed to the slow path and stopped looking.
         *
         * The scan is the half that works — a device advertises about once a
         * second and a filtered scan finds it almost immediately. So the split
         * is now heavily in the scan's favour: a minute of looking, half a
         * minute of waiting, repeat. The worst case for somebody switching a
         * strap on is half a minute rather than five.
         *
         * That is more radio than before, and deliberately. It only runs while
         * a strap is wanted and not connected, [seek] still stands aside for a
         * Bluetooth client that is actually being used, and one scan every
         * ninety seconds is nowhere near the five-in-thirty-seconds that gets
         * an app throttled.
         */
        const val BACKGROUND_GIVE_UP_MS = 30_000L

        /**
         * A reading older than this is not a reading.
         *
         * Straps notify about once a second. Ten seconds of silence means the
         * belt is running and the strap is not connected, and showing the last
         * number it managed is worse than showing nothing — it is the same
         * class of mistake as a stale sensor holding yesterday's step count.
         */
        const val STALE_MS = 10_000L

        /**
         * How long a *connected* strap may deliver nothing before the link is
         * treated as dead regardless of what the stack says. See [watchdog].
         *
         * A strap notifies about once a second, so this is thirty missed
         * notifications — long enough that no slow reporter trips it, short
         * enough to be back before the walk it was wanted for is over.
         */
        const val SILENT_LINK_MS = 30_000L

        /** How often [watchdog] looks. Cheap; it is two field reads. */
        const val LINK_CHECK_MS = 5_000L

        /**
         * How long to wait before asking again for a hunt the radio refused.
         *
         * Long enough not to spin while somebody is riding Zwift, short enough
         * that putting a strap on part-way through a session still finds it.
         */
        const val BUSY_RETRY_MS = 30_000L
    }

    data class Found(val address: String, val name: String, val rssi: Int)

    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE)
            as? android.bluetooth.BluetoothManager)?.adapter

    @Volatile private var gatt: BluetoothGatt? = null
    private val seen = LinkedHashMap<String, Found>()

    /** The address we are meant to be holding, blank when deliberately down. */
    @Volatile private var wanted = ""

    /**
     * The remembered name of the strap, and the only durable thing about some
     * of them.
     *
     * A Galaxy Watch — and anything else using a **resolvable private
     * address** — changes its Bluetooth address every few minutes by design.
     * Four were seen for one watch inside eleven minutes on 2026-09-12:
     * `45:9C:…`, `78:64:…`, `77:17:…`, `76:73:…`, every one of them with `01`
     * in the top two bits of the first octet, which is precisely what says
     * "this address is temporary".
     *
     * So [wanted] is a lead, not an identity, and a hunt filtered on it alone
     * can never find such a device again. That is the whole reason pairing
     * appeared to be the only cure: pairing scans for the *service* and takes
     * whatever address is current, which is to say it re-learns the address
     * rather than resetting anything.
     *
     * Resolving an RPA properly needs the identity key from bonding, which
     * these straps do not require and this console does not do. The name is
     * what is left, and it is enough: it is chosen by the device, it is in the
     * advertisement, and "Galaxy Watch7 (64HA)" is not going to collide with
     * somebody else's strap in a shed.
     */
    @Volatile private var wantedName = ""

    /**
     * Every monitor the console would accept, not just the one it saw last.
     *
     * Two people walk on this machine and only one of them is wearing a monitor
     * at any moment, so there is nothing to choose between: the hunt looks for
     * all of them and takes whichever is actually advertising. Whose it is does
     * not have to be recorded anywhere, which is the thing that makes this
     * small — pairing never has to ask, and the settings screen never has to
     * know who is walking.
     *
     * [wanted] and [wantedName] stay as the *current* monitor — the one being
     * connected to or already connected — because a connection is singular
     * even when the list is not.
     */
    @Volatile private var known: List<Settings.Strap> = emptyList()

    /**
     * Told when the strap turns up at an address other than the stored one, so
     * the caller can write the new one down. See [wantedName].
     */
    var onAddressChanged: ((name: String, address: String) -> Unit)? = null

    /** True while hunting for [wanted] to advertise — see [seek]. */
    @Volatile private var seeking = false

    /**
     * Which hunt is the current one.
     *
     * [seek] posts a timer to end its own scan, and that timer used to identify
     * the scan it belonged to only by the [seeking] flag — which the *next*
     * hunt sets true again. So an expiring timer could end a later, unrelated
     * scan. Measured on 2026-09-12: a hunt started at 14:11:22.972 for pairing,
     * satisfied a moment later; the watchdog started a fresh one at 14:12:10.614
     * when the link went quiet; and the first one's timer fired at 14:12:23.017
     * — 60.045 s after it was posted, and 12 s into a hunt that was supposed to
     * have a minute. The strap was pushed onto the five-minute background path
     * for no reason.
     *
     * Harmless while hunts were rare. The watchdog makes them ordinary, so
     * overlapping timers are now the normal case rather than the odd one.
     */
    private var seekGen = 0

    @Volatile private var lastBpm = 0
    @Volatile private var lastAt = 0L

    /**
     * When a measurement notification last *arrived* — valid or not.
     *
     * Deliberately not [lastAt], which is the last usable reading and is what
     * [bpm] ages out for the display. A strap that is connected but off the
     * chest still notifies, with a zero in it, and [parse] drops those: keying
     * the watchdog on [lastAt] would declare a perfectly healthy link dead
     * every time somebody put the strap down.
     *
     * What this measures is whether the radio link is carrying anything at all.
     */
    @Volatile private var lastFrameAt = 0L

    /** One watchdog in flight at a time, not one per connection. */
    private var watching = false
    @Volatile var battery = -1
        private set
    @Volatile var connected = false
        private set
    @Volatile var scanning = false
        private set

    val available: Boolean get() = adapter?.isEnabled == true

    /**
     * What this console's radio can actually do, logged once at startup.
     *
     * Asked because somebody wanted FTMS: broadcasting the treadmill to Zwift
     * means being a BLE *peripheral*, and on cheap MediaTek parts that is
     * routinely absent while the central role this class already uses works
     * fine. Nothing here reads it yet. It is a fact worth having written down
     * before anyone designs against it.
     */
    fun logRadioCapabilities() {
        val a = adapter
        if (a == null) { Log.i(FitProConnection.TAG, "radio: no bluetooth adapter"); return }
        Log.i(FitProConnection.TAG, "radio: peripheral(multiAdvertisement)=${a.isMultipleAdvertisementSupported}" +
                " offloadedFilter=${a.isOffloadedFilteringSupported}" +
                " offloadedBatching=${a.isOffloadedScanBatchingSupported}" +
                " advertiser=${if (a.bluetoothLeAdvertiser != null) "present" else "null"}")
    }

    /** The current pulse, or 0 if there has not been one recently. */
    /**
     * Which of the known monitors is connected, or being connected to.
     *
     * The settings screen shows every monitor it knows and marks the live one,
     * so that forgetting is per monitor rather than all or nothing.
     */
    val currentName: String get() = wantedName

    fun bpm(): Int =
        if (lastBpm > 0 && SystemClock.elapsedRealtime() - lastAt < STALE_MS) lastBpm else 0

    /**
     * Is the link actually carrying anything?
     *
     * Not the same question as `connected`, which is the platform's opinion and
     * can be stale — see [watchdog]. Not the same question as [bpm] either: a
     * strap off the chest is delivering frames and no pulse, and that link is
     * perfectly healthy.
     */
    private fun delivering(): Boolean =
        connected && lastFrameAt > 0L &&
                SystemClock.elapsedRealtime() - lastFrameAt < SILENT_LINK_MS

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

    /**
     * Hold a connection to [address], reconnecting for as long as it is wanted.
     *
     * Asking again for the address already held is a no-op rather than a
     * restart: settings re-apply on changes that have nothing to do with the
     * strap, and tearing a working link down to build the same one again would
     * lose a minute of readings each time.
     *
     * **A *working* link, though — not merely a client object.** This used to
     * hold off on `gatt != null`, which a background connection satisfies the
     * instant it is asked for and keeps satisfying whether or not it ever
     * reaches the strap. Re-applying the settings then did nothing, every
     * time, and the only escape was to change the address — which is precisely
     * what forgetting the strap and pairing it again does. Keyed on [connected]
     * the guard still protects a live link and no longer protects a dead one.
     *
     * Except that [connected] can be wrong in the same direction — see
     * [watchdog] — and then this guard was back to refusing the one thing that
     * would have fixed it. So it asks whether the link is *delivering*, not
     * whether the stack thinks it exists. The watchdog gets there on its own
     * within [SILENT_LINK_MS]; somebody who has walked over and pressed the
     * button should not have to wait for it.
     */
    @SuppressLint("MissingPermission")
    fun connect(address: String, name: String = "") =
        connect(listOf(Settings.Strap(address, name)))

    /**
     * Hold a connection to whichever of [straps] turns up.
     *
     * Asking again for the same set while one of them is delivering is a no-op,
     * for the reason above: settings re-apply on changes that have nothing to
     * do with heart rate, and dropping a working link to rebuild the same one
     * costs a minute of readings.
     */
    fun connect(straps: List<Settings.Strap>) {
        val wantedSet = straps.filter { it.addr.isNotBlank() }
        if (wantedSet.isEmpty()) return
        // Still hunting for, or holding, one of the monitors we would accept.
        if (wantedSet == known && (delivering() || seeking)) return
        disconnect()
        known = wantedSet
        // The most recently paired is the first lead; the rest are found by
        // name in the same scan — see seekCallback.
        wanted = wantedSet[0].addr
        wantedName = wantedSet[0].name
        seek()
    }

    /**
     * Find the strap advertising, then connect to the device that found it.
     *
     * **This is not an optimisation, it is the fix for a failure.** A strap
     * uses a random static address — the Coospo's `DA:…` has the top two bits
     * set, which is what says so — and [BluetoothAdapter.getRemoteDevice] takes
     * a bare string, so it hands back a device the stack assumes is *public*.
     * Connecting to that guesses the address type wrong and fails with status
     * 133 a few seconds later, which is exactly what this console did on every
     * restart. A [ScanResult]'s device carries the real address type, so
     * connecting to *that* works first time.
     *
     * It is also much the faster path. Straps advertise about once a second, so
     * a filtered scan finds one almost immediately, where the background
     * connection this used to fall back on took a measured 98–108 seconds.
     */
    @SuppressLint("MissingPermission")
    private fun seek() {
        val address = wanted
        if (address.isBlank() || seeking) return
        // Not while something is listening to the treadmill over Bluetooth.
        //
        // This console's radio cannot do both. Hunting for a strap is an
        // active scan followed by a background connect, and on 9 September
        // 2026 every single Zwift disconnection followed one: three sessions,
        // three `hr: looking` lines, three link supervision timeouts
        // (HCI 0x08) on the FTMS link within two minutes of each. A strap that
        // is already connected costs nothing to keep, so this only blocks the
        // hunt, and only while a client is actually subscribed.
        //
        // Put the strap on before pairing and you get both. Otherwise the
        // thing somebody is looking at wins over the thing that might be in a
        // drawer.
        if (gatt == null && busy()) {
            // Not "give up" — "not now". This used to simply return, and a
            // return here is the end of the line: every other path into seek()
            // is a one-shot posted by whatever just failed, so a hunt refused
            // while the radio was busy was a strap lost until the next
            // disconnect or restart. Ask again when the client has gone.
            handler.postDelayed({ if (wanted.isNotBlank() && gatt == null) seek() },
                               BUSY_RETRY_MS)
            return
        }
        val scanner = adapter?.bluetoothLeScanner ?: return

        /*
         * Two filters, which a scan treats as "or": the address we last saw,
         * and *any* heart rate service. The second is what finds a strap whose
         * address has rotated — see [wantedName] — and costs nothing extra,
         * since it rides along in the same scan. Which results are acceptable
         * is decided in [seekCallback], not here.
         */
        val filters = ArrayList<ScanFilter>(2)
        filters.add(ScanFilter.Builder().setDeviceAddress(address).build())
        if (known.any { it.name.isNotBlank() }) {
            filters.add(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build())
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(filters, settings, seekCallback)
        } catch (e: SecurityException) {
            Log.w(FitProConnection.TAG, "hr: no scan permission to seek")
            return
        } catch (e: IllegalArgumentException) {
            Log.w(FitProConnection.TAG, "hr: bad address $address")
            return
        }
        seeking = true
        val gen = ++seekGen
        Log.i(FitProConnection.TAG, "hr: looking for $address")

        // One start and one stop per seek. Android silently throttles an app
        // that starts more than five scans in thirty seconds, and a throttled
        // scan fails the same way an absent strap does.
        handler.postDelayed({
            // Mine, and still running. See [seekGen] for what happens without
            // that first half of the question.
            if (!seeking || gen != seekGen) return@postDelayed
            stopSeek()
            if (wanted.isNotBlank() && gatt == null && !busy()) {
                Log.i(FitProConnection.TAG, "hr: not advertising, waiting in the background")
                open(adapter?.getRemoteDevice(wanted), autoConnect = true)
            }
        }, SEEK_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopSeek() {
        if (!seeking) return
        seeking = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(seekCallback)
        } catch (e: SecurityException) {
            // The scan times out on its own; nothing here is recoverable.
        }
    }

    private val seekCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(type: Int, result: ScanResult) {
            if (!seeking || gatt != null) return
            val dev = result.device ?: return

            // The address if we recognise it, otherwise the name. The scan is
            // filtered on "any heart rate service" as well as on the address,
            // so this is where somebody else's strap is turned away.
            // Any monitor we know, by address if we recognise it and by name
            // otherwise. The scan is filtered on "any heart rate service" as
            // well as on one address, so this is where a stranger's strap is
            // turned away.
            val advertised = nameOf(dev, result)
            val match = known.firstOrNull { it.addr == dev.address }
                ?: known.firstOrNull { it.name.isNotBlank() && it.name == advertised }
                ?: return

            if (match.addr != dev.address) {
                // Write it down: the next hunt starts with a current lead, and
                // a direct connect to a known address is much the faster path
                // while it lasts.
                Log.i(FitProConnection.TAG,
                    "hr: ${match.name} is at ${dev.address} now, not ${match.addr} — " +
                            "its address rotates")
                onAddressChanged?.invoke(match.name, dev.address)
            } else if (match.addr != wanted) {
                Log.i(FitProConnection.TAG, "hr: found ${match.name}")
            }
            // The connection is to this one, whichever of the known it is.
            wanted = dev.address
            wantedName = match.name
            stopSeek()
            open(dev, autoConnect = false)
        }

        override fun onScanFailed(code: Int) {
            Log.w(FitProConnection.TAG, "hr: seek failed ($code)")
            seeking = false
            // Failing here usually means throttling, which passes. The
            // background connection is the thing that does not give up.
            handler.postDelayed({
                if (wanted.isNotBlank() && gatt == null) {
                    open(adapter?.getRemoteDevice(wanted), autoConnect = true)
                }
            }, DISCOVERY_RETRY_MS)
        }
    }

    /** Whatever this device calls itself, from the two places it might say. */
    @SuppressLint("MissingPermission")
    private fun nameOf(dev: BluetoothDevice, result: ScanResult): String =
        try {
            dev.name ?: result.scanRecord?.deviceName ?: ""
        } catch (e: SecurityException) {
            ""
        }

    /** Open the GATT client against a device we have, however we came by it. */
    @SuppressLint("MissingPermission")
    private fun open(dev: BluetoothDevice?, autoConnect: Boolean) {
        if (dev == null || wanted.isBlank()) return
        gatt = try {
            dev.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            Log.w(FitProConnection.TAG, "hr: no connect permission")
            null
        }
        Log.i(FitProConnection.TAG,
            "hr: opening ${if (autoConnect) "background" else "direct"} connection to ${dev.address}")
        if (autoConnect) giveBackgroundUpEventually(gatt)
    }

    /**
     * Notice a link that is up in name only, and throw it away.
     *
     * **Every recovery path in this class is driven by the platform telling us
     * the strap disconnected, and the platform does not always say so.** Turn a
     * strap off and on again and Android can keep the GATT client in
     * `STATE_CONNECTED` with nothing behind it — the peer is gone, no
     * notification ever arrives again, and no disconnect callback is delivered.
     *
     * Reported on 2026-09-12: pair the strap and the pulse appears; power the
     * strap off and back on and it never comes back until the strap is paired
     * again. Every route back was shut: [seek] refuses while `gatt != null`,
     * [giveBackgroundUpEventually] returns early while `connected`, and
     * [connect] returns early for the address it already has while `connected`.
     * All three were consulting the same field, and the field was wrong.
     *
     * So this watches the data instead of the claim, which is the same lesson
     * the belt taught: a command the board acknowledged is not a belt that
     * slowed down, and a socket the stack calls connected is not a strap that
     * is sending anything. [lastFrameAt] is the evidence; the stack's opinion
     * is not.
     *
     * Recycling rather than probing, because a GATT that has gone quiet does
     * not come back — the client is closed and the hunt starts again, exactly
     * as it does for a disconnect the platform *did* report.
     *
     * The cost: a strap left switched on, off the chest, that stops notifying
     * rather than sending zeros will be reconnected every [SILENT_LINK_MS].
     * That is radio churn for a strap nobody is wearing, which is worth less
     * than a strap nobody can use.
     */
    private fun armWatchdog() {
        if (watching) return
        watching = true
        handler.postDelayed(watchdog, LINK_CHECK_MS)
    }

    private val watchdog = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            watching = false
            if (wanted.isBlank()) return
            val g = gatt
            if (!connected || g == null) return        // a disconnect will drive it

            val silent = SystemClock.elapsedRealtime() - lastFrameAt
            if (lastFrameAt > 0L && silent > SILENT_LINK_MS) {
                Log.w(FitProConnection.TAG,
                    "hr: connected but nothing for ${silent / 1000}s — " +
                            "the link is dead, going to look again")
                connected = false
                lastBpm = 0
                lastFrameAt = 0L
                try { g.close() } catch (e: SecurityException) { }
                gatt = null
                seek()
                return
            }
            armWatchdog()
        }
    }

    /**
     * Put a clock on a background connection, because nothing else will.
     *
     * See [BACKGROUND_GIVE_UP_MS]. The client is closed rather than kept and
     * retried: a GATT that has sat unconnected is not a thing that starts
     * working, and holding it is what made the console think it already had a
     * strap.
     */
    @SuppressLint("MissingPermission")
    private fun giveBackgroundUpEventually(g: BluetoothGatt?) {
        if (g == null) return
        handler.postDelayed({
            // Superseded, no longer wanted, or it landed after all.
            if (g !== gatt || wanted.isBlank() || connected) return@postDelayed
            Log.i(FitProConnection.TAG,
                "hr: background connection never landed, looking again")
            try { g.close() } catch (e: SecurityException) { }
            gatt = null
            seek()
        }, BACKGROUND_GIVE_UP_MS)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        wanted = ""
        wantedName = ""
        known = emptyList()
        stopSeek()
        try {
            gatt?.close()
        } catch (e: SecurityException) {
            // Closing without permission is not recoverable and not worth a crash.
        }
        gatt = null
        connected = false
        lastBpm = 0
        lastFrameAt = 0L
        battery = -1
    }

    /**
     * Ask for services, and keep asking.
     *
     * A refusal here is not rare and not fatal, but it is silent: the platform
     * returns false and simply never calls back. Retrying is the whole fix —
     * see [DISCOVERY_DELAY_MS].
     */
    @SuppressLint("MissingPermission")
    private fun discover(g: BluetoothGatt, attempt: Int) {
        handler.postDelayed({
            if (g !== gatt) return@postDelayed          // superseded by a newer link
            val ok = try { g.discoverServices() } catch (e: SecurityException) { false }
            if (ok) return@postDelayed
            if (attempt < DISCOVERY_TRIES) {
                Log.i(FitProConnection.TAG, "hr: discovery busy, retrying ($attempt)")
                discover(g, attempt + 1)
            } else {
                Log.w(FitProConnection.TAG, "hr: service discovery refused $attempt times")
            }
        }, if (attempt == 1) DISCOVERY_DELAY_MS else DISCOVERY_RETRY_MS)
    }

    private val callback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) return
            connected = newState == BluetoothProfile.STATE_CONNECTED
            Log.i(FitProConnection.TAG,
                "hr: ${if (connected) "connected" else "disconnected"} (status $status)")

            if (connected) {
                // The clock starts here rather than at the first notification,
                // so a link that connects and never subscribes is caught too.
                // That has happened: every failure path in
                // onServicesDiscovered used to be silent, and the symptom was a
                // strap that said "Connected" and never sent a beat.
                lastFrameAt = SystemClock.elapsedRealtime()
                armWatchdog()
                discover(g, attempt = 1)
                return
            }

            lastBpm = 0
            if (wanted.isBlank()) return

            // Go looking again rather than retrying the address blind: a strap
            // that dropped out mid-walk is usually back within seconds, and a
            // seek catches it as soon as it advertises. The client is reopened
            // rather than reused — a GATT that has errored never recovers, and
            // reusing it is how you get a strap that cannot reconnect until the
            // app restarts.
            try { g.close() } catch (e: SecurityException) { }
            gatt = null
            handler.postDelayed({ if (wanted.isNotBlank()) seek() }, DISCOVERY_RETRY_MS)
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(FitProConnection.TAG, "hr: service discovery failed (status $status)")
                return
            }
            val ch = g.getService(SERVICE)?.getCharacteristic(MEASUREMENT) ?: run {
                Log.w(FitProConnection.TAG, "hr: no heart rate characteristic")
                return
            }
            try {
                g.setCharacteristicNotification(ch, true)
                // Subscribing is the descriptor write, not the call above — the
                // call only tells the local stack to pass notifications on.
                val d = ch.getDescriptor(CCCD) ?: run {
                    Log.w(FitProConnection.TAG, "hr: measurement has no CCCD, cannot subscribe")
                    return
                }
                @Suppress("DEPRECATION")
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                val ok = g.writeDescriptor(d)
                // Every one of these paths used to fail silently, which is why
                // a strap could report "Connected" and never a single beat.
                Log.i(FitProConnection.TAG,
                    if (ok) "hr: subscribed to measurements"
                    else "hr: subscribe refused by the stack")
            } catch (e: SecurityException) {
                return
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid != CCCD) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(FitProConnection.TAG, "hr: subscribe rejected by the strap (status $status)")
                return
            }
            // Battery is a nice-to-have and is read once, after the thing that
            // matters is already subscribed. Concurrent GATT operations are not
            // allowed, which is why it waits until here.
            val ch = g.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_LEVEL) ?: return
            try { g.readCharacteristic(ch) } catch (e: SecurityException) { }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (ch.uuid != MEASUREMENT) return
            lastFrameAt = SystemClock.elapsedRealtime()
            parse(ch.value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray
        ) {
            if (ch.uuid != MEASUREMENT) return
            // Here rather than in parse(), because the question this answers is
            // "did anything arrive", and parse() throws away the frames that
            // carry no pulse. Both overloads, because which one the platform
            // calls depends on the API level.
            lastFrameAt = SystemClock.elapsedRealtime()
            parse(value)
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
        // One line, once, when a strap starts reading: the difference between
        // "subscribed" and "actually delivering" is the whole feature.
        if (lastBpm == 0) Log.i(FitProConnection.TAG, "hr: reading $bpm bpm")
        lastBpm = bpm
        lastAt = SystemClock.elapsedRealtime()
    }
}
