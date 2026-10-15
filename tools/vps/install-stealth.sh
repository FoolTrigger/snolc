#!/usr/bin/env bash
# ==============================================================================
# snolc Stealth Noise/TCP Automated VPS Installer
# Target: Ubuntu 20.04/22.04/24.04, Debian 11/12
# ==============================================================================

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

log() { echo -e "${CYAN}[snolc-stealth]${NC} $1"; }
ok()  { echo -e "${GREEN}[✔]${NC} $1"; }
warn(){ echo -e "${YELLOW}[!]${NC} $1"; }
err() { echo -e "${RED}[✖]${NC} $1" >&2; exit 1; }

if [ "$(id -u)" -ne 0 ]; then
    err "Этот скрипт должен быть запущен с правами root!"
fi

log "===> 1. Обновление системы и установка пакетов..."
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q curl git build-essential pkg-config libssl-dev qrencode iptables ufw python3 python3-cryptography

log "===> 2. Включение BBR и сетевая оптимизация..."
modprobe tcp_bbr 2>/dev/null || true
cat <<EOF > /etc/sysctl.d/99-snolc.conf
net.core.default_qdisc = fq
net.ipv4.tcp_congestion_control = bbr
net.ipv4.ip_forward = 1
net.ipv6.conf.all.forwarding = 1
net.ipv4.tcp_fastopen = 3
EOF
sysctl --system &>/dev/null || true
ok "BBR и IP forwarding активированы."

INSTALL_DIR="/opt/snolc"
systemctl stop snolc-stealth 2>/dev/null || true
mkdir -p "$INSTALL_DIR"/{bin,etc,state,keys,packages}
chmod 700 "$INSTALL_DIR"/state
chmod 700 "$INSTALL_DIR"/keys
rm -f "$INSTALL_DIR"/state/snolc.sock

log "===> 3. Установка Rust toolchain (1.98.1)..."
if ! command -v cargo &>/dev/null; then
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain 1.98.1
    export PATH="$HOME/.cargo/bin:$PATH"
else
    export PATH="$HOME/.cargo/bin:$PATH"
fi
ok "Rust готов."

BUILD_DIR="/tmp/snolc-build-$$"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

log "===> 4. Сборка ядра snolc..."
cd "$BUILD_DIR"
rm -rf snolc
git clone --depth 1 https://github.com/fooltrigger/snolc.git snolc
cd snolc
cargo build --release -p snolc-cli
cp target/release/snolc "$INSTALL_DIR/bin/"
chmod +x "$INSTALL_DIR/bin/snolc"
ok "Бинарник snolc собран и установлен в $INSTALL_DIR/bin/snolc."

log "===> 5. Сборка и установка официальных нативных модулей..."
ARCH=$(uname -m)
case "$ARCH" in
    x86_64) TARGET="x86_64-unknown-linux-gnu" ;;
    aarch64|arm64) TARGET="aarch64-unknown-linux-gnu" ;;
    *) TARGET="x86_64-unknown-linux-gnu" ;;
esac

MODULES=("carrier-tcp" "protection-noise" "adapter-direct" "policy-local")

for MOD in "${MODULES[@]}"; do
    MOD_UNDERSCORE="${MOD//-/_}"
    LIB_NAME="libsnolc_${MOD_UNDERSCORE}.so"
    TMP_SO="$BUILD_DIR/${LIB_NAME}"

    log "Сборка модуля owenewans/${MOD}@0.0.4 из исходников..."
    cd "$BUILD_DIR/snolc"
    cargo build --release -p "snolc-${MOD}"
    cp "target/release/${LIB_NAME}" "$TMP_SO"

    CONTENT_SHA256=$(sha256sum "$TMP_SO" | awk '{print $1}')
    STORE_DIR="$INSTALL_DIR/packages/store/owenewans/${MOD}/0.0.4/${TARGET}/${CONTENT_SHA256}"
    mkdir -p "$STORE_DIR"
    cp "$TMP_SO" "$STORE_DIR/${LIB_NAME}"

    LOCK_DIR="$INSTALL_DIR/packages/locks/owenewans/${MOD}"
    mkdir -p "$LOCK_DIR"
    cat <<EOF > "$LOCK_DIR/0.0.4.toml"
wire_version = 1
package = "owenewans/${MOD}@0.0.4"
target = "${TARGET}"
content_sha256 = "${CONTENT_SHA256}"
library = "../../../store/owenewans/${MOD}/0.0.4/${TARGET}/${CONTENT_SHA256}/${LIB_NAME}"
store = "../../../store/owenewans/${MOD}/0.0.4/${TARGET}/${CONTENT_SHA256}"
dependencies = []
EOF

    ok "Модуль owenewans/${MOD}@0.0.4 установлен (хеш: ${CONTENT_SHA256:0:16}...)."
done

log "===> 6. Генерация криптографических ключей Noise и учетных данных..."
SERVER_IP=$(curl -s -4 https://api.ipify.org || curl -s -4 https://icanhazip.com || echo "127.0.0.1")
LISTEN_PORT=443
FALLBACK_SNI="dl.google.com"

# Генерация Curve25519 (X25519) ключей для Noise
python3 - <<EOF
from cryptography.hazmat.primitives.asymmetric import x25519
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat, PrivateFormat, NoEncryption

priv = x25519.X25519PrivateKey.generate()
pub = priv.public_key()
priv_hex = priv.private_bytes(Encoding.Raw, PrivateFormat.Raw, NoEncryption()).hex()
pub_hex = pub.public_bytes(Encoding.Raw, PublicFormat.Raw).hex()

with open("$INSTALL_DIR/keys/noise-private.hex", "w") as f:
    f.write(priv_hex + "\n")
with open("$INSTALL_DIR/keys/noise-public.hex", "w") as f:
    f.write(pub_hex + "\n")
EOF

chmod 600 "$INSTALL_DIR/keys/noise-private.hex"
chmod 644 "$INSTALL_DIR/keys/noise-public.hex"
NOISE_PUBLIC_KEY=$(tr -d '\r\n' < "$INSTALL_DIR/keys/noise-public.hex")

BEARER_TOKEN=$(openssl rand -hex 32)
TOKEN_HASH=$(python3 -c "import hashlib, binascii, sys; print(hashlib.sha256(binascii.unhexlify(sys.argv[1])).hexdigest())" "$BEARER_TOKEN")

log "===> 7. Создание конфигурационных файлов сервера..."

# 7.1. Carrier TCP
cat <<EOF > "$INSTALL_DIR/etc/tcp.toml"
wire_version = 1
instance = "tcp-main"
package = "owenewans/carrier-tcp@0.0.4"
role = "server"

[options]
mode = "listen"
endpoint_ip = "0.0.0.0:$LISTEN_PORT"
max_connections = 16
nodelay = true
EOF

# 7.2. Protection Noise
cat <<EOF > "$INSTALL_DIR/etc/noise.toml"
wire_version = 1
instance = "noise-main"
package = "owenewans/protection-noise@0.0.4"
role = "server"

[options]
mode = "server"
private_key_file = "$INSTALL_DIR/keys/noise-private.hex"
EOF

# 7.3. Adapter Direct
cat <<EOF > "$INSTALL_DIR/etc/direct.toml"
wire_version = 1
instance = "direct-main"
package = "owenewans/adapter-direct@0.0.4"
role = "server"

[options]
dns_mode = "system"
max_pending_opens = 16
max_resolved_addresses = 16
resolve_timeout_ms = 15000
connect_timeout_ms = 15000
EOF

# 7.4. Policy Local
cat <<EOF > "$INSTALL_DIR/etc/policy.toml"
wire_version = 1
instance = "policy-main"
package = "owenewans/policy-local@0.0.4"
role = "server"

[options]
server_id = "node-1"
credential_transport = "protected"
max_cached_users = 256
max_admin_clients = 16
max_control_frame_bytes = 16384
status_interval_ms = 1000
control_grace_ms = 10000
sniff_bytes = 16384
sniff_timeout_ms = 2000
on_unknown_protocol = "allow"
checkpoint_interval_ms = 5000

[options.storage]
path = "$INSTALL_DIR/state/policy.redb"
cache_bytes = 4194304
max_database_bytes = 134217728
queue_capacity = 64
accounting_block_bytes = 1048576
batch_max_delay_ms = 5

[options.global_rate]
mode = "unlimited"

[options.rules]
terminal = "allow"
entries = []
EOF

# 7.5. Основной конфиг snolc.toml
cat <<EOF > "$INSTALL_DIR/etc/snolc.toml"
wire_version = 1

[paths]
packages = "$INSTALL_DIR/packages"
state = "$INSTALL_DIR/state"

[engine]
max_sessions = 16
max_flows = 256
max_pending_sessions = 4
max_pending_opens = 16
max_managed_bytes = 134217728
max_commands = 64
max_events = 256
max_io_chunk = 16384
max_ingress_packets_per_tick = 32
connect_timeout_ms = 15000
handshake_timeout_ms = 15000
shutdown_timeout_ms = 5000

[stack]
ipv4 = true
ipv6 = true
mtu = 1280
tcp_socket_rx_bytes = 16384
tcp_socket_tx_bytes = 16384
udp_socket_rx_bytes = 131072
udp_socket_tx_bytes = 131072
udp_metadata_slots = 8
packet_queue_bytes = 262144
max_udp_payload_bytes = 65507
reassembly_slots = 4
reassembly_timeout_ms = 15000

[yamux]
max_streams_per_session = 17
receive_window_bytes = 4456448
split_send_size = 16384
read_after_close = true

[logging]
mode = "file"
source = "toml"
levels = ["warning", "error", "debug"]
file = "$INSTALL_DIR/state/snolc.log"
limit = "16mb"
queue_bytes = 65536
max_record_bytes = 2048
flush_interval_ms = 1000
on_io_error = "event"

[control]
mode = "unix"
path = "$INSTALL_DIR/state/snolc.sock"
max_request_bytes = 65536
max_connections = 4

[[tunnels]]
name = "main"
role = "server"
adapters = ["$INSTALL_DIR/etc/direct.toml"]
protection = "$INSTALL_DIR/etc/noise.toml"
carrier = "$INSTALL_DIR/etc/tcp.toml"
policy = "$INSTALL_DIR/etc/policy.toml"
EOF

log "===> 8. Проверка корректности конфигурации..."
"$INSTALL_DIR/bin/snolc" validate "$INSTALL_DIR/etc/snolc.toml"
ok "Конфигурация проверена ядром snolc (validate: OK)!"

log "===> 9. Запуск службы snolc-stealth в systemd..."
cat <<EOF > /etc/systemd/system/snolc-stealth.service
[Unit]
Description=snolc stealth proxy engine
After=network.target

[Service]
Type=simple
User=root
ExecStart=$INSTALL_DIR/bin/snolc run $INSTALL_DIR/etc/snolc.toml
Restart=always
RestartSec=3
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now snolc-stealth
ok "Служба snolc-stealth активирована!"

if ufw status 2>/dev/null | grep -qw "active"; then
    ufw allow "$LISTEN_PORT"/tcp
fi

log "===> 10. Ожидание готовности сокета управления и регистрация пользователя..."
SOCK="$INSTALL_DIR/state/snolc.sock"
SOCKET_READY=false
for i in $(seq 1 30); do
    if [ -S "$SOCK" ]; then
        SOCKET_READY=true
        break
    fi
    sleep 0.5
done

if [ "$SOCKET_READY" != "true" ]; then
    warn "Сокет не появился вовремя. Логи службы:"
    journalctl -u snolc-stealth -n 30 --no-pager || true
    err "Сокет управления $SOCK не доступен!"
fi

cat <<EOF > "$BUILD_DIR/user-create.toml"
method = "user.create"
client_id = "vps-init"
seq = 1

[user]
status = "enabled"
burst_bytes = 65507
weight = 1
group = "default"
rule_profile = "default"

[user.expiration]
mode = "unlimited"
[user.weekly_access]
mode = "unlimited"
[user.quota]
mode = "unlimited"
[user.upload_rate]
mode = "unlimited"
[user.download_rate]
mode = "unlimited"
[user.combined_rate]
mode = "unlimited"
[user.max_sessions]
mode = "limited"
count = 10
[user.max_flows]
mode = "limited"
count = 64
EOF

USER_CREATE_RESP=$("$INSTALL_DIR/bin/snolc" control "$SOCK" policy-main "$BUILD_DIR/user-create.toml")
echo "$USER_CREATE_RESP" > "$BUILD_DIR/user-create-resp.toml"

USER_ID=$(python3 -c "import re, sys; m = re.search(r'user_id\s*=\s*\"([^\"]+)\"', open(sys.argv[1]).read()); print(m.group(1) if m else '')" "$BUILD_DIR/user-create-resp.toml")

if [ -z "$USER_ID" ]; then
    warn "Ответ policy-main: $USER_CREATE_RESP"
    err "Не удалось получить user_id от policy-main!"
fi
ok "Создан пользователь ID: $USER_ID"

cat <<EOF > "$BUILD_DIR/credential-add.toml"
method = "credential.add"
client_id = "vps-init"
seq = 2
user_id = "$USER_ID"
credential_sha256 = "$TOKEN_HASH"
EOF

"$INSTALL_DIR/bin/snolc" control "$SOCK" policy-main "$BUILD_DIR/credential-add.toml"
ok "Токен авторизации успешно зарегистрирован!"

# Формируем клиентскую ссылку профиля для Android
CLIENT_PROFILE_URL="snolc://profile/?server=${SERVER_IP}:${LISTEN_PORT}&proto=noise&sni=${FALLBACK_SNI}&key=${NOISE_PUBLIC_KEY}&user=${USER_ID}&token=${BEARER_TOKEN}"

# Сохраняем клиентские файлы
echo "$CLIENT_PROFILE_URL" > "$INSTALL_DIR/client-profile.url"
cat <<EOF > "$INSTALL_DIR/client.toml"
wire_version = 1
server = "${SERVER_IP}:${LISTEN_PORT}"
server_name = "${FALLBACK_SNI}"
noise_public_key = "${NOISE_PUBLIC_KEY}"
user_id = "${USER_ID}"
bearer_token = "${BEARER_TOKEN}"
EOF

rm -rf "$BUILD_DIR"

echo ""
echo "=========================================================================="
echo -e "${GREEN}       ПОЛНОСТЬЮ АВТОМАТИЗИРОВАННАЯ УСТАНОВКА ЗАВЕРШЕНА!${NC}"
echo "=========================================================================="
echo -e "IP Сервера:        ${CYAN}${SERVER_IP}${NC}"
echo -e "Порт:              ${CYAN}${LISTEN_PORT}${NC}"
echo -e "Noise Public Key:  ${CYAN}${NOISE_PUBLIC_KEY}${NC}"
echo -e "User ID:           ${CYAN}${USER_ID}${NC}"
echo -e "Bearer Token:      ${CYAN}${BEARER_TOKEN}${NC}"
echo "--------------------------------------------------------------------------"
echo -e "${YELLOW}Клиентская ссылка профиля (скопируйте в буфер или Android):${NC}"
echo -e "${GREEN}${CLIENT_PROFILE_URL}${NC}"
echo "--------------------------------------------------------------------------"
echo -e "${YELLOW}QR-код для импорта в Android:${NC}"
qrencode -t ANSIUTF8 "${CLIENT_PROFILE_URL}"
echo "=========================================================================="
