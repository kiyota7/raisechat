#!/usr/bin/env bash
# 稼働中のインスタンスの公開ドメインを、インスタンスを作り直さずに切り替える(sslip.io → 独自ドメインなど)。
# 前提: terraform apply 済み。AWS CLI が使える状態。ドメインのAレコードが、Elastic IP(terraform output public_ip)を向いていること。
# 使い方: ./deploy/set-domain.sh chat.example.com
#         DRY_RUN=1 ./deploy/set-domain.sh chat.example.com   (送る内容を表示するだけ)
#         SKIP_DNS_CHECK=1 ./deploy/set-domain.sh ...          (DNSの確認を省く)
set -euo pipefail
cd "$(dirname "$0")/.."

DOMAIN="${1:-}"
if [[ ! "$DOMAIN" =~ ^([a-z0-9]([a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,}$ ]]; then
  echo "使い方: $0 <ドメイン名(小文字。例 chat.example.com)>" >&2
  exit 1
fi

tf() { terraform -chdir=terraform output -raw "$1"; }
INSTANCE="$(tf instance_id)"
REGION="$(tf region)"
IP="$(tf public_ip)"

# --- DNSの確認: Aレコードが、このサーバーのIPを向いているか -------------------------
if [ "${SKIP_DNS_CHECK:-0}" != "1" ]; then
  RESOLVED="$(dig +short A "$DOMAIN" | tail -1 || true)"
  if [ "$RESOLVED" != "$IP" ]; then
    echo "DNSが、まだこのサーバーを向いていません。" >&2
    echo "  $DOMAIN の A レコード: ${RESOLVED:-(なし)}" >&2
    echo "  期待するIP(Elastic IP) : $IP" >&2
    echo "Aレコードを設定して、反映されてから(数分〜)もう一度実行してください。" >&2
    exit 1
  fi
fi

# --- インスタンスで実行する内容(ドメインは、上で形式を検証済み) --------------------
PARAMS="$(DOMAIN="$DOMAIN" python3 - <<'PY'
import json, os
d = os.environ["DOMAIN"]
cmds = [
    "set -e",
    # Caddyfile の1行目は、サイトのアドレス("<アドレス> {")
    "head -1 /etc/caddy/Caddyfile | grep -q ' {$'",
    f"sed -i '1s|.*|{d} {{|' /etc/caddy/Caddyfile",
    f"sed -i 's|^APP_WEBSOCKET_ALLOWED_ORIGINS=.*|APP_WEBSOCKET_ALLOWED_ORIGINS=https://{d}|' /etc/raisechat/env",
    "runuser -u caddy -- env XDG_DATA_HOME=/var/lib/caddy /usr/local/bin/caddy validate --config /etc/caddy/Caddyfile",
    "systemctl restart raisechat",
    "systemctl restart caddy",
    "head -1 /etc/caddy/Caddyfile; grep '^APP_WEBSOCKET_ALLOWED_ORIGINS' /etc/raisechat/env",
]
print(json.dumps({"commands": cmds}))
PY
)"

if [ "${DRY_RUN:-0}" = "1" ]; then
  echo "[DRY_RUN] instance=$INSTANCE region=$REGION"
  echo "$PARAMS" | python3 -c 'import json,sys; print("\n".join(json.load(sys.stdin)["commands"]))'
  exit 0
fi

echo "==> $DOMAIN に切り替え ($INSTANCE)"
CMD_ID="$(aws ssm send-command --region "$REGION" --instance-ids "$INSTANCE" \
  --document-name AWS-RunShellScript --parameters "$PARAMS" \
  --query Command.CommandId --output text)"
aws ssm wait command-executed --region "$REGION" --command-id "$CMD_ID" --instance-id "$INSTANCE"
aws ssm get-command-invocation --region "$REGION" --command-id "$CMD_ID" --instance-id "$INSTANCE" \
  --query '[Status,StandardOutputContent,StandardErrorContent]' --output text

echo "==> 疎通確認: https://$DOMAIN/api/me (未ログインなので 401 なら正常。証明書の取得に1〜2分かかることがあります)"
for i in $(seq 36); do
  code="$(curl -s -o /dev/null -w '%{http_code}' "https://$DOMAIN/api/me" || true)"
  [ "$code" = "401" ] && { echo "OK ($code)"; break; }
  sleep 5
done
[ "$code" = "401" ] || { echo "疎通できませんでした(最後のコード: $code)。ログ: sudo journalctl -u caddy" >&2; exit 1; }

echo
echo "次に、Terraform の記録も合わせてください(インスタンスは変更されません):"
echo "  terraform/terraform.tfvars に  domain = \"$DOMAIN\"  を書いて、cd terraform && terraform apply"
