/*
 * RemoteConfigManager.java
 *
 * GearUP-style dynamic server push for Psiphone.
 *
 * GearUP's /acc endpoint returns a JSON blob (AccResponse) that tells the app:
 *   - which relay nodes to use (ip, port, weight, region)
 *   - routing rules, QoS buffer sizes, dual-channel mode, timeout tuning
 *
 * This class mirrors that pattern: it periodically fetches a JSON config from a
 * configurable URL and applies the values to TunnelManager's buildTunnelCoreConfig()
 * at connect-time.  The config is cached in SharedPreferences so the last known-good
 * settings survive offline restarts.
 *
 * JSON schema (all fields optional; unknown fields are ignored):
 * {
 *   "connectionWorkerPoolSize":        5,        // parallel dial workers (default 5)
 *   "initialCandidateCount":           5,        // QUIC preference candidates (default 5)
 *   "preferredRegions":                ["JP","SG","HK"],  // region priority list
 *   "initialProtocols":                ["QUIC-OSSH"],     // protocol preference
 *   "enableDualChannel":               true,     // WiFi+Cell simultaneous send
 *   "latencyMultiplierLambda":         2.0,      // Psiphon NetworkLatencyMultiplierLambda
 *   "udpSocketSendBufferKB":           256,      // UDP QoS send buffer (kb)
 *   "udpSocketRecvBufferKB":           256,      // UDP QoS recv buffer (kb)
 *   "dnsAlternateServers":             ["1.1.1.1","8.8.8.8"],
 *   "bypassPackages":                  ["com.google.android.youtube", ...],
 *   "fetchIntervalSeconds":            300,      // re-fetch interval (default 5 min)
 *   "configVersion":                   1         // bump to force immediate re-apply
 * }
 *
 * To use:  host the JSON at any HTTPS URL and call:
 *   RemoteConfigManager.setConfigUrl(context, "https://your-server.example.com/boost.json");
 *   RemoteConfigManager.scheduleFetch(context);
 *
 * The fetched values flow into TunnelManager automatically via getApplied().
 */
package com.psiphon3.psiphonlibrary;

import android.content.Context;
import android.content.SharedPreferences;

import com.psiphon3.log.MyLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class RemoteConfigManager {

    private static final String TAG = "RemoteConfigManager";
    private static final String PREFS_NAME  = "psiphone_remote_cfg";
    private static final String KEY_URL     = "cfg_url";
    private static final String KEY_JSON    = "cfg_json";
    private static final String KEY_VERSION = "cfg_version";

    private static final int DEFAULT_FETCH_INTERVAL_SEC = 300; // 5 minutes
    private static final int CONNECT_TIMEOUT_MS         = 8_000;
    private static final int READ_TIMEOUT_MS            = 10_000;

    // Singleton executor for background fetches
    private static ScheduledExecutorService sExecutor;

    // In-memory cache of the last successfully parsed config
    private static volatile AppliedConfig sCurrent = new AppliedConfig();

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /** Set the URL that hosts the remote config JSON. */
    public static void setConfigUrl(Context ctx, String url) {
        prefs(ctx).edit().putString(KEY_URL, url).apply();
        MyLog.i(TAG + ": config URL set to " + url);
    }

    /** Returns the currently configured URL, or null if none. */
    public static String getConfigUrl(Context ctx) {
        return prefs(ctx).getString(KEY_URL, null);
    }

    /**
     * Start a background thread that fetches the remote config immediately
     * and then on the configured interval.  Safe to call multiple times — only
     * one executor is created.
     */
    public static synchronized void scheduleFetch(Context ctx) {
        if (sExecutor != null && !sExecutor.isShutdown()) {
            return; // already running
        }
        // Restore last cached config immediately so it's available before the
        // first network fetch completes.
        restoreFromPrefs(ctx);

        sExecutor = Executors.newSingleThreadScheduledExecutor();
        final Context appCtx = ctx.getApplicationContext();
        int interval = sCurrent.fetchIntervalSeconds > 0
                ? sCurrent.fetchIntervalSeconds
                : DEFAULT_FETCH_INTERVAL_SEC;

        sExecutor.scheduleAtFixedRate(() -> fetchAndApply(appCtx), 0, interval, TimeUnit.SECONDS);
        MyLog.i(TAG + ": fetch scheduler started (interval " + interval + "s)");
    }

    /** Stop the background fetcher (call in onDestroy if needed). */
    public static synchronized void shutdown() {
        if (sExecutor != null) {
            sExecutor.shutdownNow();
            sExecutor = null;
        }
    }

    /**
     * Returns the most-recently-applied config.  Always non-null; defaults are
     * used if no remote config has been fetched yet.
     */
    public static AppliedConfig getApplied() {
        return sCurrent;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Fetch + parse
    // ──────────────────────────────────────────────────────────────────────────

    private static void fetchAndApply(Context ctx) {
        String url = prefs(ctx).getString(KEY_URL, null);
        if (url == null || url.isEmpty()) {
            return; // no URL configured
        }
        try {
            String json = httpGet(url);
            if (json == null || json.isEmpty()) return;

            AppliedConfig cfg = parse(json);
            sCurrent = cfg;

            // Persist so the next restart picks up the last-known config
            prefs(ctx).edit()
                    .putString(KEY_JSON, json)
                    .putInt(KEY_VERSION, cfg.configVersion)
                    .apply();

            MyLog.i(TAG + ": remote config applied (v" + cfg.configVersion + ")");
        } catch (Exception e) {
            MyLog.w(TAG + ": fetch failed — " + e.getMessage());
        }
    }

    private static void restoreFromPrefs(Context ctx) {
        String cached = prefs(ctx).getString(KEY_JSON, null);
        if (cached != null && !cached.isEmpty()) {
            try {
                sCurrent = parse(cached);
                MyLog.i(TAG + ": restored cached config v" + sCurrent.configVersion);
            } catch (Exception e) {
                MyLog.w(TAG + ": could not restore cached config — " + e.getMessage());
            }
        }
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "Psiphone/1.0");
        conn.setRequestProperty("Accept", "application/json");

        int code = conn.getResponseCode();
        if (code != 200) {
            MyLog.w(TAG + ": server returned HTTP " + code);
            return null;
        }
        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // JSON → AppliedConfig
    // ──────────────────────────────────────────────────────────────────────────

    static AppliedConfig parse(String jsonStr) throws Exception {
        JSONObject o = new JSONObject(jsonStr);
        AppliedConfig c = new AppliedConfig();

        if (o.has("connectionWorkerPoolSize"))
            c.connectionWorkerPoolSize = o.getInt("connectionWorkerPoolSize");
        if (o.has("initialCandidateCount"))
            c.initialCandidateCount = o.getInt("initialCandidateCount");
        if (o.has("latencyMultiplierLambda"))
            c.latencyMultiplierLambda = o.getDouble("latencyMultiplierLambda");
        if (o.has("enableDualChannel"))
            c.enableDualChannel = o.getBoolean("enableDualChannel");
        if (o.has("udpSocketSendBufferKB"))
            c.udpSocketSendBufferKB = o.getInt("udpSocketSendBufferKB");
        if (o.has("udpSocketRecvBufferKB"))
            c.udpSocketRecvBufferKB = o.getInt("udpSocketRecvBufferKB");
        if (o.has("fetchIntervalSeconds"))
            c.fetchIntervalSeconds = o.getInt("fetchIntervalSeconds");
        if (o.has("configVersion"))
            c.configVersion = o.getInt("configVersion");

        if (o.has("preferredRegions")) {
            JSONArray arr = o.getJSONArray("preferredRegions");
            c.preferredRegions = toStringList(arr);
        }
        if (o.has("initialProtocols")) {
            JSONArray arr = o.getJSONArray("initialProtocols");
            c.initialProtocols = toStringList(arr);
        }
        if (o.has("dnsAlternateServers")) {
            JSONArray arr = o.getJSONArray("dnsAlternateServers");
            c.dnsAlternateServers = toStringList(arr);
        }
        if (o.has("bypassPackages")) {
            JSONArray arr = o.getJSONArray("bypassPackages");
            c.bypassPackages = toStringList(arr);
        }

        return c;
    }

    private static List<String> toStringList(JSONArray arr) throws Exception {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) list.add(arr.getString(i));
        return list;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Value object — all fields have safe defaults matching current hardcodes
    // ──────────────────────────────────────────────────────────────────────────

    public static final class AppliedConfig {
        /** Parallel dial workers — GearUP uses 3–8 depending on network quality. */
        public int connectionWorkerPoolSize   = 5;

        /**
         * How many servers to try with the preferred protocol before opening to
         * all protocols.  GearUP equivalent: acc list size.
         */
        public int initialCandidateCount      = 5;

        /**
         * Psiphon NetworkLatencyMultiplierLambda.
         * 0.1  → mean multiplier ~10  (slow, tolerant — bad for games)
         * 2.0  → mean multiplier ~0.5 (fast, aggressive — GearUP style)
         * null → use Psiphon default
         */
        public double latencyMultiplierLambda = -1; // -1 means "use Psiphon default"

        /**
         * GearUP dualChannel: send each game packet simultaneously over WiFi AND
         * cellular.  Controlled via DirectUdpManager; this flag just records the
         * server's recommendation.
         */
        public boolean enableDualChannel      = true;

        /** UDP QoS send buffer size in KB.  0 = keep OS default. */
        public int udpSocketSendBufferKB      = 256;

        /** UDP QoS recv buffer size in KB.  0 = keep OS default. */
        public int udpSocketRecvBufferKB      = 256;

        /** How often to re-fetch the config (seconds). */
        public int fetchIntervalSeconds       = DEFAULT_FETCH_INTERVAL_SEC;

        /** Config schema version — bump on the server side to force re-apply. */
        public int configVersion              = 0;

        /**
         * Preferred server regions in priority order.
         * null/empty → use UdpLatencyChecker's probed regions (current behaviour).
         * e.g. ["JP", "SG", "HK", "TW", "KR"]
         */
        public List<String> preferredRegions  = null;

        /**
         * Protocol preference list.
         * Defaults to ["QUIC-OSSH"] which mirrors the current hardcoded value.
         */
        public List<String> initialProtocols  = null;

        /**
         * Alternate DNS servers.  null → keep current hardcoded list.
         */
        public List<String> dnsAlternateServers = null;

        /**
         * Additional non-game packages to bypass the VPN tunnel.
         * Merged with GamingModeConfig.BYPASS_PACKAGES at connect time.
         */
        public List<String> bypassPackages    = null;
    }
}
