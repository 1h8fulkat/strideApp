package dev.stride.hud

import android.content.Context
import android.util.Base64
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertPathValidator
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Map tiles for the route view, fetched by the console and cached on it.
 *
 * The page asks for `https://tiles.stride/{z}/{x}/{y}.png` and this answers.
 * The scheme is matched on not at all and the host is never resolved — nothing
 * connects, so there is no certificate to check. https is used only so that no
 * mixed-content rule in the browser has an opinion about a `file://` page
 * loading it.
 * Nothing in the WebView ever talks to a tile server, and that is the whole
 * design rather than an implementation detail:
 *
 *  * **The cache is here, not in the WebView.** Walking the same route twice
 *    costs no traffic the second time, the map redraws instantly, and Wi-Fi
 *    dropping mid-walk leaves the tiles you have already seen on screen. The
 *    WebView's own HTTP cache is small, shared with everything else and
 *    cleared whenever it feels like it.
 *  * **The TLS handshake is here, not in the WebView.** This console's clock
 *    reads 2022 and its trust store is older than Let's Encrypt's root, and
 *    both of those are fatal to an HTTPS tile fetch. In Kotlin they are
 *    fixable; inside a WebView's certificate error callback they are a choice
 *    between trusting everything and drawing nothing. See [tolerantSsl].
 *  * **A failed tile is a blank tile.** Not an error tile, and never a hang.
 *    A route drawn over empty ground still tells you where you are; a grid of
 *    broken images tells you the app is broken.
 *
 * Called on a WebView network thread, one call per tile, so blocking here is
 * expected — but it is bounded by [TIMEOUT_MS], because the thread that is
 * blocked is the one that would otherwise be painting the map.
 */
class Tiles(context: Context, private val cfg: Settings) {

    private val dir = File(context.filesDir, "tiles")
    private val res = context.resources

    /** One fetch per tile, however many times the page asks for it. */
    private val inFlight = ConcurrentHashMap<String, Any>()

    /** Eviction runs on its own thread: it walks the cache, and the caller is
     *  a paint. */
    private val housekeeping = Executors.newSingleThreadExecutor()
    private val writes = AtomicInteger(0)

    @Volatile private var ssl: SSLContext? = null

    /**
     * A tile request, or null if this is not one.
     *
     * Null hands the request back to the WebView, which is right for every
     * other URL the page loads — the interface itself is a `file://` document
     * with local assets.
     */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        if (!HOST.equals(uri.host, ignoreCase = true)) return null

        val m = KEY.matchEntire(uri.path ?: "") ?: return blank()
        val z = m.groupValues[1].toIntOrNull() ?: return blank()
        val x = m.groupValues[2].toIntOrNull() ?: return blank()
        val y = m.groupValues[3].toIntOrNull() ?: return blank()
        if (z !in 0..22) return blank()
        val span = 1 shl z
        if (x < 0 || y < 0 || x >= span || y >= span) return blank()

        val file = File(dir, "$z/$x/$y.png")
        if (file.isFile && file.length() > 0L) return png(FileInputStream(file))

        // One thread fetches; anyone else asking for the same tile waits for it
        // and then finds it on disk. Without this, a pan that brings a tile
        // back into view while it is still arriving fetches it twice.
        //
        // `putIfAbsent` rather than `getOrPut`: the Kotlin extension is two
        // operations and two threads can come away holding different locks,
        // which is a single-flight gate that lets everything through.
        val key = "$z/$x/$y"
        val fresh = Any()
        val gate = inFlight.putIfAbsent(key, fresh) ?: fresh
        try {
            synchronized(gate) {
                if (file.isFile && file.length() > 0L) return png(FileInputStream(file))
                val bytes = fetch(z, x, y) ?: return blank()
                store(file, bytes)
                return png(ByteArrayInputStream(bytes))
            }
        } finally {
            inFlight.remove(key, gate)
        }
    }

    /** Metres of disk in use, for the settings screen to report. */
    fun cachedBytes(): Long =
        try { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        catch (e: Exception) { 0L }

    fun clearCache() {
        housekeeping.execute {
            try { dir.deleteRecursively() } catch (e: Exception) {
                Log.w(TAG, "cannot clear cache (${e.message})")
            }
        }
    }

    // --- the network ---------------------------------------------------------

    private fun fetch(z: Int, x: Int, y: Int): ByteArray? {
        val url = cfg.mapTileUrl()
            .replace("{z}", z.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())
        val parsed = try { URL(url) } catch (e: Exception) {
            Log.w(TAG, "tile url is not a url: $url")
            return null
        }
        if (parsed.protocol != "http" && parsed.protocol != "https") return null

        var conn: HttpURLConnection? = null
        try {
            conn = (parsed.openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                // Tile terms generally ask an application to say what it is, and
                // a stock WebView user agent is the thing most likely to be
                // rate limited on sight.
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "image/png,image/*;q=0.8")
            }
            if (conn is HttpsURLConnection) conn.sslSocketFactory = tolerantSsl().socketFactory
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "tile $z/$x/$y: HTTP $code")
                return null
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            return if (bytes.isEmpty()) null else bytes
        } catch (e: Exception) {
            // Expected, and often: no network, no DNS, a certificate this
            // console will not accept, a server that has had enough of us. The
            // map draws the route on blank ground and the walk carries on.
            Log.w(TAG, "tile $z/$x/$y: ${e.javaClass.simpleName} ${e.message}")
            return null
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { }
        }
    }

    /**
     * TLS that works on a console whose clock and trust store are both from
     * the wrong decade.
     *
     * Two problems, both of them the machine's rather than the tile server's:
     *
     *  1. **The clock reads 2022.** Every certificate on the internet is
     *     therefore "not yet valid", and the correct-looking fix — setting the
     *     clock — does not survive being switched off at the wall on some of
     *     these boards.
     *  2. **The trust store predates ISRG Root X1**, which Android only shipped
     *     from 7.1.1. This console is on 7.0, so a Let's Encrypt chain — which
     *     is most of the web, OpenStreetMap included — has no anchor to reach.
     *
     * So: try the platform's own trust manager first and use it whenever it is
     * happy, which is the ordinary case and the fully-checked one. Only if it
     * refuses does this re-validate the chain itself, against the system
     * anchors **plus** the bundled ISRG root, and at a date taken from the
     * certificate rather than from the console's idea of now.
     *
     * What that gives up, stated plainly: a certificate that is genuinely
     * expired is accepted, because nothing here can tell that case apart from
     * a clock that is wrong. Signatures, the chain to a real anchor, and the
     * hostname are all still checked. It applies to map tile fetches and to
     * nothing else in the app, and the worst a forged tile server can do is
     * draw the wrong hill behind the route.
     */
    private fun tolerantSsl(): SSLContext {
        ssl?.let { return it }
        synchronized(this) {
            ssl?.let { return it }
            val platform = platformTrust()
            val anchors = anchors()
            val tm = object : X509TrustManager {
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    if (platform != null) {
                        try {
                            platform.checkServerTrusted(chain, authType)
                            return
                        } catch (e: CertificateException) {
                            Log.i(TAG, "tiles: platform trust said no (${e.message}); " +
                                    "re-checking the chain at the certificate's own date")
                        }
                    }
                    if (anchors.isEmpty()) throw CertificateException("no trust anchors")
                    // Drop a self-signed tail: a server that sends its own root
                    // fails PKIX validation with "trust anchor found in path",
                    // which has nothing to do with whether the chain is good.
                    val path = chain.filterNot { it.issuerX500Principal == it.subjectX500Principal }
                    if (path.isEmpty()) throw CertificateException("nothing to validate")
                    val params = PKIXParameters(anchors)
                    params.isRevocationEnabled = false
                    // A day past the leaf's own notBefore is inside the validity
                    // of every certificate in a chain that was ever valid, and
                    // owes nothing to the system clock.
                    params.date = Date(path[0].notBefore.time + 86_400_000L)
                    try {
                        CertPathValidator.getInstance("PKIX").validate(
                            CertificateFactory.getInstance("X.509").generateCertPath(path), params)
                    } catch (e: Exception) {
                        throw CertificateException("chain rejected: ${e.message}")
                    }
                }

                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                    throw CertificateException("client certificates are not used here")
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> =
                    platform?.acceptedIssuers ?: emptyArray()
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(tm), null)
            ssl = ctx
            return ctx
        }
    }

    private fun platformTrust(): X509TrustManager? = try {
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .firstOrNull()
    } catch (e: Exception) {
        Log.w(TAG, "no platform trust manager (${e.message})")
        null
    }

    /** Every system CA, plus the roots this console is too old to have. */
    private fun anchors(): Set<TrustAnchor> {
        val out = HashSet<TrustAnchor>()
        try {
            val ks = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            for (alias in ks.aliases()) {
                (ks.getCertificate(alias) as? X509Certificate)?.let { out.add(TrustAnchor(it, null)) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot read the system CA store (${e.message})")
        }
        for (id in BUNDLED_ROOTS) {
            try {
                res.openRawResource(id).use {
                    val c = CertificateFactory.getInstance("X.509").generateCertificate(it)
                    (c as? X509Certificate)?.let { cert -> out.add(TrustAnchor(cert, null)) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "cannot read a bundled root (${e.message})")
            }
        }
        return out
    }

    // --- the disk ------------------------------------------------------------

    private fun store(file: File, bytes: ByteArray) {
        try {
            file.parentFile?.mkdirs()
            // Written aside and renamed: a tile half-written when the console
            // is switched off at the wall would be cached as a corrupt image
            // and served from disk forever after.
            val tmp = File(file.parentFile, file.name + ".part")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (e: Exception) {
            Log.w(TAG, "cannot cache a tile (${e.message})")
            return
        }
        if (writes.incrementAndGet() % SWEEP_EVERY == 0) housekeeping.execute { sweep() }
    }

    /**
     * Keep the cache under the configured size, oldest fetched first.
     *
     * By fetch time rather than by last use: knowing the last use means writing
     * to the file on every read, and a read here happens while the map is being
     * painted. Age is the cheap answer and very nearly the same one — the tiles
     * you keep re-fetching are the routes you keep walking.
     */
    private fun sweep() {
        try {
            val cap = cfg.mapCacheMb() * 1024L * 1024L
            val files = dir.walkTopDown().filter { it.isFile }.toMutableList()
            var total = files.sumOf { it.length() }
            if (total <= cap) return
            // Down to nine tenths, not to exactly the cap: sweeping to the line
            // means sweeping again on the next tile.
            val target = cap / 10 * 9
            files.sortBy { it.lastModified() }
            var dropped = 0
            for (f in files) {
                if (total <= target) break
                val n = f.length()
                if (f.delete()) { total -= n; dropped++ }
            }
            Log.i(TAG, "tile cache: dropped $dropped, now ${total / 1024 / 1024} MB of " +
                    "${cap / 1024 / 1024} MB")
        } catch (e: Exception) {
            Log.w(TAG, "cache sweep failed (${e.message})")
        }
    }

    // --- responses -----------------------------------------------------------

    private fun png(stream: InputStream) = WebResourceResponse(
        "image/png", null, 200, "OK",
        mapOf("Cache-Control" to "max-age=31536000",
              "Access-Control-Allow-Origin" to "*"),
        stream)

    /**
     * A transparent tile, served with a 200.
     *
     * Deliberately not a 404. Leaflet's answer to a failed tile is to leave the
     * grid empty and keep asking, and a map that is visibly missing pieces
     * reads as a fault in the app rather than in the network. One transparent
     * pixel stretched over the tile leaves the route line and the dot on a
     * plain field, which is a legible thing to walk to.
     */
    private fun blank() = WebResourceResponse(
        "image/png", null, 200, "OK",
        mapOf("Cache-Control" to "no-store",
              "Access-Control-Allow-Origin" to "*"),
        ByteArrayInputStream(BLANK_PNG))

    companion object {
        private const val TAG = "StrideTiles"

        /** The host the page asks for. Never resolved: this answers first. */
        const val HOST = "tiles.stride"

        /**
         * The roots this console does not have and the web now needs.
         *
         * Measured against the trust store pulled off this machine — 148
         * certificates, none of them ISRG — by validating each provider's real
         * chain against it:
         *
         *     tile.openstreetmap.org        GlobalSign     trusted as shipped
         *     tile.opentopomap.org          Let's Encrypt  needs X1
         *     tile-cyclosm.openstreetmap.fr Let's Encrypt  needs X1
         *
         * X1 is what both Let's Encrypt chains happen to terminate at today,
         * because the servers send the cross-signed path. X2 is here for the
         * day they stop: it is the newer root, it is what a fresh chain
         * anchors at, and finding out the hard way means a blank map.
         */
        private val BUNDLED_ROOTS = intArrayOf(R.raw.isrg_root_x1, R.raw.isrg_root_x2)

        private val KEY = Regex("^/(\\d{1,2})/(\\d{1,7})/(\\d{1,7})\\.png$")
        private const val TIMEOUT_MS = 6000
        private const val SWEEP_EVERY = 64
        private const val UA =
            "STRIDE/0.8 (treadmill console; https://github.com/keranm/strideApp)"

        /** 1x1 transparent PNG. */
        private val BLANK_PNG: ByteArray = Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk" +
            "YPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==", Base64.DEFAULT)
    }
}
