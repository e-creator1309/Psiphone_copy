# Session Progress — Psiphone Gaming Conversion

## Goal
Convert the Psiphone TCP clone into a UDP/QUIC gaming-optimised tunnel app, modelled on the GearUP architecture. No UI changes — only the network processing layer changes.

## What's in app/libs

| File | Role |
|---|---|
| `ca.psiphon.aar` | Custom-built Psiphon Go tunnel core — arm64-only, ~10 MB (vs 37 MB stock). Built from open-source tunnel-core using `tunnel-core/patches/make.bash`. Flags: `-trimpath`, 16 KB page size for Android 15, API 21+. |
| `achartengine-1.0.0.jar` | Legacy charting lib (Statistics tab) |
| `jackson-core-2.2.0.jar` | JSON streaming parser |
| `jndcrash-release.aar` | Native crash reporter (NDCrash) |
| `snakeyaml-1.10-android.jar` | YAML parser |

## Architecture understood

### Current stack (unchanged layers)
| Layer | File | Role |
|---|---|---|
| UI | `com/psiphon3/*.java` | Untouched — zero UI changes |
| VPN capture | `TunnelVpnService.java` | Android VpnService — intercepts all IP packets |
| TUN→SOCKS | `jni/badvpn/` (tun2socks C/JNI) | Converts TUN packets to SOCKS for Go tunnel |
| VPN rules | `TunnelManager.vpnServiceBuilder()` | Per-app include/exclude, now + gaming browser bypass |
| Tunnel manager | `TunnelManager.java` | Builds config JSON, manages lifecycle |
| Go tunnel core | `ca.psiphon.aar` (`ca.psiphon.PsiphonTunnel`) | Protocol handshake to servers |
| Server list | `EmbeddedValues.java` | 400 servers, hex-encoded JSON. Injected at build time via `scripts/inject-server-entry.py` + GitHub secret `SERVER_ENTRY`. Empty stub in repo. |

### GearUP comparison (current parity)
| GearUP | This app | Status |
|---|---|---|
| VpnService traffic capture | TunnelVpnService | ✅ |
| Encrypted UDP relay (sproxy / AES-128-GCM) | QUICv1 (IETF QUIC over UDP) forced via `LimitTunnelProtocols` | ✅ Session 1 |
| Parallel relay selection | `ConnectionWorkerPoolSize:5`, `StaggerConnectionWorkersMilliseconds:0` | ✅ Session 2 |
| Latency-first connect | `NetworkLatencyMultiplierLambda:0.1`, `InitialLimitTunnelProtocolsCandidateCount:5` | ✅ Session 2 |
| Fast reconnect on drop | `EstablishTunnelPausePeriodSeconds:1` | ✅ Session 2 |
| Best relay selection (radix-tree IP→relay) | `UdpLatencyChecker.java` — TCP-probes servers, stores fastest regions, sets `LimitServerEntryRegions` | ✅ Session 2 |
| Game-only traffic (TProxy classifier) | `GamingModeConfig.java` — browsers/streaming bypass the tunnel | ✅ Session 2 |
| Private BGP peering at IXPs | 400 embedded servers across regions (pre-existing) | ✅ |

---

## Changes made

### Session 1 ✅
**File:** `TunnelManager.java` → `buildTunnelCoreConfig()`

| Config key added | Value | Effect |
|---|---|---|
| `LimitTunnelProtocols` | `["QUICv1"]` | Drops all TCP (SSH/OSSH/TLS/Meek). UDP only. |
| `NetworkLatencyMultiplierLambda` | `0.1` | Aggressively optimises for RTT not throughput |
| `ConnectionWorkerPoolSize` | `5` | 5 servers tried in parallel |

### Session 2 ✅
**File:** `TunnelManager.java` → `buildTunnelCoreConfig()` — 3 more config keys:

| Config key added | Value | Effect |
|---|---|---|
| `StaggerConnectionWorkersMilliseconds` | `0` | All 5 workers fire simultaneously (GearUP: parallel relay probe) |
| `InitialLimitTunnelProtocolsCandidateCount` | `5` | 5 candidates in initial fast-select phase |
| `EstablishTunnelPausePeriodSeconds` | `1` | Reconnect in 1 s on drop (default is much longer) |
| `LimitServerEntryRegions` | top-3 from UdpLatencyChecker | Only dial servers in fastest-latency regions (skipped if user chose a region manually) |

**File:** `TunnelManager.java` → `getTunnelConfigSingle()`
- Calls `UdpLatencyChecker.runInBackground(context)` on every tunnel connect — probes run in the background and are ready for the next reconnect.

**File:** `TunnelManager.java` → `vpnServiceBuilder()`
- Calls `GamingModeConfig.applyBrowserBypass()` when in ALL_APPS / EXCLUDE_APPS mode.
- Skipped in INCLUDE_APPS mode (can't mix addAllowed + addDisallowed).

**New file:** `UdpLatencyChecker.java`
- Decodes `EmbeddedValues.EMBEDDED_SERVER_LIST` hex entries
- TCP-probes one server per region on `TlsOSSHPort` (443) to measure RTT
- Runs 12 workers in parallel, 3 s timeout per probe
- Stores top-3 fastest region codes as JSON in SharedPreferences
- TunnelManager reads and passes to `LimitServerEntryRegions` in the Go config
- Fire-and-forget: never blocks tunnel or UI

**New file:** `GamingModeConfig.java`
- 29 browser/streaming/background package names added to VPN disallowed list
- These apps bypass the QUIC tunnel and go direct to ISP (like GearUP's non-game passthrough)
- Game apps are NOT in the list → they use the QUIC relay for low latency

---

## Files transferred
| File | From | To | Branch |
|---|---|---|---|
| `EmbeddedValues.java` | `e-creator1309/fastscripts` | `Psiphone_copy/app/src/main/java/com/psiphon3/psiphonlibrary/` | master ✅ |

---

## What still needs doing

### Phase 3 — Test the build
- Trigger `.github/workflows/build.yml` (needs `SERVER_ENTRY` secret set)
- Confirm APK builds with new files
- Check logcat for: `UdpLatencyChecker: fastest regions stored`, `GamingModeConfig: N non-game apps bypassed`, tunnel connects via QUICv1

### Phase 4 — Direct UDP path ✅ COMPLETE (CI green 2026-07-20)
- **What was built:** Game UDP packets (non-DNS, IPv4) are intercepted inside
  `process_device_udp_packet()` in tun2socks.c BEFORE they reach SocksUdpGwClient.
  They are handed to a new `DirectUdpManager` Java class via JNI, which sends them
  through a `VpnService.protect()`-ed `DatagramSocket` directly to the game server —
  bypassing the TCP SOCKS5/udpgw path entirely.
- **Responses** are injected back into the TUN fd by `injectGameUdpResponse` (C JNI):
  a hand-built IPv4+UDP packet with correct header checksums, written atomically via
  `write(options.tun_fd, …)`.
- **DNS** (port 53) still goes through the tunnel (censorship circumvention preserved).
- **Files changed:**
  - `app/src/main/jni/badvpn/tun2socks/tun2socks.c` — 6 C additions
  - `app/src/main/java/ca/psiphon/Tun2SocksJniLoader.java` — native decl added
  - `app/src/main/java/com/psiphon3/psiphonlibrary/DirectUdpManager.java` — new file
  - `app/src/main/java/com/psiphon3/VpnManager.java` — lifecycle wiring
  - `app/src/main/java/com/psiphon3/psiphonlibrary/TunnelManager.java` — interface impl
- **Expected latency impact:** eliminates the 999 ms HOL-blocking spike floor caused by
  TCP-inside-QUIC retransmits on the udpgw path.

### Phase 5 — Dynamic server injection (optional)
- Currently: servers injected at build time from GitHub secret
- Goal: fetch updated server list at runtime from a private endpoint, decrypt, replace EmbeddedValues list
- Similar to GearUP's dynamic route table download from `gearupportal.com`

---

## Decisions log
- **No UI changes** — user requirement, zero UI files touched
- **No server changes** — servers already support QUICv1 and QUIC ports
- **LimitTunnelProtocols not LimitTunnelDialSysCall** — protocol restriction, not syscall restriction
- **TCP probe for latency, not UDP ping** — Android blocks ICMP without root; TCP SYN-ACK RTT is a valid proxy for QUIC UDP RTT to the same host
- **GamingModeConfig uses addDisallowedApplication not addAllowedApplication** — preserves existing per-app VPN UI; safer than replacing the allow-list
- **ConnectionWorkerPoolSize 5** — battery vs speed balance; tune up to 10 if needed
- **EstablishTunnelPausePeriodSeconds 1** — fast reconnect matters for gaming (dropped connection = game kick)
- **ca.psiphon.aar is custom-built arm64-only** — reduces APK size ~75%, requires CI to rebuild from source when tunnel-core changes
