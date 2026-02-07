#!/bin/bash
#
# Provisions a fresh Fedora server for howfar.nyc.
# Usage: sudo ./setup-server.sh
#
# Environment variables (optional):
#   DOMAIN      - Domain name (default: howfar.nyc)
#   DB_PASSWORD - PostgreSQL password (auto-generated if not set)
#

set -euo pipefail

DOMAIN="${DOMAIN:-howfar.nyc}"
HOWFAR_USER="howfar"
HOWFAR_DIR="/opt/howfar"
DB_PASSWORD="${DB_PASSWORD:-$(openssl rand -base64 24 | tr -dc 'a-zA-Z0-9' | head -c 32)}"

if [[ $EUID -ne 0 ]]; then
    echo "Error: run as root" >&2
    exit 1
fi

# --- Packages ----------------------------------------------------------------

echo "Installing packages..."
dnf upgrade -y

# Add Adoptium repo for Java 21
cat > /etc/yum.repos.d/adoptium.repo << 'EOF'
[Adoptium]
name=Adoptium
baseurl=https://packages.adoptium.net/artifactory/rpm/fedora/$releasever/$basearch
enabled=1
gpgcheck=1
gpgkey=https://packages.adoptium.net/artifactory/api/gpg/key/public
EOF

dnf install -y \
    postgresql-server postgresql-contrib postgis \
    temurin-21-jdk \
    nodejs npm \
    python3 python3-pip \
    podman podman-compose containernetworking-plugins \
    nginx certbot python3-certbot-nginx \
    git curl

cat > /etc/profile.d/java.sh << 'EOF'
export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk
export PATH=$JAVA_HOME/bin:$PATH
EOF
source /etc/profile.d/java.sh

# Clojure CLI
curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh
chmod +x linux-install.sh && ./linux-install.sh --prefix /usr/local && rm linux-install.sh

# --- PostgreSQL ---------------------------------------------------------------

echo "Setting up PostgreSQL..."
[[ ! -f /var/lib/pgsql/data/PG_VERSION ]] && postgresql-setup --initdb
systemctl enable --now postgresql

cat >> /var/lib/pgsql/data/postgresql.conf << 'EOF'

# howfar.nyc tuning
listen_addresses = 'localhost'
password_encryption = scram-sha-256
shared_buffers = 4GB
effective_cache_size = 20GB
work_mem = 32MB
maintenance_work_mem = 1GB
random_page_cost = 1.1
jit = off
max_connections = 50
EOF

cat > /var/lib/pgsql/data/pg_hba.conf << 'EOF'
local   all    postgres                peer
local   howfar howfar                  scram-sha-256
host    howfar howfar  127.0.0.1/32    scram-sha-256
host    howfar howfar  ::1/128         scram-sha-256
EOF

systemctl restart postgresql

sudo -u postgres psql << EOSQL
DO \$\$ BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'howfar') THEN
        CREATE USER howfar WITH PASSWORD '${DB_PASSWORD}';
    END IF;
END \$\$;
SELECT 'CREATE DATABASE howfar OWNER howfar'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'howfar')\gexec
\c howfar
CREATE EXTENSION IF NOT EXISTS postgis;
GRANT ALL PRIVILEGES ON DATABASE howfar TO howfar;
GRANT ALL ON SCHEMA public TO howfar;
ALTER USER howfar PASSWORD '${DB_PASSWORD}';
EOSQL

# --- Application user & directories ------------------------------------------

echo "Setting up application..."
id -u "${HOWFAR_USER}" &>/dev/null || useradd -r -m -d "${HOWFAR_DIR}" -s /bin/bash "${HOWFAR_USER}"
mkdir -p "${HOWFAR_DIR}"/{app,otp-data,logs}

cat > "${HOWFAR_DIR}/howfar.env" << EOF
PORT=3001
DB_NAME=howfar
DB_HOST=localhost
DB_PORT=5432
DB_USER=howfar
DB_PASSWORD=${DB_PASSWORD}
OTP_URL=http://localhost:8080
JAVA_HOME=/usr/lib/jvm/temurin-21-jdk
EOF

chmod 600 "${HOWFAR_DIR}/howfar.env"
chown -R "${HOWFAR_USER}:${HOWFAR_USER}" "${HOWFAR_DIR}"

# --- nginx reverse proxy ------------------------------------------------------

echo "Configuring nginx..."
setsebool -P httpd_can_network_connect 1

cat > /etc/nginx/nginx.conf << 'NGINXEOF'
user nginx;
worker_processes auto;
error_log /var/log/nginx/error.log;
pid /run/nginx.pid;

events { worker_connections 1024; }

http {
    include /etc/nginx/mime.types;
    default_type application/octet-stream;
    sendfile on;
    keepalive_timeout 65;
    include /etc/nginx/conf.d/*.conf;
}
NGINXEOF

cat > /etc/nginx/conf.d/howfar.conf << EOF
upstream backend { server 127.0.0.1:3001; keepalive 32; }
upstream otp     { server 127.0.0.1:8080; keepalive 32; }

server {
    listen 80;
    server_name ${DOMAIN};

    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header X-Content-Type-Options "nosniff" always;

    location / {
        root ${HOWFAR_DIR}/app/public;
        try_files \$uri \$uri/ /index.html;
        location ~* \.(js|css|png|jpg|svg|woff2?)$ {
            expires 1y;
            add_header Cache-Control "public, immutable";
        }
    }

    location /api/ {
        proxy_pass http://backend;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 120s;
    }

    location /otp/ {
        proxy_pass http://otp/otp/;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_read_timeout 120s;
    }
}
EOF

nginx -t
systemctl enable nginx

# --- systemd services ---------------------------------------------------------

echo "Creating systemd services..."

cat > /etc/systemd/system/howfar-backend.service << EOF
[Unit]
Description=howfar.nyc Backend
After=network-online.target postgresql.service
Requires=postgresql.service

[Service]
Type=simple
User=${HOWFAR_USER}
WorkingDirectory=${HOWFAR_DIR}/app
EnvironmentFile=${HOWFAR_DIR}/howfar.env
Environment="PATH=/usr/lib/jvm/temurin-21-jdk/bin:/usr/local/bin:/usr/bin"
ExecStart=/usr/local/bin/clj -J-Xms512m -J-Xmx2g -M:run
Restart=on-failure
RestartSec=10
StandardOutput=append:${HOWFAR_DIR}/logs/backend.log
StandardError=append:${HOWFAR_DIR}/logs/backend-error.log
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=${HOWFAR_DIR}/logs

[Install]
WantedBy=multi-user.target
EOF

cat > /etc/systemd/system/howfar-otp.service << EOF
[Unit]
Description=howfar.nyc OTP Cluster
After=network-online.target

[Service]
Type=simple
User=root
WorkingDirectory=${HOWFAR_DIR}/app
ExecStart=/usr/bin/podman-compose up
ExecStop=/usr/bin/podman-compose down
Restart=on-failure
RestartSec=30
TimeoutStartSec=600
StandardOutput=append:${HOWFAR_DIR}/logs/otp.log
StandardError=append:${HOWFAR_DIR}/logs/otp-error.log

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload

# --- Firewall -----------------------------------------------------------------

systemctl enable --now firewalld
firewall-cmd --permanent --add-service={http,https,ssh}
firewall-cmd --reload

# --- SELinux ------------------------------------------------------------------

if command -v restorecon &>/dev/null; then
    restorecon -Rv "${HOWFAR_DIR}" 2>/dev/null || true
fi

# --- Done ---------------------------------------------------------------------

cat << EOF

========================================
Setup complete
========================================

  DB password:  ${DB_PASSWORD}
  Env file:     ${HOWFAR_DIR}/howfar.env
  App dir:      ${HOWFAR_DIR}/app

Next steps:
  1. Deploy app code to ${HOWFAR_DIR}/app
  2. Build frontend:  cd ${HOWFAR_DIR}/app && npm install && npx shadow-cljs release app
  3. Start OTP:       systemctl start howfar-otp    (wait ~5 min for graph load)
  4. Start backend:   systemctl start howfar-backend
  5. Start nginx:     systemctl start nginx
  6. SSL:             certbot --nginx -d ${DOMAIN} --redirect

Save the DB password above!

EOF
