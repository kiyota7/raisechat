#!/usr/bin/env bash
# サーバー(EC2)の一時停止・再開・状態確認。使わない間の、EC2の稼働料金を止められる。
# データ(DB・添付ファイル)はデータ用EBSに残り、公開IP(Elastic IP)も変わらないので、再開すればそのまま使える。
# 停止中も、EBS・Elastic IP・Route 53 のホストゾーン・ドメインの料金は続く。
#
# 使い方: ./deploy/power.sh status     今の状態を表示する
#         ./deploy/power.sh stop       停止する(アプリは開けなくなる。確認あり)
#         ./deploy/power.sh start      再開して、アプリが応答するまで待つ
# 環境変数: YES=1     停止の確認を省く
#           DRY_RUN=1 実行する内容だけ表示する(AWSの状態を変えない)
set -euo pipefail
cd "$(dirname "$0")/.."

CMD="${1:-}"
case "$CMD" in
  status | stop | start) ;;
  *)
    echo "使い方: $0 {status|stop|start}" >&2
    exit 1
    ;;
esac

tf() { terraform -chdir=terraform output -raw "$1"; }
INSTANCE="$(tf instance_id)"
REGION="$(tf region)"
URL="$(tf app_url)"

state() {
  aws ec2 describe-instances --region "$REGION" --instance-ids "$INSTANCE" \
    --query 'Reservations[0].Instances[0].State.Name' --output text
}

# 変更を伴うコマンド。DRY_RUN=1 のときは、表示するだけ
run() {
  if [ "${DRY_RUN:-0}" = "1" ]; then
    echo "[DRY_RUN] $*"
  else
    "$@"
  fi
}

http_code() { curl -s -m 10 -o /dev/null -w '%{http_code}' "$URL/api/me" || true; }

STATE="$(state)"
echo "インスタンス: $INSTANCE ($REGION)  状態: $STATE"

case "$CMD" in
  status)
    if [ "$STATE" = "running" ]; then
      echo "アプリ: $URL  応答: $(http_code) (401 なら正常)"
    fi
    ;;

  stop)
    case "$STATE" in
      stopped)
        echo "すでに停止しています。"
        exit 0
        ;;
      terminated | shutting-down)
        echo "インスタンスは終了しています(停止の対象外)。" >&2
        exit 1
        ;;
    esac
    if [ "${YES:-0}" != "1" ] && [ "${DRY_RUN:-0}" != "1" ]; then
      if [ ! -t 0 ]; then
        echo "確認できません。YES=1 を付けて実行してください。" >&2
        exit 1
      fi
      echo "停止すると、$URL は開けなくなります(データは残ります)。"
      read -r -p "停止しますか? [y/N] " ans
      [ "$ans" = "y" ] || [ "$ans" = "Y" ] || { echo "中止しました。"; exit 0; }
    fi
    # 起動中(pending)の場合は、起動が終わるのを待ってから止める
    [ "$STATE" = "pending" ] && run aws ec2 wait instance-running --region "$REGION" --instance-ids "$INSTANCE"
    run aws ec2 stop-instances --region "$REGION" --instance-ids "$INSTANCE" --output text --query 'StoppingInstances[0].CurrentState.Name'
    run aws ec2 wait instance-stopped --region "$REGION" --instance-ids "$INSTANCE"
    echo "停止しました。再開するには: $0 start"
    echo "(停止中も、EBS・Elastic IP・ホストゾーン・ドメインの料金は続きます)"
    ;;

  start)
    case "$STATE" in
      terminated | shutting-down)
        echo "インスタンスは終了しています。terraform apply で作り直してください。" >&2
        exit 1
        ;;
      stopping)
        run aws ec2 wait instance-stopped --region "$REGION" --instance-ids "$INSTANCE"
        ;;
    esac
    if [ "$STATE" != "running" ] && [ "$STATE" != "pending" ]; then
      run aws ec2 start-instances --region "$REGION" --instance-ids "$INSTANCE" --output text --query 'StartingInstances[0].CurrentState.Name'
    fi
    run aws ec2 wait instance-running --region "$REGION" --instance-ids "$INSTANCE"
    if [ "${DRY_RUN:-0}" = "1" ]; then
      echo "[DRY_RUN] $URL/api/me が 401 になるまで待つ"
      exit 0
    fi
    echo "起動を確認しました。アプリが応答するまで待ちます(最大3分。長く止めていた場合は、証明書の取り直しで少しかかります)..."
    for i in $(seq 36); do
      code="$(http_code)"
      [ "$code" = "401" ] && { echo "OK ($code): $URL"; exit 0; }
      sleep 5
    done
    echo "まだ応答しません(最後のコード: $code)。少し待ってから、$0 status を実行してください。" >&2
    exit 1
    ;;
esac
