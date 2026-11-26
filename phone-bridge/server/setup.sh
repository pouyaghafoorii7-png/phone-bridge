#!/usr/bin/env bash
# Phone Bridge relay: one-command install on a fresh Ubuntu/Debian server.
#   sudo bash setup.sh            # listens on 443 (wss)
#   sudo bash setup.sh 8443       # another port
# Put relay.py next to this script first (or keep the repo layout).
set -euo pipefail
PORT=${1:-443}
DIR=/opt/phone-bridge
HERE=$(cd "$(dirname "$0")" && pwd)
SRC=$HERE/relay.py
[ -f "$SRC" ] || SRC=$HERE/../relay/relay.py
[ -f "$SRC" ] || { echo "relay.py not found next to setup.sh"; exit 1; }
[ "$(id -u)" = 0 ] || { echo "run as root (sudo)"; exit 1; }

echo "== packages"
apt-get update -qq
apt-get install -y -qq python3 python3-venv openssl >/dev/null

echo "== files and user"
id pbridge >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin pbridge
install -d -m 755 $DIR
install -m 644 "$SRC" $DIR/relay.py
if [ ! -x $DIR/venv/bin/python ]; then python3 -m venv $DIR/venv; fi
if ! $DIR/venv/bin/pip install -q 'websockets>=13,<16'; then
  echo "pip failed (network?) - using the distro package instead"
  apt-get install -y -qq python3-websockets >/dev/null
  rm -rf $DIR/venv && python3 -m venv --system-site-packages $DIR/venv
fi

echo "== TLS certificate (self-signed, pinned by the apps)"
if [ ! -f $DIR/cert.pem ]; then
  openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 3650 \
    -subj "/CN=phone-bridge" -keyout $DIR/key.pem -out $DIR/cert.pem 2>/dev/null
fi
chown pbridge:pbridge $DIR/key.pem $DIR/cert.pem
chmod 600 $DIR/key.pem

echo "== service"
cat > /etc/systemd/system/phone-bridge-relay.service <<UNIT
[Unit]
Description=Phone Bridge relay (end-to-end encrypted, stores nothing on disk)
After=network-online.target

[Service]
User=pbridge
ExecStart=$DIR/venv/bin/python $DIR/relay.py --port $PORT --cert $DIR/cert.pem --key $DIR/key.pem
Restart=always
RestartSec=3
AmbientCapabilities=CAP_NET_BIND_SERVICE
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
PrivateDevices=yes
ProtectKernelTunables=yes
ProtectControlGroups=yes
RestrictSUIDSGID=yes
LockPersonality=yes
MemoryDenyWriteExecute=yes

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable --now phone-bridge-relay
systemctl restart phone-bridge-relay
if command -v ufw >/dev/null && ufw status | grep -q active; then ufw allow "$PORT/tcp" >/dev/null; fi

sleep 1
systemctl is-active --quiet phone-bridge-relay || { journalctl -u phone-bridge-relay -n 30 --no-pager; exit 1; }
IP=$(hostname -I | awk '{print $1}')
FP=$(openssl x509 -in $DIR/cert.pem -noout -fingerprint -sha256 | cut -d= -f2 | tr -d : | tr 'A-F' 'a-f')
echo
echo "Relay is running."
echo "  Address for the Android app:  wss://$IP:$PORT"
echo "  Certificate fingerprint:      $FP"
echo "  (The Android app learns this fingerprint when you create the pairing;"
echo "   check that the first 16 characters shown in the app match.)"
