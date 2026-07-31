package dev.stride.usbprobe

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * STRIDE USB probe — READ ONLY.
 *
 * Answers three questions about the treadmill control board:
 *   1. Can a normal app claim it via UsbManager?
 *   2. Does it stream input reports unprompted?
 *   3. What do those reports look like, and which bytes move?
 *
 * This deliberately never writes to the device. Nothing here can move the belt.
 */
class MainActivity : Activity() {

    companion object {
        const val TAG = "StrideProbe"
        const val ACTION_USB_PERMISSION = "dev.stride.usbprobe.USB_PERMISSION"

        // ICON Fitness "ICON Generic HID"
        const val VID = 0x213c
        const val PID = 0x0002

        const val MAX_LINES = 14
    }

    private lateinit var headerView: TextView
    private lateinit var maskView: TextView
    private lateinit var logView: TextView

    @Volatile private var running = false
    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null

    /** Per-offset record of every distinct value seen — the decoding aid. */
    private val seenValues = HashMap<Int, MutableSet<Int>>()
    private var firstReport: ByteArray? = null
    private val lines = ArrayDeque<String>()
    private var reportCount = 0L
    private var lastRendered = ""

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            if (granted && device != null) {
                append("permission GRANTED")
                openAndRead(device)
            } else {
                append("permission DENIED — cannot proceed")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(permissionReceiver, filter)
        }

        findAndRequest()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(24, 24, 24, 24)
        }

        headerView = TextView(this).apply {
            setTextColor(Color.parseColor("#7FDBFF"))
            textSize = 15f
            typeface = Typeface.MONOSPACE
            text = "STRIDE USB probe — read only\nscanning…"
        }
        root.addView(headerView)

        maskView = TextView(this).apply {
            setTextColor(Color.parseColor("#FFDC00"))
            textSize = 15f
            typeface = Typeface.MONOSPACE
            setPadding(0, 16, 0, 16)
        }
        root.addView(maskView)

        logView = TextView(this).apply {
            setTextColor(Color.parseColor("#E0E0E0"))
            textSize = 15f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP
        }
        val scroll = ScrollView(this).apply { addView(logView) }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0
            ).apply { weight = 1f }
        )

        setContentView(root)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
    }

    private fun findAndRequest() {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val all = manager.deviceList.values

        Log.i(TAG, "enumerating ${all.size} usb device(s)")
        all.forEach {
            Log.i(TAG, "  ${it.deviceName} vid=0x${it.vendorId.toString(16)} " +
                    "pid=0x${it.productId.toString(16)} ${it.manufacturerName} / ${it.productName}")
        }

        val device = all.firstOrNull { it.vendorId == VID && it.productId == PID }
        if (device == null) {
            setHeader(
                "STRIDE USB probe — read only\n" +
                "TARGET NOT FOUND (looking for ${hex4(VID)}:${hex4(PID)})\n" +
                "saw ${all.size} device(s): " +
                all.joinToString { "${hex4(it.vendorId)}:${hex4(it.productId)}" }
            )
            append("Is com.ifit.eru still holding the device? It must stay disabled.")
            return
        }

        describe(device)

        if (manager.hasPermission(device)) {
            append("already have permission")
            openAndRead(device)
        } else {
            append("requesting permission…")
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_IMMUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags
            )
            manager.requestPermission(device, pi)
        }
    }

    /** Dump the descriptor tree — needed to know which endpoint to talk to later. */
    private fun describe(device: UsbDevice) {
        val sb = StringBuilder()
        sb.append("STRIDE USB probe — read only\n")
        sb.append("${device.manufacturerName} / ${device.productName}\n")
        sb.append("vid=${hex4(device.vendorId)} pid=${hex4(device.productId)} ")
        sb.append("class=${device.deviceClass} ifaces=${device.interfaceCount}\n")

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            sb.append("  if$i class=${intf.interfaceClass} sub=${intf.interfaceSubclass} ")
            sb.append("proto=${intf.interfaceProtocol} eps=${intf.endpointCount}\n")
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN " else "OUT"
                sb.append("    ep$e $dir type=${epType(ep.type)} ")
                sb.append("addr=0x${ep.address.toString(16)} max=${ep.maxPacketSize} ")
                sb.append("interval=${ep.interval}\n")
            }
        }
        setHeader(sb.toString().trimEnd())
        Log.i(TAG, sb.toString())
    }

    private fun openAndRead(device: UsbDevice) {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager

        val conn = manager.openDevice(device)
        if (conn == null) {
            append("openDevice() returned null — another app holds the device")
            return
        }
        connection = conn

        // Prefer an interface that actually has an interrupt IN endpoint.
        var chosen: UsbInterface? = null
        var inEp: UsbEndpoint? = null
        outer@ for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.direction == UsbConstants.USB_DIR_IN &&
                    (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT ||
                     ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK)
                ) {
                    chosen = intf; inEp = ep; break@outer
                }
            }
        }

        if (chosen == null || inEp == null) {
            append("no IN endpoint found — cannot read")
            return
        }

        // force=true detaches any kernel driver already bound to the interface.
        if (!conn.claimInterface(chosen, true)) {
            append("claimInterface() FAILED")
            return
        }
        claimedInterface = chosen
        append("claimed if=${chosen.id}, reading ep 0x${inEp.address.toString(16)} " +
                "(${inEp.maxPacketSize} bytes)")

        // A HID device declares its own report layout. Read it before guessing
        // at bytes — this is a standard read-only control transfer.
        dumpRawDescriptors(conn)
        fetchHidReportDescriptor(conn, chosen.id)

        running = true
        val ep = inEp
        thread(name = "usb-read") {
            val buf = ByteArray(ep.maxPacketSize.coerceAtLeast(8))
            var consecutiveTimeouts = 0
            while (running) {
                val n = conn.bulkTransfer(ep, buf, buf.size, 1000)
                when {
                    n > 0 -> {
                        consecutiveTimeouts = 0
                        onReport(buf.copyOf(n))
                    }
                    n == 0 -> { /* zero-length packet; ignore */ }
                    else -> {
                        consecutiveTimeouts++
                        if (consecutiveTimeouts == 5) {
                            append("no data after 5s — device may be silent until polled")
                        }
                    }
                }
            }
        }
    }

    /** Device + config descriptors, exactly as the device reported them. */
    private fun dumpRawDescriptors(conn: UsbDeviceConnection) {
        val raw = conn.rawDescriptors
        if (raw == null) {
            append("rawDescriptors unavailable")
            return
        }
        Log.i(TAG, "RAW DESCRIPTORS (${raw.size} bytes)")
        raw.toList().chunked(16).forEachIndexed { i, chunk ->
            Log.i(TAG, "  %04X  %s".format(i * 16, chunk.joinToString(" ") { "%02X".format(it) }))
        }
        append("raw descriptors: ${raw.size} bytes → logcat")
    }

    /**
     * GET_DESCRIPTOR(HID REPORT) — bmRequestType 0x81 (in, standard, interface),
     * bRequest 0x06, wValue 0x2200 (report descriptor, index 0), wIndex = interface.
     */
    private fun fetchHidReportDescriptor(conn: UsbDeviceConnection, ifaceId: Int) {
        val buf = ByteArray(4096)
        val n = conn.controlTransfer(0x81, 0x06, 0x2200, ifaceId, buf, buf.size, 2000)
        if (n <= 0) {
            append("HID report descriptor: request failed (rc=$n)")
            return
        }
        val desc = buf.copyOf(n)
        append("HID report descriptor: $n bytes → logcat")
        Log.i(TAG, "HID REPORT DESCRIPTOR ($n bytes)")
        desc.toList().chunked(16).forEachIndexed { i, chunk ->
            Log.i(TAG, "  %04X  %s".format(i * 16, chunk.joinToString(" ") { "%02X".format(it) }))
        }
        Log.i(TAG, "--- decoded ---")
        decodeHidReportDescriptor(desc).forEach { Log.i(TAG, "  $it") }
    }

    /**
     * Minimal HID item walker. Enough to surface Report ID / Size / Count and
     * usages, which is what tells us how the treadmill frames its data.
     */
    private fun decodeHidReportDescriptor(d: ByteArray): List<String> {
        val out = ArrayList<String>()
        var i = 0
        var indent = 0
        while (i < d.size) {
            val b = d[i].toInt() and 0xFF
            val size = when (b and 0x03) { 0 -> 0; 1 -> 1; 2 -> 2; else -> 4 }
            val type = (b shr 2) and 0x03
            val tag = (b shr 4) and 0x0F
            var value = 0L
            for (k in 0 until size) {
                if (i + 1 + k < d.size) {
                    value = value or ((d[i + 1 + k].toLong() and 0xFF) shl (8 * k))
                }
            }

            val name = when (type) {
                0 -> when (tag) { // Main
                    0x8 -> "INPUT"; 0x9 -> "OUTPUT"; 0xB -> "FEATURE"
                    0xA -> "COLLECTION"; 0xC -> "END_COLLECTION"
                    else -> "MAIN(tag=$tag)"
                }
                1 -> when (tag) { // Global
                    0x0 -> "USAGE_PAGE"; 0x1 -> "LOGICAL_MIN"; 0x2 -> "LOGICAL_MAX"
                    0x3 -> "PHYSICAL_MIN"; 0x4 -> "PHYSICAL_MAX"; 0x5 -> "UNIT_EXP"
                    0x6 -> "UNIT"; 0x7 -> "REPORT_SIZE"; 0x8 -> "REPORT_ID"
                    0x9 -> "REPORT_COUNT"
                    else -> "GLOBAL(tag=$tag)"
                }
                2 -> when (tag) { // Local
                    0x0 -> "USAGE"; 0x1 -> "USAGE_MIN"; 0x2 -> "USAGE_MAX"
                    else -> "LOCAL(tag=$tag)"
                }
                else -> "RESERVED(tag=$tag)"
            }

            if (name == "END_COLLECTION" && indent > 0) indent--
            out.add("  ".repeat(indent) + "$name 0x${value.toString(16).uppercase()} ($value)")
            if (name == "COLLECTION") indent++

            i += 1 + size
        }
        return out
    }

    private fun onReport(report: ByteArray) {
        reportCount++

        if (firstReport == null) firstReport = report.copyOf()
        report.forEachIndexed { i, b ->
            seenValues.getOrPut(i) { HashSet() }.add(b.toInt() and 0xFF)
        }

        val hex = report.joinToString(" ") { "%02X".format(it) }
        // Only surface changes — a static report scrolling at 100 Hz tells us nothing.
        if (hex != lastRendered) {
            lastRendered = hex
            Log.i(TAG, "report#$reportCount $hex")
            append(hex)
            runOnUiThread { maskView.text = buildMask() }
        }
    }

    /**
     * Marks which byte offsets have taken more than one value. Those are the
     * live fields — speed, incline, distance. Static offsets are header/padding.
     */
    private fun buildMask(): String {
        val volatileOffsets = seenValues.filterValues { it.size > 1 }.keys.sorted()
        val sb = StringBuilder("reports=$reportCount  changing offsets: ")
        sb.append(if (volatileOffsets.isEmpty()) "(none yet)" else volatileOffsets.joinToString(","))
        sb.append("\n")
        volatileOffsets.take(8).forEach { off ->
            val vals = seenValues[off]!!
            sb.append("  [$off] ${vals.size} distinct, range ${vals.min()}..${vals.max()}\n")
        }
        return sb.toString().trimEnd()
    }

    private fun setHeader(s: String) = runOnUiThread { headerView.text = s }

    private fun append(s: String) {
        Log.i(TAG, s)
        runOnUiThread {
            lines.addLast(s)
            while (lines.size > MAX_LINES) lines.removeFirst()
            logView.text = lines.joinToString("\n")
        }
    }

    override fun onDestroy() {
        running = false
        claimedInterface?.let { connection?.releaseInterface(it) }
        connection?.close()
        try { unregisterReceiver(permissionReceiver) } catch (_: IllegalArgumentException) {}
        super.onDestroy()
    }

    private fun hex4(v: Int) = "0x%04X".format(v)

    private fun epType(t: Int) = when (t) {
        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "control"
        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isoc"
        UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
        UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
        else -> "type$t"
    }
}
