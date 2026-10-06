#!/usr/bin/env bash
# ビルド → S3へアップロード → SSMでインスタンスを更新・再起動する。
# 前提: terraform apply 済み。AWS CLI が使える状態(認証は、あなたの環境のものを使う。このスクリプトには書かない)。
# 使い方: ./deploy/deploy.sh            (テストも実行する)
#         SKIP_TESTS=1 ./deploy/deploy.sh (テストを省く)
set -euo pipefail
cd "$(dirname "$0")/.."

tf() { terraform -chdir=terraform output -raw "$1"; }
BUCKET="$(tf artifacts_bucket)"
INSTANCE="$(tf instance_id)"
REGION="$(tf region)"
URL="$(tf app_url)"

echo "==> バックエンドをビルド"
if [ "${SKIP_TESTS:-0}" = "1" ]; then
  (cd backend && mvn -B -ntp -DskipTests clean package)
else
  (cd backend && mvn -B -ntp clean package)
fi
JAR="$(ls backend/target/*.jar | grep -v '\.original$' | head -1)"

echo "==> 画面をビルド"
(cd frontend && npm ci && npm run build)

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
tar czf "$WORK/frontend.tar.gz" -C frontend/dist .

echo "==> S3へアップロード (s3://$BUCKET/releases/)"
aws s3 cp "$JAR" "s3://$BUCKET/releases/backend.jar" --region "$REGION"
aws s3 cp "$WORK/frontend.tar.gz" "s3://$BUCKET/releases/frontend.tar.gz" --region "$REGION"

echo "==> インスタンスを更新して再起動 ($INSTANCE)"
CMD_ID="$(aws ssm send-command --region "$REGION" --instance-ids "$INSTANCE" \
  --document-name AWS-RunShellScript --parameters 'commands=["/usr/local/bin/raisechat-update"]' \
  --query Command.CommandId --output text)"
aws ssm wait command-executed --region "$REGION" --command-id "$CMD_ID" --instance-id "$INSTANCE"
aws ssm get-command-invocation --region "$REGION" --command-id "$CMD_ID" --instance-id "$INSTANCE" \
  --query '[Status,StandardOutputContent,StandardErrorContent]' --output text

echo "==> 疎通確認: $URL/api/me (未ログインなので 401 なら正常)"
for i in $(seq 30); do
  code="$(curl -s -o /dev/null -w '%{http_code}' "$URL/api/me" || true)"
  [ "$code" = "401" ] && { echo "OK ($code)"; exit 0; }
  sleep 5
done
echo "疎通できませんでした(最後のコード: $code)。証明書の取得に時間がかかる場合があります。ログ: sudo journalctl -u raisechat -u caddy" >&2
exit 1
