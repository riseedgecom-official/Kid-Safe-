package com.example.kidsafe.child

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.provider.Settings
import com.example.kidsafe.core.Col
import com.example.kidsafe.core.Dns
import com.example.kidsafe.core.EventType
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Website filter. It is a "DNS-only" VPN: ONLY website-name lookups (DNS) are sent through this
 * service. All other traffic (pages, videos, passwords, messages) never touches it.
 *
 *  - lookups for sites on the parent's list get "does not exist"
 *  - everything else is asked to a family-safe DNS (Cloudflare 1.1.1.3, blocks adult + malware)
 *  - blocked lookups are reported to the parent (site name + time, nothing else)
 *
 * The byte-level packet work lives in `core` ([Dns]) where it is unit-checked.
 */
class WebFilterService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    @Volatile private var running = false
    @Volatile private var pool: ExecutorService? = null
    @Volatile private var lastEventAt = 0L
    private val lastLogged = ConcurrentHashMap<String, Long>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY
        if (!WebFilterConfig.isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val pfd = try {
            Builder()
                .setSession("KidSafe web protection")
                .addAddress(VPN_ADDRESS, 32)
                .addDnsServer(DNS_ADDRESS)
                .addRoute(DNS_ADDRESS, 32) // only the DNS address goes through the tunnel
                .setMtu(1500)
                .setBlocking(true)
                .establish()
        } catch (e: Exception) {
            null
        }
        if (pfd == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        tun = pfd
        running = true
        pool = Executors.newFixedThreadPool(6)
        reader = Thread({ readLoop(pfd) }, "kidsafe-dns").also { it.start() }
        publishStatus(true)
        return START_STICKY
    }

    /** Called by Android when the child (or another VPN app) switches our VPN off. */
    override fun onRevoke() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid != null && WebFilterConfig.isEnabled(this)) {
            FirebaseFirestore.getInstance().collection(Col.CHILDREN).document(uid).update(
                "lastEvent",
                hashMapOf<String, Any>("type" to EventType.FILTER_OFF, "ts" to FieldValue.serverTimestamp()),
            )
        }
        stopFilter()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopFilter()
        super.onDestroy()
    }

    private fun stopFilter() {
        if (!running && tun == null) return
        running = false
        runCatching { tun?.close() }
        tun = null
        reader?.interrupt()
        reader = null
        pool?.shutdownNow()
        pool = null
        publishStatus(false)
    }

    private fun readLoop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val buf = ByteArray(32767)
        try {
            while (running) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                val packet = buf.copyOf(n)
                pool?.execute { handlePacket(packet, output) }
            }
        } catch (e: Exception) {
            // The tunnel was closed; nothing to do.
        }
    }

    private fun handlePacket(p: ByteArray, out: FileOutputStream) {
        try {
            val query = Dns.parseQuery(p) ?: return
            val domain = query.domain

            var blockedBy: String? = null
            val response: ByteArray
            if (WebFilterConfig.matchesCustom(this, domain)) {
                response = Dns.nxdomain(query.dns, query.questionEnd)
                blockedBy = "parent_list"
            } else {
                response = forward(query.dns) ?: return
                if (Dns.isSinkholed(response)) blockedBy = "unsafe_site"
            }
            if (response.size > Dns.MAX_REPLY_BYTES) return

            if (blockedBy != null) report(domain, blockedBy)
            val reply = Dns.buildReply(p, query.clientPort, response)
            synchronized(out) { out.write(reply) }
        } catch (e: Exception) {
            // Ignore a bad packet.
        }
    }

    /** Asks the family-safe DNS. Returns null if it can't be reached. */
    private fun forward(dns: ByteArray): ByteArray? {
        for (upstream in UPSTREAMS) {
            try {
                DatagramSocket().use { s ->
                    protect(s)
                    s.soTimeout = 2500
                    s.send(DatagramPacket(dns, dns.size, InetAddress.getByName(upstream), 53))
                    val buf = ByteArray(4096)
                    val resp = DatagramPacket(buf, buf.size)
                    s.receive(resp)
                    return buf.copyOf(resp.length)
                }
            } catch (e: Exception) {
                // try the next one
            }
        }
        return null
    }

    private fun report(domain: String, source: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val now = System.currentTimeMillis()
        val prev = lastLogged[domain]
        if (prev != null && now - prev < LOG_EVERY_MS) return
        lastLogged[domain] = now

        val ref = FirebaseFirestore.getInstance().collection(Col.CHILDREN).document(uid)
        ref.collection(Col.BLOCKED).add(
            hashMapOf<String, Any>(
                "domain" to domain,
                "source" to source,
                "ts" to FieldValue.serverTimestamp(),
            ),
        )
        if (now - lastEventAt >= EVENT_EVERY_MS) {
            lastEventAt = now
            // "place" is the generic "what" field of an event; for web events it is the site name.
            ref.update(
                "lastEvent",
                hashMapOf<String, Any>(
                    "type" to EventType.WEB_BLOCKED,
                    "place" to domain,
                    "ts" to FieldValue.serverTimestamp(),
                ),
            )
        }
    }

    private fun publishStatus(active: Boolean) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val privateDns = runCatching {
            Settings.Global.getString(contentResolver, "private_dns_mode")
        }.getOrNull().orEmpty()
        FirebaseFirestore.getInstance().collection(Col.CHILDREN).document(uid).update(
            "webFilter",
            hashMapOf<String, Any>(
                "active" to active,
                "privateDns" to privateDns,
                "at" to FieldValue.serverTimestamp(),
            ),
        )
    }

    private companion object {
        const val VPN_ADDRESS = "10.111.222.1"
        const val DNS_ADDRESS = "10.111.222.2"
        val UPSTREAMS = listOf("1.1.1.3", "1.0.0.3")
        const val LOG_EVERY_MS = 10 * 60_000L
        const val EVENT_EVERY_MS = 5 * 60_000L
    }
}
