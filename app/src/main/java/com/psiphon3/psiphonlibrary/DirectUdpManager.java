/*
 * DirectUdpManager.java
 * Gaming Mode – Phase 6: GearUP-style dualChannel UDP.
 *
 * Inspired by GearUP Booster's libdivider2 dualChannel architecture:
 * each outgoing game UDP packet is transmitted simultaneously over the
 * WiFi interface AND the Cellular interface.  Whichever copy reaches
 * the game server first wins; if one path drops the packet the other
 * delivers it, eliminating the 999 ms retransmission spikes that appear
 * when only a single physical path is used.
 *
 * Architecture
 * ─────────────────────────────────────────────────────────────────────
 *   TUN → tun2socks.c (modified) → onGameUdpPacket()
 *     ┌─→ WiFi  DatagramSocket ──→ game server
 *     └─→ Cell  DatagramSocket ──→ game server   (if cellular available)
 *   game server → both receive threads → injectGameUdpResponse() → TUN
 *
 * Response deduplication
 * ─────────────────────────────────────────────────────────────────────
 * Game UDP protocols (ENet / KCP / custom) include their own seq numbers
 * and silently drop duplicates, so injecting both responses is harmless.
 * The first to arrive is consumed; the second (usually < 2 ms later) is
 * discarded by the game's transport layer.
 *
 * Threading model
 * ─────────────────────────────────────────────────────────────────────
 * • onGameUdpPacket() is called from the tun2socks BReactor thread and
 *   MUST return immediately — it only enqueues the packet.
 * • A single sender thread drains the queue and writes to both sockets.
 * • Each (session × interface) has its own receiver thread.
 *
 * Session key: srcIpHO + ":" + srcPortHO  (one socket-pair per game flow).
 * Socket is connectionless — it can send to and receive from any server
 * address, so a single session handles games that use multiple server IPs.
 * The actual sender address from each datagram is used when injecting the
 * response (not the original dst), which handles NAT / load-balancers.
 *
 * All IP/port values exchanged with native code are in host byte order
 * (C side converts with ntohl/ntohs before calling in, htonl/htons on
 * the way back into the synthesised IPv4+UDP packet).
 */

package com.psiphon3.psiphonlibrary;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import ca.psiphon.Tun2SocksJniLoader;

public class DirectUdpManager {

    private static final String TAG = "DirectUdpManager";

    /** 65535 - 20 (IPv4 hdr) - 8 (UDP hdr) */
    private static final int  MAX_UDP_PAYLOAD = 65507;
    /** Prune sessions idle longer than this. */
    private static final long SOCKET_IDLE_MS  = 60_000L;
    /** Per-socket receive timeout — lets the loop check sRunning regularly. */
    private static final int  RECV_TIMEOUT_MS = 3_000;
    /** Outbound queue capacity. */
    private static final int  QUEUE_CAPACITY  = 2048;

    // ── module state ─────────────────────────────────────────────────────────
    private static volatile VpnService sVpnService;
    private static volatile Network    sWifiNetwork;   // null if not available
    private static volatile Network    sCellNetwork;   // null if not available
    private static final AtomicBoolean sRunning     = new AtomicBoolean(false);
    private static volatile Thread     sSenderThread;

    private static final BlockingQueue<UdpPacket>                  sOutQueue   =
            new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private static final ConcurrentHashMap<String, ManagedSession> sSessionMap =
            new ConcurrentHashMap<>();

    // ── inner types ──────────────────────────────────────────────────────────

    private static final class UdpPacket {
        final int    srcIpHO, srcPortHO, dstIpHO, dstPortHO;
        final byte[] payload;
        UdpPacket(int si, int sp, int di, int dp, byte[] pl) {
            srcIpHO = si; srcPortHO = sp; dstIpHO = di; dstPortHO = dp; payload = pl;
        }
    }

    /**
     * One session = one (srcIp, srcPort) game flow.
     * primary   = WiFi socket  (always present)
     * secondary = Cellular socket (null when only one interface exists)
     */
    private static final class ManagedSession {
        final DatagramSocket primary;
        final DatagramSocket secondary;      // may be null
        volatile long        lastUsedMs;

        ManagedSession(DatagramSocket p, DatagramSocket s) {
            primary = p; secondary = s;
            lastUsedMs = System.currentTimeMillis();
        }

        void close() {
            try { primary.close();                    } catch (Exception ignored) {}
            try { if (secondary != null) secondary.close(); } catch (Exception ignored) {}
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Start the direct-UDP bypass with dualChannel support.
     * Idempotent — safe to call multiple times (no-op if already running).
     *
     * @param vpnService the active VpnService used to protect sockets and
     *                   to query the ConnectivityManager for network handles.
     */
    public static void start(VpnService vpnService) {
        if (!sRunning.compareAndSet(false, true)) return;

        sVpnService = vpnService;
        detectNetworks(vpnService);

        String mode;
        if (sWifiNetwork != null && sCellNetwork != null) {
            mode = "dualChannel (WiFi + Cellular)";
        } else if (sWifiNetwork != null) {
            mode = "single-path WiFi";
        } else if (sCellNetwork != null) {
            mode = "single-path Cellular";
        } else {
            mode = "single-path (default network)";
        }
        Log.i(TAG, "DirectUdpManager started — " + mode);

        sSenderThread = new Thread(DirectUdpManager::senderLoop, "DirectUdpSender");
        sSenderThread.setDaemon(true);
        sSenderThread.start();
    }

    /** Stop all sockets and threads. Idempotent. */
    public static void stop() {
        if (!sRunning.compareAndSet(true, false)) return;
        sVpnService  = null;
        sWifiNetwork = null;
        sCellNetwork = null;
        if (sSenderThread != null) {
            sSenderThread.interrupt();
            sSenderThread = null;
        }
        for (ManagedSession ms : sSessionMap.values()) ms.close();
        sSessionMap.clear();
        sOutQueue.clear();
        Log.i(TAG, "DirectUdpManager stopped");
    }

    /**
     * Called from the tun2socks BReactor thread (JNI).  Must return quickly.
     * All IP/port values are in host byte order.
     */
    public static void onGameUdpPacket(int srcIpHO, int srcPortHO,
                                       int dstIpHO, int dstPortHO,
                                       byte[] payload) {
        if (!sRunning.get() || payload == null) return;
        byte[] copy = new byte[payload.length];
        System.arraycopy(payload, 0, copy, 0, payload.length);
        if (!sOutQueue.offer(new UdpPacket(srcIpHO, srcPortHO, dstIpHO, dstPortHO, copy))) {
            Log.w(TAG, "outbound queue full — packet dropped");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Network detection
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Populate sWifiNetwork and sCellNetwork from the system ConnectivityManager.
     * Requires Android M+ for Network.bindSocket(); silently degrades on older APIs.
     */
    private static void detectNetworks(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Log.i(TAG, "detectNetworks: API < 23, single-path mode");
            return;
        }
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;

            for (Network net : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(net);
                if (nc == null) continue;
                // Skip VPN and loopback transports — we only want physical interfaces.
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;

                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    sWifiNetwork = net;
                    Log.i(TAG, "dualChannel: WiFi network detected");
                } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    sCellNetwork = net;
                    Log.i(TAG, "dualChannel: Cellular network detected");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "detectNetworks error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Sender thread
    // ═══════════════════════════════════════════════════════════════════════

    private static void senderLoop() {
        while (sRunning.get()) {
            try {
                UdpPacket pkt = sOutQueue.poll(200, TimeUnit.MILLISECONDS);
                if (pkt == null) {
                    pruneIdleSessions();
                    continue;
                }
                dispatchPacket(pkt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private static void dispatchPacket(UdpPacket pkt) {
        String key = pkt.srcIpHO + ":" + pkt.srcPortHO;
        ManagedSession session = sSessionMap.get(key);

        if (session == null || session.primary.isClosed()) {
            session = createSession(pkt.srcIpHO, pkt.srcPortHO);
            if (session == null) return;
            sSessionMap.put(key, session);
        }

        session.lastUsedMs = System.currentTimeMillis();

        try {
            InetAddress dstAddr = intToInet(pkt.dstIpHO);
            int         dstPort = pkt.dstPortHO;

            // ── PRIMARY socket (WiFi or system default) ───────────────────
            if (!session.primary.isClosed()) {
                session.primary.send(
                        new DatagramPacket(pkt.payload, pkt.payload.length,
                                           dstAddr, dstPort));
            }

            // ── SECONDARY socket (Cellular — dualChannel path) ────────────
            if (session.secondary != null && !session.secondary.isClosed()) {
                session.secondary.send(
                        new DatagramPacket(pkt.payload, pkt.payload.length,
                                           dstAddr, dstPort));
            }

        } catch (IOException e) {
            Log.w(TAG, "send error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Session management
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Create a new ManagedSession for the given source (client) address.
     * Spawns receiver threads for each allocated socket.
     */
    private static ManagedSession createSession(int clientIpHO, int clientPortHO) {
        VpnService vpn = sVpnService;
        if (vpn == null) return null;

        // Primary: prefer WiFi, fall back to cellular, then unbound (OS default).
        Network primaryNet = (sWifiNetwork != null) ? sWifiNetwork : sCellNetwork;
        DatagramSocket primary = openSocket(vpn, primaryNet);
        if (primary == null) primary = openSocket(vpn, null); // last resort
        if (primary == null) {
            Log.e(TAG, "createSession: could not open primary socket");
            return null;
        }

        // Secondary: only when we have a SECOND distinct interface.
        DatagramSocket secondary = null;
        if (sWifiNetwork != null && sCellNetwork != null) {
            secondary = openSocket(vpn, sCellNetwork);
            // If opening the secondary fails, we simply run single-path.
        }

        // Start receiver threads — one per socket.
        startReceiverThread(primary,   clientIpHO, clientPortHO, "P");
        if (secondary != null) {
            startReceiverThread(secondary, clientIpHO, clientPortHO, "S");
        }

        return new ManagedSession(primary, secondary);
    }

    /**
     * Open a DatagramSocket optionally bound to {@code network}
     * (null = OS default), protected from VPN self-routing.
     */
    private static DatagramSocket openSocket(VpnService vpn, Network network) {
        try {
            DatagramSocket sock = new DatagramSocket();
            sock.setSoTimeout(RECV_TIMEOUT_MS);
            sock.setReceiveBufferSize(256 * 1024);
            sock.setSendBufferSize(256 * 1024);

            // Bind to a specific physical interface (Android M+).
            if (network != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    network.bindSocket(sock);
                } catch (IOException e) {
                    Log.w(TAG, "bindSocket failed (" + e.getMessage()
                            + ") — falling back to OS default for this socket");
                }
            }

            // Exclude from VPN routing so packets go to the real internet.
            if (!vpn.protect(sock)) {
                Log.w(TAG, "protect() returned false — socket may route through VPN");
            }

            // Request IP DSCP / TOS low-delay marking (IPTOS_LOWDELAY = 0x10).
            try { sock.setTrafficClass(0x10); } catch (Exception ignored) {}

            return sock;
        } catch (IOException e) {
            Log.e(TAG, "openSocket failed: " + e.getMessage());
            return null;
        }
    }

    private static void startReceiverThread(DatagramSocket sock,
                                             int clientIpHO, int clientPortHO,
                                             String label) {
        Thread t = new Thread(
                () -> receiveLoop(sock, clientIpHO, clientPortHO),
                "DirectUdpRecv-" + label + "-" + clientPortHO);
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Receiver loop
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Receive game-server responses and inject them into the TUN device.
     * Uses the ACTUAL sender address from each datagram (handles NAT / LB
     * transparently — the injected source IP matches what the server sent).
     */
    private static void receiveLoop(DatagramSocket sock,
                                    int clientIpHO, int clientPortHO) {
        byte[]         buf   = new byte[MAX_UDP_PAYLOAD];
        DatagramPacket dgram = new DatagramPacket(buf, buf.length);

        while (sRunning.get() && !sock.isClosed()) {
            try {
                sock.receive(dgram);

                // Use actual sender address, not the pre-configured dst.
                byte[] addrBytes    = dgram.getAddress().getAddress();
                int    serverIpHO   = bytesToInt(addrBytes);
                int    serverPortHO = dgram.getPort();

                byte[] payload = new byte[dgram.getLength()];
                System.arraycopy(buf, 0, payload, 0, dgram.getLength());

                // Inject into TUN — tun2socks.c synthesises IPv4 + UDP headers.
                Tun2SocksJniLoader.injectGameUdpResponse(
                        serverIpHO,  serverPortHO,
                        clientIpHO,  clientPortHO,
                        payload);

            } catch (SocketTimeoutException ignored) {
                // Normal — loop back and check sRunning.
            } catch (IOException e) {
                if (sRunning.get() && !sock.isClosed())
                    Log.w(TAG, "receiveLoop IO error: " + e.getMessage());
                break;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Session pruning
    // ═══════════════════════════════════════════════════════════════════════

    private static void pruneIdleSessions() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, ManagedSession>> it =
                sSessionMap.entrySet().iterator();
        while (it.hasNext()) {
            ManagedSession ms = it.next().getValue();
            if (now - ms.lastUsedMs > SOCKET_IDLE_MS || ms.primary.isClosed()) {
                ms.close();
                it.remove();
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Byte-order utilities
    // ═══════════════════════════════════════════════════════════════════════

    /** Host-order int → InetAddress.  e.g. 0x0A000001 → InetAddress(10.0.0.1) */
    private static InetAddress intToInet(int ho) throws java.net.UnknownHostException {
        return InetAddress.getByAddress(new byte[]{
            (byte)(ho >>> 24), (byte)(ho >>> 16), (byte)(ho >>> 8), (byte) ho });
    }

    /** Big-endian 4-byte array → host-order int. */
    private static int bytesToInt(byte[] b) {
        if (b == null || b.length < 4) return 0;
        return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16)
             | ((b[2] & 0xFF) <<  8) |  (b[3] & 0xFF);
    }
}
