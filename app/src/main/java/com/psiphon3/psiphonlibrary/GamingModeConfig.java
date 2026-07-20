/*
 * GamingModeConfig.java
 * GearUP-style browser/streaming bypass for gaming mode.
 *
 * GearUP only tunnels game traffic and routes everything else direct.
 * We mirror that by adding well-known browser and streaming app packages to
 * the VPN disallowed list — they bypass the QUIC tunnel and go straight to
 * the user's ISP.  Game apps are NOT in this list, so they flow through the
 * low-latency QUIC relay just like GearUP's sproxy.
 *
 * applyBrowserBypass() is called from TunnelManager.vpnServiceBuilder() only
 * when the VPN is in ALL_APPS or EXCLUDE_APPS mode (uses addDisallowedApplication).
 * It is skipped in INCLUDE_APPS mode to avoid mixing add/disallow calls.
 */
package com.psiphon3.psiphonlibrary;

import android.content.pm.PackageManager;
import android.net.VpnService;

import com.psiphon3.log.MyLog;

public class GamingModeConfig {

    /**
     * Browsers, streaming apps, and other non-game traffic that should
     * bypass the QUIC tunnel and go direct — lower overhead, leaves more
     * bandwidth for game UDP flows.
     */
    private static final String[] BYPASS_PACKAGES = {
        // ── Browsers ─────────────────────────────────────────────────────────
        "com.android.chrome",
        "org.mozilla.firefox",
        "com.microsoft.bing",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.UCMobile.intl",
        "com.brave.browser",
        "com.sec.android.app.sbrowser",          // Samsung Internet
        "com.android.browser",
        "com.yandex.browser",

        // ── Streaming / heavy-data apps ───────────────────────────────────────
        "com.google.android.youtube",
        "com.netflix.mediaclient",
        "com.spotify.music",
        "com.amazon.avod.thirdpartyclient",       // Prime Video
        "com.disney.disneyplus",
        "tv.twitch.android.app",

        // ── System / background ───────────────────────────────────────────────
        "com.google.android.apps.maps",
        "com.google.android.gm",                 // Gmail
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.photos",
        "com.google.android.apps.docs",
        "com.dropbox.android",
        "com.microsoft.teams",
        "com.skype.raider",
        "com.whatsapp",
        "org.telegram.messenger",
    };

    /**
     * Add bypass packages to the VPN builder.
     * Only call this when the builder is in disallowed-app mode (ALL_APPS or EXCLUDE_APPS).
     *
     * Merges the hardcoded BYPASS_PACKAGES list with any additional packages pushed
     * via RemoteConfigManager (bypassPackages field in the remote JSON).
     */
    public static void applyBrowserBypass(VpnService.Builder vpnBuilder,
                                          PackageManager pm) {
        // Build the combined set: hardcoded + remote
        java.util.Set<String> allPackages = new java.util.LinkedHashSet<>();
        for (String p : BYPASS_PACKAGES) allPackages.add(p);

        RemoteConfigManager.AppliedConfig rc = RemoteConfigManager.getApplied();
        if (rc.bypassPackages != null) {
            allPackages.addAll(rc.bypassPackages);
        }

        int applied = 0;
        for (String pkg : allPackages) {
            try {
                pm.getPackageInfo(pkg, 0); // check installed
                vpnBuilder.addDisallowedApplication(pkg);
                applied++;
            } catch (PackageManager.NameNotFoundException e) {
                // Not installed on this device — skip silently
            } catch (Exception e) {
                MyLog.w("GamingModeConfig: failed to bypass " + pkg + ": " + e.getMessage());
            }
        }
        MyLog.i("GamingModeConfig: " + applied + " non-game apps bypassed (go direct)"
                + (rc.bypassPackages != null ? " [+" + rc.bypassPackages.size() + " remote]" : ""));
    }
}
