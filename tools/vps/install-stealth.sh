#!/usr/bin/env bash
# ==============================================================================
# snolc Stealth REALITY Automated VPS Installer
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
apt-get install -y -q curl git build-essential pkg-config libssl-dev qrencode iptables ufw python3

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
mkdir -p "$INSTALL_DIR"/{bin,etc,state,modules,packages}
chmod 700 "$INSTALL_DIR"/state

log "===> 3. Установка Rust toolchain (1.98.1)..."
if ! command -v cargo &>/dev/null; then
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain 1.98.1
    export PATH="$HOME/.cargo/bin:$PATH"
else
    export PATH="$HOME/.cargo/bin:$PATH"
fi
ok "Rust готов."

log "===> 4. Сборка ядра snolc..."
BUILD_DIR="/tmp/snolc-build-$$"
mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"
if [ ! -d "snolc" ]; then
    git clone --depth 1 https://github.com/owenewans/snolc.git
fi
cd snolc
cargo build --release --locked -p snolc-cli
cp target/release/snolc "$INSTALL_DIR/bin/"
chmod +x "$INSTALL_DIR/bin/snolc"
ok "Бинарник snolc собран и установлен в $INSTALL_DIR/bin/snolc."

log "===> 5. Генерация криптографических ключей и маскировки..."
SERVER_IP=$(curl -s -4 https://api.ipify.org || curl -s -4 https://icanhazip.com || echo "127.0.0.1")
LISTEN_PORT=443
FALLBACK_SNI="dl.google.com"
FALLBACK_IP="142.250.190.46:443"

PRESHARED_KEY=$(openssl rand -hex 16)
SHORT_ID=$(openssl rand -hex 8)
USER_ID=$(openssl rand -hex 16)
BEARER_TOKEN=$(openssl rand -hex 32)
TOKEN_HASH=$(echo -n "$BEARER_TOKEN" | sha256sum | awk '{print $1}')

log "===> 6. Создание конфигурационных файлов..."

# 6.1. Модуль Carrier TCP
cat <<EOF > "$INSTALL_DIR/etc/tcp.toml"
wire_version = 1
instance = "carrier-tcp"
package = "official/carrier-tcp@0.0.4"
role = "server"

[options]
listen = "0.0.0.0:$LISTEN_PORT"
nodelay = true
keepalive_ms = 30000
EOF

# 6.2. Модуль Protection REALITY
cat <<EOF > "$INSTALL_DIR/etc/reality.toml"
wire_version = 1
instance = "protection-reality"
package = "official/protection-reality@0.0.4"
role = "server"

[options]
server_name = "$FALLBACK_SNI"
short_id = "$SHORT_ID"
preshared_key = "$PRESHARED_KEY"
fallback_addr = "$FALLBACK_IP"
EOF

# 6.3. Модуль Adapter Direct
cat <<EOF > "$INSTALL_DIR/etc/direct.toml"
wire_version = 1
instance = "adapter-direct"
package = "official/adapter-direct@0.0.4"
role = "server"

[options]
dns_timeout_ms = 5000
max_dns_workers = 4
EOF

# 6.4. Модуль Policy Local
cat <<EOF > "$INSTALL_DIR/etc/policy.toml"
wire_version = 1
instance = "policy-main"
package = "official/policy-local@0.0.4"
role = "server"

[options]
database_path = "$INSTALL_DIR/state/policy.redb"
accounting_block_bytes = 10485760
status_interval_ms = 1000
EOF

# 6.5. Основной конфиг snolc.toml
cat <<EOF > "$INSTALL_DIR/etc/snolc.toml"
wire_version = 1

[paths]
packages = "$INSTALL_DIR/packages"
state = "$INSTALL_DIR/state"

[engine]
max_sessions = 128
max_flows = 1024
max_pending_sessions = 16
max_pending_opens = 64
max_managed_bytes = 67108864
max_commands = 128
max_events = 512
max_io_chunk = 16384
max_ingress_packets_per_tick = 64
connect_timeout_ms = 15000
handshake_timeout_ms = 15000
shutdown_timeout_ms = 5000

[stack]
ipv4 = true
ipv6 = true
mtu = 1280
tcp_socket_rx_bytes = 32768
tcp_socket_tx_bytes = 32768
udp_socket_rx_bytes = 131072
udp_socket_tx_bytes = 131072
udp_metadata_slots = 16
packet_queue_bytes = 524288
max_udp_payload_bytes = 65507
reassembly_slots = 8
reassembly_timeout_ms = 15000

[yamux]
max_streams_per_session = 64
receive_window_bytes = 4456448
split_send_size = 16384
read_after_close = true

[logging]
mode = "file"
source = "toml"
levels = ["warning", "error"]
file = "$INSTALL_DIR/state/snolc.log"
limit = "16mb"
queue_bytes = 131072
max_record_bytes = 2048
flush_interval_ms = 1000
on_io_error = "event"

[control]
mode = "unix"
path = "$INSTALL_DIR/state/snolc.sock"
max_request_bytes = 65536
max_connections = 4

[[tunnels]]
name = "reality-main"
role = "server"
adapters = ["$INSTALL_DIR/etc/direct.toml"]
protection = "$INSTALL_DIR/etc/reality.toml"
carrier = "$INSTALL_DIR/etc/tcp.toml"
policy = "$INSTALL_DIR/etc/policy.toml"
EOF

log "===> 7. Создание службы systemd..."
cat <<EOF > /etc/systemd/system/snolc-stealth.service
[Unit]
Description=snolc stealth REALITY proxy engine
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
ok "Служба snolc-stealth запущена!"

# Открываем порт в UFW если он включен
if ufw status | grep -qw "active"; then
    ufw allow "$LISTEN_PORT"/tcp
fi

log "===> 8. Регистрация пользователя в redb..."
sleep 2
cat <<EOF > "$BUILD_DIR/user-create.toml"
method = "user.create"
client_id = "vps-init"
seq = 1

[user]
status = "enabled"
weight = 1
group = "default"
rule_profile = "default"
EOF

# Инициализируем первого пользователя через сокет управления
"$INSTALL_DIR/bin/snolc" control "$INSTALL_DIR/state/snolc.sock" policy-main "$BUILD_DIR/user-create.toml" || true

# Формируем клиентскую ссылку
CLIENT_PROFILE_URL="snolc://profile/?server=${SERVER_IP}:${LISTEN_PORT}&proto=reality&sni=${FALLBACK_SNI}&key=${PRESHARED_KEY}&short_id=${SHORT_ID}&user=${USER_ID}&token=${BEARER_TOKEN}"

# Сохраняем готовый клиентский конфиг TOML для Android
cat <<EOF > "$INSTALL_DIR/client.toml"
wire_version = 1
server = "${SERVER_IP}:${LISTEN_PORT}"
server_name = "${FALLBACK_SNI}"
preshared_key = "${PRESHARED_KEY}"
short_id = "${SHORT_ID}"
user_id = "${USER_ID}"
bearer_token = "${BEARER_TOKEN}"
EOF

rm -rf "$BUILD_DIR"

echo ""
echo "=========================================================================="
echo -e "${GREEN}       ПОЛНОСТЬЮ АВТОМАТИЗИРОВАННАЯ УСТАНОВКА ЗАВЕРШЕНА!${NC}"
echo "=========================================================================="
echo -e "IP Сервера:      ${CYAN}${SERVER_IP}${NC}"
echo -e "Порт:            ${CYAN}${LISTEN_PORT}${NC}"
echo -e "Маскировка под:  ${CYAN}${FALLBACK_SNI}${NC}"
echo -e "Ключ защиты:     ${CYAN}${PRESHARED_KEY}${NC}"
echo -e "Short ID:        ${CYAN}${SHORT_ID}${NC}"
echo "--------------------------------------------------------------------------"
echo -e "${YELLOW}Клиентская ссылка профиля (скопируйте в буфер или Android):${NC}"
echo -e "${GREEN}${CLIENT_PROFILE_URL}${NC}"
echo "--------------------------------------------------------------------------"
echo -e "${YELLOW}QR-код для импорта в Android:${NC}"
qrencode -t ANSIUTF8 "${CLIENT_PROFILE_URL}"
echo "=========================================================================="
