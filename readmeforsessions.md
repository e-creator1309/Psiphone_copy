# Session Progress — Psiphone Gaming Conversion

## Goal
Convert the Psiphone TCP clone into a UDP/QUIC gaming-optimised tunnel app, modelled on the GearUP architecture. No UI changes — only the network processing layer changes.

## Architecture understood (from README + code audit)

### Current stack (unchanged layers)
| Layer | File | Role |
|---|---|---|
| UI | `com/psiphon3/*.java` | Untouched |
| VPN capture | `TunnelVpnService.java` | Android VpnService — intercepts all IP packets, no protocol dependency |
| TUN→SOCKS | `jni/badvpn/` (tun2socks, C/JNI) | Converts TUN packets to SOCKS for the Go tunnel — protocol-agnostic |
| Tunnel manager | `TunnelManager.java` | Builds config JSON, manages lifecycle |
| Go tunnel core | `ca.psiphon.PsiphonTunnel` (AAR) | Does the actual protocol handshake to servers |
| Server list | `EmbeddedValues.java` | 400 servers, hex-encoded JSON entries |

### GearUP comparison
| GearUP | This app |
|---|---|
| `libdivider2.so` — TProxy game classifier | badvpn tun2socks (all traffic tunnelled) |
| `libRouteTable.so` — radix-tree IP→relay map | EmbeddedValues.java server pool |
| `sproxy` — AES-128-GCM UDP relay protocol | PsiphonTunnel QUICv1 (IETF QUIC over UDP) |
| P2P fallback → sproxy | QUICv1 only (no TCP fallback) |
| Private BGP peering at IXPs | 400 embedded servers across regions |

## Key discovery
**The 400 servers already support QUICv1 (UDP).** Every server entry in `EmbeddedValues.java` includes:
- `"QUICv1"` in its `capabilities` array
- `sshObfuscatedQUICPort` field (dedicated QUIC port per server)

The client was just never told to use it — defaulting to TCP (SSH/OSSH/TLS).

---

## Changes made

### Session 1 ✅
**File:** `app/src/main/java/com/psiphon3/psiphonlibrary/TunnelManager.java`
**Method:** `buildTunnelCoreConfig()`
**What changed:** Added 3 gaming config keys to the JSON passed to the Go tunnel core, just before `return json.toString()`:

```java
// Force QUICv1 (UDP) only — drop all TCP protocols
JSONArray limitProtocols = new JSONArray();
limitProtocols.put("QUICv1");
json.put("LimitTunnelProtocols", limitProtocols);

// Aggressive RTT optimisation
json.put("NetworkLatencyMultiplierLambda", 0.1);

// 5 parallel server workers — faster first connect from 400-server pool
json.put("ConnectionWorkerPoolSize", 5);
```

**What this deletes from the TCP path:**
- SSH protocol (port 22 / obfuscated port)
- OSSH (obfuscated SSH)
- TLS tunnelling
- Meek (domain-fronting)
- All TCP handshake/retry logic for above

**What this keeps:**
- VpnService capture (unchanged)
- badvpn tun2socks (unchanged)
- All UI (unchanged)
- QUICv1 server connect path (already in Go library)
- All 400 servers (they all speak QUIC)

---

## Files transferred
| File | From | To | Branch |
|---|---|---|---|
| `EmbeddedValues.java` | `e-creator1309/fastscripts` | `e-creator1309/Psiphone_copy` `app/src/main/java/com/psiphon3/psiphonlibrary/` | master ✅ |

---

## Next sessions — what still needs doing

### Phase 2 — Server selection (GearUP-style best-server routing)
- Currently: Go library picks server from embedded list automatically
- Goal: Ping all reachable servers at startup, rank by RTT, connect to fastest
- File to create: `ServerSelector.java` in `psiphonlibrary/`
- Hook into: `TunnelManager.getTunnelConfigSingle()` before tunnel starts

### Phase 3 — Game traffic classifier (optional, GearUP parity)
- Currently: all traffic goes through the tunnel (fine for gaming)
- Goal: classify by dest port (UDP 3074, 3478, 9308 etc.) and bypass tunnel for non-game traffic to reduce overhead
- File to modify: `VpnRulesHelper.java` or add `GameTrafficClassifier.java`

### Phase 4 — UDP socket bypass for game packets
- badvpn converts TUN→SOCKS (TCP). Game packets arriving as UDP get wrapped in TCP SOCKS then re-UDP'd at the Go layer.
- For pure gaming: investigate replacing tun2socks with a direct UDP path (remove one TCP wrapper hop)
- Risk: significant JNI/C changes — do Phase 2 & 3 first

### Phase 5 — Compile & test
- Build with `./gradlew assembleDebug`
- Test with a game title, measure ping before/after
- Verify QUICv1 is selected in diagnostic logs (`onDiagnosticMessage` output)

---

## Decisions log
- **No UI changes** — user requirement, zero UI files touched
- **No server changes** — servers already speak QUIC, no config needed server-side
- **LimitTunnelProtocols not LimitTunnelDialSysCall** — the former restricts protocol selection, the latter restricts OS calls; we want protocol restriction
- **ConnectionWorkerPoolSize 5** — balances battery vs connect speed; can tune up to 10
- **NetworkLatencyMultiplierLambda 0.1** — already used in disableTimeouts mode, safe value
