#!/bin/bash
# setup-psiphond.sh — run this ONCE on your free VPS to deploy Psiphon server
# Usage: curl -sSL https://raw.githubusercontent.com/e-creator1309/psiphon-android/master/scripts/setup-psiphond.sh | bash
set -e

echo "========================================"
echo "  Psiphond Auto-Setup"
echo "========================================"

# 1. Dependencies
apt-get update -qq
apt-get install -y -qq git wget curl build-essential python3

# 2. Install Go 1.22
if ! command -v go &>/dev/null; then
  ARCH=$(uname -m); [[ "$ARCH" == "aarch64" ]] && GOARCH="arm64" || GOARCH="amd64"
  wget -q "https://go.dev/dl/go1.22.5.linux-${GOARCH}.tar.gz" -O /tmp/go.tar.gz
  rm -rf /usr/local/go && tar -C /usr/local -xzf /tmp/go.tar.gz
  export PATH="/usr/local/go/bin:$PATH"
  echo 'export PATH="/usr/local/go/bin:$PATH"' >> /etc/profile
else
  export PATH="/usr/local/go/bin:$PATH"
  echo "Go already installed: $(go version)"
fi

# 3. Build psiphond
echo "Building psiphond (3-5 min)..."
mkdir -p /opt/psiphond-build && cd /opt/psiphond-build
[ ! -d psiphon-tunnel-core ] && git clone --depth=1 https://github.com/Psiphon-Labs/psiphon-tunnel-core.git
cd psiphon-tunnel-core/Server
go build -o /usr/local/bin/psiphond .
echo "psiphond built OK"

# 4. Generate config + server entry
mkdir -p /etc/psiphond && cd /etc/psiphond
SERVER_IP=$(curl -4 -s https://api.ipify.org || curl -4 -s https://ifconfig.me || hostname -I | awk '{print $1}')
echo "Server IP: $SERVER_IP"

if [ ! -f psiphond.config ]; then
  /usr/local/bin/psiphond \
    -ipaddress "$SERVER_IP" \
    -protocol SSH:22 \
    -protocol OSSH:443 \
    -protocol UNFRONTED-MEEK-HTTPS-OSSH:8080 \
    -web 80 \
    generate
  echo "Config generated."
else
  echo "Config exists, skipping generation."
fi

# Extract server entry signature public key from config
SERVER_ENTRY_KEY=$(python3 - <<'PYEOF'
import json, sys
try:
    with open("/etc/psiphond/psiphond.config") as f:
        cfg = json.load(f)
    print(cfg.get("ServerEntrySignaturePublicKey", ""))
except Exception as e:
    print("")
PYEOF
)

# 5. Install and start systemd service
cat > /etc/systemd/system/psiphond.service <<'SERVICE'
[Unit]
Description=Psiphon Server Daemon
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=/etc/psiphond
ExecStart=/usr/local/bin/psiphond run
Restart=always
RestartSec=10
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
SERVICE

# Open firewall ports
for PORT in 22 80 443 8080; do
  ufw allow ${PORT}/tcp 2>/dev/null || true
  iptables -I INPUT -p tcp --dport ${PORT} -j ACCEPT 2>/dev/null || true
done

systemctl daemon-reload
systemctl enable psiphond
systemctl restart psiphond
sleep 3
systemctl is-active --quiet psiphond && echo "psiphond is RUNNING" || echo "WARNING: check: systemctl status psiphond"

SERVER_ENTRY=$(cat /etc/psiphond/server-entry.dat 2>/dev/null || echo "NOT_FOUND")

echo ""
echo "========================================================"
echo "  DONE — ADD THESE AS GITHUB REPOSITORY SECRETS"
echo "  Repo → Settings → Secrets → Actions → New secret"
echo "========================================================"
echo ""
echo "Secret 1 name:  SERVER_ENTRY"
echo "Secret 1 value (copy everything on the next line):"
echo "$SERVER_ENTRY"
echo ""
echo "Secret 2 name:  SERVER_ENTRY_KEY"
echo "Secret 2 value (copy everything on the next line):"
echo "$SERVER_ENTRY_KEY"
echo "========================================================"
