/*
 * UdpLatencyChecker.java
 * GearUP-style server latency prober for gaming mode.
 *
 * Decodes the embedded server list, TCP-probes one server per region on
 * TlsOSSHPort (443), ranks regions by RTT, and stores the top-3 fastest
 * region codes in SharedPreferences.  TunnelManager reads the result and
 * passes it as LimitServerEntryRegions to the Go tunnel core so it only
 * dials servers in low-latency regions.
 *
 * Runs entirely on a background thread — never blocks the UI or the tunnel.
 */
package com.psiphon3.psiphonlibrary;

import android.content.Context;

import com.psiphon3.log.MyLog;

import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class UdpLatencyChecker {

    public static final String PREFS_NAME         = "gaming_mode_prefs";
    public static final String KEY_FASTEST_REGIONS = "fastest_regions_json";

    private static final int PROBE_TIMEOUT_MS = 3000;
    private static final int MAX_WORKERS      = 12;

    /** Fire-and-forget: probes servers and stores results. Call once on tunnel connect. */
    public static void runInBackground(Context context) {
        Thread t = new Thread(() -> probe(context.getApplicationContext()),
                              "UdpLatencyChecker");
        t.setDaemon(true);
        t.start();
    }

    private static void probe(Context context) {
        String[] serverList = EmbeddedValues.EMBEDDED_SERVER_LIST;
        if (serverList == null || serverList.length == 0) {
            MyLog.i("UdpLatencyChecker: embedded server list is empty, skipping probe");
            return;
        }

        // Decode hex entries and collect one representative IP per region
        Map<String, String[]> regionToServer = new HashMap<>(); // region -> [ip, portStr]
        for (String hexEntry : serverList) {
            try {
                String decoded = hexToString(hexEntry);
                int jsonStart = decoded.indexOf('{');
                if (jsonStart < 0) continue;
                JSONObject obj = new JSONObject(decoded.substring(jsonStart));
                String ip     = obj.optString("ipAddress", "");
                String region = obj.optString("region", "");
                int    port   = obj.optInt("TlsOSSHPort", 443);
                if (ip.isEmpty() || region.isEmpty()) continue;
                regionToServer.putIfAbsent(region, new String[]{ip, String.valueOf(port)});
            } catch (Exception ignored) { /* skip malformed entry */ }
        }

        if (regionToServer.isEmpty()) {
            MyLog.i("UdpLatencyChecker: no decodable server entries found");
            return;
        }
        MyLog.i("UdpLatencyChecker: probing " + regionToServer.size() + " regions");

        List<RegionResult> results = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(MAX_WORKERS, regionToServer.size()));
        CountDownLatch latch = new CountDownLatch(regionToServer.size());

        for (Map.Entry<String, String[]> entry : regionToServer.entrySet()) {
            final String region = entry.getKey();
            final String ip     = entry.getValue()[0];
            final int    port   = Integer.parseInt(entry.getValue()[1]);

            pool.submit(() -> {
                try {
                    long rtt = tcpProbe(ip, port);
                    if (rtt >= 0) {
                        results.add(new RegionResult(region, rtt));
                        MyLog.i("UdpLatencyChecker: " + region
                                + " ip=" + ip + " rtt=" + rtt + "ms");
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await(PROBE_TIMEOUT_MS + 2000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {}
        pool.shutdownNow();

        if (results.isEmpty()) {
            MyLog.i("UdpLatencyChecker: no reachable servers found");
            return;
        }

        // Sort ascending by RTT and keep top 3 regions
        Collections.sort(results, (a, b) -> Long.compare(a.rttMs, b.rttMs));
        int keep = Math.min(3, results.size());
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < keep; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(results.get(i).region).append("\"");
        }
        sb.append("]");
        String regionsJson = sb.toString();

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
               .edit()
               .putString(KEY_FASTEST_REGIONS, regionsJson)
               .apply();

        MyLog.i("UdpLatencyChecker: fastest regions stored = " + regionsJson);
    }

    /** TCP SYN-ACK round-trip to the given host:port. Returns -1 if unreachable. */
    private static long tcpProbe(String ip, int port) {
        try {
            long start = System.currentTimeMillis();
            Socket s = new Socket();
            s.connect(new InetSocketAddress(ip, port), PROBE_TIMEOUT_MS);
            long rtt = System.currentTimeMillis() - start;
            s.close();
            return rtt;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Decode a hex string to a UTF-8 string (server entries are hex-encoded JSON). */
    private static String hexToString(String hex) {
        hex = hex.trim();
        StringBuilder sb = new StringBuilder(hex.length() / 2);
        for (int i = 0; i + 1 < hex.length(); i += 2) {
            sb.append((char) Integer.parseInt(hex.substring(i, i + 2), 16));
        }
        return sb.toString();
    }

    /** Returns the stored fastest-regions JSON array string, or "" if not yet available. */
    public static String getStoredFastestRegionsJson(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                      .getString(KEY_FASTEST_REGIONS, "");
    }

    private static class RegionResult {
        final String region;
        final long   rttMs;
        RegionResult(String r, long ms) { region = r; rttMs = ms; }
    }
}
