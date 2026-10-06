#!/bin/bash
# 初回起動のときだけ実行される(cloud-init)。ここでは、土台(Java・Caddy・データ用ボリューム・systemd)だけを用意する。
# アプリ本体(jarと画面)は、deploy/deploy.sh がS3へ置き、raisechat-update が取り込む。
# 秘密情報(JWT秘密鍵)は、Terraformの値(=stateファイル)に載せず、データ用ボリュームの上で生成する。
set -euxo pipefail
exec > >(tee -a /var/log/raisechat-bootstrap.log) 2>&1

SITE_ADDRESS="${site_address}"
BUCKET="${artifacts_bucket}"
REGION="${aws_region}"
CADDY_VERSION="${caddy_version}"
DATA_DIR=/var/lib/raisechat

# --- パッケージ -----------------------------------------------------------
dnf install -y java-21-amazon-corretto-headless tar gzip

# 小さいインスタンスでのメモリ不足に備えて、1GBのスワップを用意する
if [ ! -f /swapfile ]; then
  dd if=/dev/zero of=/swapfile bs=1M count=1024
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile swap swap defaults 0 0' >> /etc/fstab
fi

# --- データ用ボリューム(別のEBS)をマウントする ----------------------------
# ボリュームの接続は、インスタンスの作成より少し後なので、現れるまで待つ(最大10分)
DEV="/dev/disk/by-id/nvme-Amazon_Elastic_Block_Store_${data_volume_id}"
for i in $(seq 120); do
  [ -e "$DEV" ] && break
  sleep 5
done
[ -e "$DEV" ] || { echo "data volume not found" >&2; exit 1; }

# 空のときだけフォーマットする(既存のデータは消さない)
if ! blkid "$DEV" >/dev/null 2>&1; then
  mkfs.xfs "$DEV"
fi
mkdir -p "$DATA_DIR"
UUID="$(blkid -s UUID -o value "$DEV")"
grep -q "$UUID" /etc/fstab || echo "UUID=$UUID $DATA_DIR xfs defaults,nofail 0 2" >> /etc/fstab
mount -a

# --- アプリ用のユーザーとディレクトリ ----------------------------------------
id raisechat >/dev/null 2>&1 || useradd --system --home-dir "$DATA_DIR" --shell /sbin/nologin raisechat
mkdir -p "$DATA_DIR/uploads" /opt/raisechat/web /etc/raisechat
chown -R raisechat:raisechat "$DATA_DIR"

# JWT秘密鍵: データ用ボリュームに、初回だけ生成する(インスタンスを作り直しても変わらない)
if [ ! -s "$DATA_DIR/jwt_secret" ]; then
  umask 077
  head -c 48 /dev/urandom | base64 -w0 > "$DATA_DIR/jwt_secret"
  chown raisechat:raisechat "$DATA_DIR/jwt_secret"
fi

# --- アプリの環境変数(秘密鍵は、起動時にファイルから読む) ---------------------
cat > /etc/raisechat/env <<ENVEOF
SPRING_PROFILES_ACTIVE=prod
SERVER_ADDRESS=127.0.0.1
SERVER_FORWARD_HEADERS_STRATEGY=native
SPRING_DATASOURCE_URL=jdbc:sqlite:$DATA_DIR/raisechat.db?journal_mode=WAL&busy_timeout=5000&foreign_keys=true
APP_UPLOAD_DIR=$DATA_DIR/uploads
APP_WEBSOCKET_ALLOWED_ORIGINS=https://$SITE_ADDRESS
ENVEOF
chmod 644 /etc/raisechat/env

cat > /etc/systemd/system/raisechat.service <<'UNITEOF'
[Unit]
Description=RaiseChat backend
After=network-online.target var-lib-raisechat.mount
Wants=network-online.target
RequiresMountsFor=/var/lib/raisechat

[Service]
User=raisechat
WorkingDirectory=/var/lib/raisechat
EnvironmentFile=/etc/raisechat/env
# 秘密鍵は、ファイルから読んで環境変数にする(unitファイルや環境変数のファイルには書かない)
ExecStart=/bin/bash -c 'export JWT_SECRET="$(cat /var/lib/raisechat/jwt_secret)"; exec /usr/bin/java -Xms128m -Xmx384m -jar /opt/raisechat/backend.jar'
Restart=on-failure
RestartSec=5
# 最低限の権限だけ(書き込めるのはデータ用ボリュームだけ)
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/var/lib/raisechat
PrivateTmp=true

[Install]
WantedBy=multi-user.target
UNITEOF

# --- Caddy(自動HTTPS・画面の配信・API/WebSocketの中継) ---------------------
cd /tmp
CADDY_TGZ="caddy_$${CADDY_VERSION}_linux_amd64.tar.gz"
CADDY_BASE="https://github.com/caddyserver/caddy/releases/download/v$${CADDY_VERSION}"
curl -fsSLO "$CADDY_BASE/$CADDY_TGZ"
curl -fsSLO "$CADDY_BASE/caddy_$${CADDY_VERSION}_checksums.txt"
grep " $CADDY_TGZ\$" "caddy_$${CADDY_VERSION}_checksums.txt" | sha512sum -c -
tar xzf "$CADDY_TGZ" caddy
install -m 755 caddy /usr/local/bin/caddy
id caddy >/dev/null 2>&1 || useradd --system --home-dir /var/lib/caddy --create-home --shell /sbin/nologin caddy
mkdir -p /etc/caddy

cat > /etc/caddy/Caddyfile <<CADDYEOF
$SITE_ADDRESS {
	encode zstd gzip
	header Strict-Transport-Security "max-age=31536000"

	# API・添付ファイル・WebSocketは、同じサーバー内のバックエンド(127.0.0.1:8080)へ。
	# これらのセキュリティヘッダーは、バックエンドが付ける
	handle /api/* {
		reverse_proxy 127.0.0.1:8080
	}
	handle /uploads/* {
		reverse_proxy 127.0.0.1:8080
	}
	handle /ws* {
		reverse_proxy 127.0.0.1:8080
	}

	# それ以外は画面(ビルドした静的ファイル)
	handle {
		header {
			X-Content-Type-Options nosniff
			X-Frame-Options DENY
			Referrer-Policy no-referrer
			Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self' ws: wss:; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'"
		}
		root * /opt/raisechat/web
		try_files {path} /index.html
		file_server
	}
}
CADDYEOF

cat > /etc/systemd/system/caddy.service <<'UNITEOF'
[Unit]
Description=Caddy
After=network-online.target
Wants=network-online.target

[Service]
User=caddy
ExecStart=/usr/local/bin/caddy run --config /etc/caddy/Caddyfile
ExecReload=/usr/local/bin/caddy reload --config /etc/caddy/Caddyfile
AmbientCapabilities=CAP_NET_BIND_SERVICE
Restart=on-failure
Environment=XDG_DATA_HOME=/var/lib/caddy

[Install]
WantedBy=multi-user.target
UNITEOF

# --- 更新スクリプト: S3の成果物を取り込み、再起動する(SSMから呼ぶ) -------------
cat > /usr/local/bin/raisechat-update <<UPDEOF
#!/bin/bash
set -euo pipefail
TMP="\$(mktemp -d)"
trap 'rm -rf "\$TMP"' EXIT
aws s3 cp "s3://$BUCKET/releases/backend.jar" "\$TMP/backend.jar" --region "$REGION"
aws s3 cp "s3://$BUCKET/releases/frontend.tar.gz" "\$TMP/frontend.tar.gz" --region "$REGION"
mkdir -p "\$TMP/web"
tar xzf "\$TMP/frontend.tar.gz" -C "\$TMP/web"
install -m 644 "\$TMP/backend.jar" /opt/raisechat/backend.jar
rm -rf /opt/raisechat/web.new
cp -r "\$TMP/web" /opt/raisechat/web.new
rm -rf /opt/raisechat/web.old
[ -d /opt/raisechat/web ] && mv /opt/raisechat/web /opt/raisechat/web.old
mv /opt/raisechat/web.new /opt/raisechat/web
rm -rf /opt/raisechat/web.old
chmod -R a+rX /opt/raisechat/web
systemctl restart raisechat
echo "updated"
UPDEOF
chmod 755 /usr/local/bin/raisechat-update

systemctl daemon-reload
systemctl enable --now caddy
systemctl enable raisechat

# 成果物がすでにあれば取り込む(初回は、まだ無いので失敗してよい。deploy/deploy.sh の実行後に反映される)
/usr/local/bin/raisechat-update || echo "no release yet (run deploy/deploy.sh)"
