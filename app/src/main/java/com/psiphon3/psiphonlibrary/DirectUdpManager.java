/*
 * Copyright (c) 2024, Psiphon Inc.
 * All rights reserved.
 *
 * Gaming Mode – Phase 4: Direct UDP bypass.
 *
 * Game UDP packets intercepted by tun2socks.c are handed to this manager
 * instead of going through the TCP SOCKS5/udpgw path.  Each unique
 * (srcIp, srcPort) pair gets a protected DatagramSocket that sends the
 * UDP payload directly to the game server, eliminating TCP head-of-line
 * blocking and the 999 ms spike floor.  Responses are injected back into
 * the TUN device via the native injectGameUdpResponse() JNI call, which
 * reconstructs a proper IPv4+UDP packet and writes it to the TUN fd.
 *
 * Threading model
 * ---------------
 * • onGameUdpPacket() is called from the tun2socks BReactor thread and
 *   must return immediately — it only enqueues the packet.
 * • A single sender thread drains the queue and dispatches outbound UDP.
 * • Each DatagramSocket has its own receiver thread that injects replies.
 *
 * All IP/port values exchanged with native code are in host byte order
 * (C side converts with ntohl/ntohs before calling in, htonl/htons on
 * the way back into the packet buffer).
 */

package com.psiphon3.psiphonlibrary;

import android.net.VpnService;
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

    /** 65535 - 20 (IPv4) - 8 (UDP) */
    private static final int  MAX_UDP_PAYLOAD = 65507;
    /** Sockets idle longer than this are pruned. */
    private static final long SOCKET_IDLE_MS  = 60_000L;
    /** Per-socket receive timeout — controls liveness-check frequency. */
    private static final int  RECV_TIMEOUT_MS = 3_000;
    /** Outbound queue capacity. */
    private static final int  QUEUE_CAPACITY  = 2048;

    // ── state ────────────────────────────────────────────────────────────────
    private static volatile VpnService    sVpnService;
    private static final AtomicBoolean    sRunning     = new AtomicBoolean(false);
    private static volatile Thread        sSenderThread;

    private static final BlockingQueue<UdpPacket>                sOutQueue  =
            new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private static final ConcurrentHashMap<String, ManagedSocket> sSocketMap =
            new ConcurrentHashMap<>();

    // ── inner types ──────────────────────────────────────────────────────────
    private static final class UdpPacket {
        final int srcIpHO, srcPortHO, dstIpHO, dstPortHO;
        final byte[] payload;
        UdpPacket(int si, int sp, int di, int dp, byte[] pl) {
            srcIpHO = si; srcPortHO = sp; dstIpHO = di; dstPortHO = dp; payload = pl;
        }
    }

    private static final class ManagedSocket {
        final DatagramSocket socket;
        final Thread         receiverThread;
        volatile long        lastUsedMs;
        ManagedSocket(DatagramSocket s, Thread t) {
            socket = s; receiverThread = t; lastUsedMs = System.currentTimeMillis();
        }
        void close() { socket.close(); }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Start the direct-UDP bypass.  Idempotent — safe to call multiple times.
     *
     * @param vpnService the active VpnService used to protect sockets from
     *                   being routed through the VPN itself.
     */
    public static synchronized void start(VpnService vpnService) {
        if (sRunning.get()) return;
        if (vpnService == null) {
            Log.w(TAG, "start() called with null VpnService; bypass disabled");
            return;
        }
        sVpnService = vpnService;
        sOutQueue.clear();
        sRunning.set(true);
        sSenderThread = new Thread(DirectUdpManager::senderLoop, "DirectUdpSender");
        sSenderThread.setDaemon(true);
        sSenderThread.setPriority(Thread.MAX_PRIORITY); // gaming: minimize sender latency
        sSenderThread.start();
        Log.i(TAG, "Direct UDP bypass started");
    }

    /**
     * Stop the bypass and close all managed sockets.  Idempotent.
     */
    public static synchronized void stop() {
        if (!sRunning.compareAndSet(true, false)) return;
        Thread t = sSenderThread; sSenderThread = null;
        if (t != null) t.interrupt();
        for (ManagedSocket ms : sSocketMap.values()) ms.close();
        sSocketMap.clear();
        sOutQueue.clear();
        sVpnService = null;
        Log.i(TAG, "Direct UDP bypass stopped");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Called from tun2socks native code on the BReactor thread.
    // Must return immediately — only enqueues, never blocks or allocates.
    //
    // ALL parameters are in host byte order (C converts with ntohl/ntohs).
    // JNI signature expected by C: "(IIII[B)V"
    // ─────────────────────────────────────────────────────────────────────
    public static void onGameUdpPacket(
            int srcIpHO,  int srcPortHO,
            int dstIpHO,  int dstPortHO,
            byte[] payload) {
        if (!sRunning.get() || payload == null || payload.length == 0) return;
        // Defensive copy: C JNI buffer is only valid during this call
        byte[] copy = new byte[payload.length];
        System.arraycopy(payload, 0, copy, 0, payload.length);
        if (!sOutQueue.offer(new UdpPacket(srcIpHO, srcPortHO, dstIpHO, dstPortHO, copy))) {
            Log.w(TAG, "outQueue full — game UDP packet dropped");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Sender thread — drains the queue, dispatches outbound packets
    // ═══════════════════════════════════════════════════════════════════════

    private static void senderLoop() {
        Log.d(TAG, "sender thread started");
        try {
            while (sRunning.get()) {
                UdpPacket pkt = sOutQueue.poll(500, TimeUnit.MILLISECONDS);
                if (pkt == null) { pruneIdleSockets(); continue; }

                String key = pkt.srcIpHO + ":" + pkt.srcPortHO;
                ManagedSocket ms = sSocketMap.get(key);
                if (ms == null || ms.socket.isClosed()) {
                    ms = createSocket(pkt.srcIpHO, pkt.srcPortHO);
                    if (ms == null) continue;
                    ManagedSocket prev = sSocketMap.putIfAbsent(key, ms);
                    if (prev != null) { ms.close(); ms = prev; }
                }
                ms.lastUsedMs = System.currentTimeMillis();

                try {
                    InetAddress dst = intToInet(pkt.dstIpHO);
                    ms.socket.send(new DatagramPacket(
                            pkt.payload, pkt.payload.length, dst, pkt.dstPortHO));
                } catch (IOException e) {
                    if (sRunning.get()) Log.w(TAG, "send: " + e.getMessage());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Log.d(TAG, "sender thread exiting");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Per-socket helpers
    // ═══════════════════════════════════════════════════════════════════════

    /** Create a new protected DatagramSocket and start its receiver thread. */
    private static ManagedSocket createSocket(int srcIpHO, int srcPortHO) {
        VpnService svc = sVpnService;
        if (svc == null) return null;
        DatagramSocket sock;
        try {
            sock = new DatagramSocket();
            sock.setSoTimeout(RECV_TIMEOUT_MS);
            // IPTOS_LOWDELAY (0x10) — hint to OS/router to minimise queuing delay
            try { sock.setTrafficClass(0x10); } catch (Exception ignored) {}
            try { sock.setReceiveBufferSize(256 * 1024); } catch (Exception ignored) {}
            try { sock.setSendBufferSize(256 * 1024); } catch (Exception ignored) {}
        } catch (IOException e) {
            Log.e(TAG, "createSocket: " + e.getMessage()); return null;
        }
        if (!svc.protect(sock)) {
            Log.w(TAG, "protect() failed on game UDP socket"); sock.close(); return null;
        }
        Thread recv = new Thread(
                () -> receiverLoop(sock, srcIpHO, srcPortHO),
                "DirectUdpRecv-" + srcPortHO);
        recv.setDaemon(true);
        recv.setPriority(Thread.MAX_PRIORITY); // gaming: inject responses without delay
        recv.start();
        return new ManagedSocket(sock, recv);
    }

    /**
     * Receive loop: reads game-server responses and injects them back into
     * the TUN as proper IPv4+UDP packets via the native JNI call.
     */
    private static void receiverLoop(DatagramSocket sock, int clientIpHO, int clientPortHO) {
        byte[] buf   = new byte[MAX_UDP_PAYLOAD];
        DatagramPacket dgram = new DatagramPacket(buf, buf.length);
        while (sRunning.get() && !sock.isClosed()) {
            try {
                dgram.setLength(buf.length);
                sock.receive(dgram);

                int serverIpHO   = bytesToInt(dgram.getAddress().getAddress());
                int serverPortHO = dgram.getPort();
                byte[] payload   = new byte[dgram.getLength()];
                System.arraycopy(buf, 0, payload, 0, dgram.getLength());

                // Inject server response into TUN (C rebuilds IPv4+UDP header)
                Tun2SocksJniLoader.injectGameUdpResponse(
                        serverIpHO, serverPortHO, clientIpHO, clientPortHO, payload);

            } catch (SocketTimeoutException ignored) {
                // normal: just loop back and check sRunning
            } catch (IOException e) {
                if (sRunning.get() && !sock.isClosed())
                    Log.w(TAG, "receive: " + e.getMessage());
                break;
            }
        }
    }

    /** Remove sockets that have been idle too long. */
    private static void pruneIdleSockets() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, ManagedSocket>> it = sSocketMap.entrySet().iterator();
        while (it.hasNext()) {
            ManagedSocket ms = it.next().getValue();
            if (now - ms.lastUsedMs > SOCKET_IDLE_MS || ms.socket.isClosed()) {
                ms.close(); it.remove();
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Byte-order utilities
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Host-order int (MSB = first octet) → InetAddress.
     * e.g. 0x0A000001 → InetAddress(10.0.0.1)
     */
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
