#!/usr/bin/env bash
# 架空のデモデータ(ユーザー・チャンネル・メッセージ)をAPI経由で投入する。ポートフォリオ用のスクリーンショット撮影向け。
# 使い方: DEMO_PASSWORD='8〜64文字のパスワード' ./scripts/seed-demo.sh [ベースURL]
#   ベースURLを省くと、terraform の app_url を使う。パスワードは、このスクリプトにもコードにも書かない(環境変数で渡す)。
#   全ユーザーが同じパスワードになる。撮影が終わったら、サーバーごと作り直すか、使い捨てのものとして扱う。
set -euo pipefail
cd "$(dirname "$0")/.."

: "${DEMO_PASSWORD:?環境変数 DEMO_PASSWORD を設定してください(8〜64文字)}"
BASE="${1:-$(terraform -chdir=terraform output -raw app_url)}"

req() { # req METHOD PATH [JSON] [TOKEN]
  local args=(-sS -X "$1" "$BASE$2" -H 'Content-Type: application/json')
  [ -n "${4:-}" ] && args+=(-H "Authorization: Bearer $4")
  [ -n "${3:-}" ] && args+=(-d "$3")
  curl "${args[@]}"
}

register() { # register username displayName -> token(登録済みならログイン)
  local body res
  body=$(jq -cn --arg u "$1" --arg p "$DEMO_PASSWORD" --arg d "$2" '{username:$u,password:$p,displayName:$d}')
  res=$(req POST /api/auth/register "$body")
  if [ "$(echo "$res" | jq -r '.token // empty')" = "" ]; then
    res=$(req POST /api/auth/login "$(jq -cn --arg u "$1" --arg p "$DEMO_PASSWORD" '{username:$u,password:$p}')")
  fi
  local tok
  tok=$(echo "$res" | jq -r '.token // empty')
  [ -n "$tok" ] || { echo "ログイン/登録に失敗: $1 ($(echo "$res" | jq -r '.message // .error // .'))。既に別のパスワードで登録済みの可能性があります" >&2; exit 1; }
  echo "$tok"
}
post() { # post TOKEN CHANNEL_ID TEXT [PARENT_ID] -> message id
  req POST "/api/channels/$2/messages" "$(jq -cn --arg c "$3" --argjson p "${4:-null}" '{content:$c,parentId:$p}')" "$1" | jq -r '.id'
}
react() { req POST "/api/messages/$2/reactions" "$(jq -cn --arg e "$3" '{emoji:$e}')" "$1" >/dev/null; }

echo "==> ユーザーを登録 ($BASE)"
T_OWNER=$(register raise_owner "さくら")
T_MIKI=$(register miki_dev "ミキ")
T_TARO=$(register taro_design "タロウ")
T_YUI=$(register yui_qa "ユイ")

echo "==> ワークスペースとチャンネル"
WS=$(req POST /api/workspaces '{"name":"Raise Studio"}' "$T_OWNER" | jq -r '.id')
for u in miki_dev taro_design yui_qa; do
  req POST "/api/workspaces/$WS/invite" "$(jq -cn --arg u "$u" '{username:$u}')" "$T_OWNER" >/dev/null || true
done
GENERAL=$(req GET "/api/workspaces/$WS" "" "$T_OWNER" | jq -r '[.channels[]|select(.name=="general")][0].id')
DEV=$(req POST "/api/workspaces/$WS/channels" '{"name":"dev","isPrivate":false}' "$T_OWNER" | jq -r '.id')
DESIGN=$(req POST "/api/workspaces/$WS/channels" '{"name":"design","isPrivate":false}' "$T_OWNER" | jq -r '.id')
for t in "$T_MIKI" "$T_TARO" "$T_YUI"; do
  req POST "/api/channels/$DEV/join" "" "$t" >/dev/null || true
  req POST "/api/channels/$DESIGN/join" "" "$t" >/dev/null || true
done

echo "==> メッセージ"
M1=$(post "$T_OWNER" "$GENERAL" "みなさん、**Raise Studio** へようこそ! 今週のリリース予定をここで共有します。")
post "$T_MIKI" "$GENERAL" "よろしくお願いします! 新しい検索機能は @yui_qa さんにテストをお願いしたいです。" >/dev/null
M3=$(post "$T_TARO" "$GENERAL" "ログイン画面のデザイン案ができました。#design に置いておきます。")
react "$T_MIKI" "$M1" "🎉"; react "$T_TARO" "$M1" "🎉"; react "$T_YUI" "$M1" "👍"
react "$T_OWNER" "$M3" "👀"; react "$T_MIKI" "$M3" "🔥"
post "$T_YUI" "$GENERAL" "@raise_owner 了解です! 検索のテストは明日の午前中に終わらせます。" >/dev/null

D1=$(post "$T_MIKI" "$DEV" "デプロイ手順書を更新しました。\`./deploy/deploy.sh\` 1本で、ビルドから反映まで終わります。")
post "$T_OWNER" "$DEV" "ありがとう! スレッドで確認ポイントをまとめるね。" "$D1" >/dev/null
post "$T_YUI" "$DEV" "疎通確認は \`401\` が返れば OK、で合っていますか?" "$D1" >/dev/null
post "$T_MIKI" "$DEV" "はい、未ログインで 401 が返れば、アプリが応答している状態です。" "$D1" >/dev/null
react "$T_OWNER" "$D1" "✅"
post "$T_OWNER" "$DEV" "WebSocket の再接続も動作確認済みです。" >/dev/null

D2=$(post "$T_TARO" "$DESIGN" "カラーパレットの案です。ダークテーマを基調にして、アクセントは緑にしました。")
react "$T_OWNER" "$D2" "❤️"; react "$T_MIKI" "$D2" "🔥"
post "$T_OWNER" "$DESIGN" "いい感じ! モバイル幅での見え方も確認しましょう。" "$D2" >/dev/null

echo "==> DM"
DM=$(req POST "/api/workspaces/$WS/dms" "$(jq -cn --argjson u "$(req GET /api/me '' "$T_OWNER" | jq .id)" '{userId:$u}')" "$T_MIKI" | jq -r '.id')
post "$T_MIKI" "$DM" "お疲れさまです。リリース前に、少しだけ相談してもいいですか?" >/dev/null

echo "完了。ログイン: raise_owner / (DEMO_PASSWORD)  URL: $BASE"
