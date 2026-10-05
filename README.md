# RaiseChat

Slack風チャットアプリケーション(スクール上級編課題)。

## 技術スタック

| 層 | 内容 |
|---|---|
| バックエンド | Java 21 / Spring Boot 3.5 / JdbcTemplate / Flyway / SQLite / JWT(jjwt) |
| フロントエンド | React 19 + TypeScript / Vite / react-markdown(+GFM) |
| ファイル保存 | 既定はローカルディスク。設定でAWS S3にも保存可能(下記「添付ファイルの保存先」) |
| リアルタイム更新 | WebSocket (STOMP)。更新の合図のみ配信し、データはRESTで再取得。切断時は自動再接続 |

## 起動方法

ポートは固定です(バックエンド **8080** / フロントエンド **5173**)。競合時は別ポートに逃がさず、使用中プロセスを停止してください。

前提ツール、ポート競合時の対処、データの初期化、トラブルシューティングなどの詳細は[ローカル開発環境セットアップ](docs/ローカル開発環境セットアップ.md)を参照。

```bash
# バックエンド
cd backend && mvn spring-boot:run

# フロントエンド(別ターミナル)
cd frontend && npm install && npm run dev
```

http://localhost:5173 を開き、新規登録(ユーザーID+パスワードのみ)から始めます。
テストは `cd backend && mvn test`。

### CI

GitHub Actions(`.github/workflows/ci.yml`)が、PRと `main` へのpushのたびに、次を自動実行する。

| ジョブ | 内容 |
|---|---|
| Backend | `cd backend && mvn test`(Java 21) |
| Frontend | `cd frontend && npm ci && npm run lint && npm run build`(Node 24) |

手元で同じ確認をするには、上のコマンドをそのまま実行する。リントの警告(warning)では失敗しない。

## 実装済み機能

- **認証・ユーザー**: 新規登録 / ログイン / ログアウト / プロフィール(アバター画像・表示名・ステータス)
- **メッセージング**: チャンネルチャット / DM / 編集・削除 / スレッド返信 / マークダウン / 画像・動画添付 / 絵文字リアクション / `@ユーザーID` メンション(入力補完つき) / メッセージ検索
- **ワークスペース・チャンネル**: ワークスペース作成 / パブリック・プライベートチャンネル作成 / 参加・招待
- **オーナー権限**: ユーザー招待 / キック / チャンネル削除(`#general` とDMは削除不可)
- **通知**: 未読バッジ / メンションバッジ / メンション到着トースト / タブタイトルの未読件数
- **プレゼンス**: オンライン表示(緑の点: メンバー一覧 / DM一覧 / 投稿者アイコン) / 入力中インジケーター(「〇〇が入力中...」)

## 仕様メモ

- ユーザーIDは半角英数字とアンダースコア3〜20文字。`@ユーザーID` でメンションする
- パブリックチャンネルはワークスペースの誰でも「参加」で読み書き可能。プライベートはチャンネルメンバーからの招待が必要
- ワークスペースに招待されたユーザーは自動で `#general` に参加する
- 添付ファイルは既定で `backend/uploads/` に保存(`/uploads/**` で配信)。DBは `backend/raisechat.db`(いずれもgit管理外)。S3に切り替える方法は下記
- 対象外: ボイスチャンネル
- プレゼンスはWebSocket(STOMP)の接続状態から判定する。複数タブは最後の接続が切れるまでオンラインで、切断から3秒の猶予(`app.presence.offline-grace-ms`)を置いてオフラインにする(ページ再読み込みで点滅しないため)。状態はサーバーのメモリ上にあり、DBには保存しない。サーバーを複数台にする場合は別途Redis Pub/Subなどが必要
- 入力中の通知はクライアントが `/app/typing` へ送り、チャンネルメンバーにだけ `/topic/channels/{id}/typing` で転送する(最後の通知から3.5秒で表示を消す。投稿すると即座に消える)。送信者IDは認証済みユーザーから付与し、保存はしない

## 添付ファイルの保存先(S3)

既定ではサーバーのローカルディスクに保存する。`app.storage.type=s3` にするとAWS S3に保存する(環境変数なら `APP_STORAGE_TYPE=s3`)。DBに保存するURLは保存先によらず `/uploads/{ファイル名}` で、フロントエンドは変更不要。

```bash
export APP_STORAGE_TYPE=s3
export APP_STORAGE_S3_BUCKET=your-bucket
export APP_STORAGE_S3_REGION=ap-northeast-1
# 任意: APP_STORAGE_S3_PREFIX=uploads/ (既定)、MinIOなどS3互換サーバーは APP_STORAGE_S3_ENDPOINT と APP_STORAGE_S3_PATH_STYLE=true
cd backend && mvn spring-boot:run
```

- **認証情報はリポジトリや設定ファイルに書かない。** AWS標準の取得方法(環境変数 `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`、`~/.aws`、EC2などのIAMロール)を使う
- **バケットは非公開(パブリックアクセスをすべてブロック)にする。** S3モードでは `GET /uploads/{ファイル名}` が、有効10分の署名付きURLへのリダイレクトになる。画像・動画タグはログイン情報を送れないため、ローカル配信と同じく、URLを知っていること(UUIDで推測不能)が閲覧の条件になる
- 最小限のIAMポリシー例(`your-bucket` と `uploads/` は設定に合わせる):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:GetObject"],
      "Resource": "arn:aws:s3:::your-bucket/uploads/*"
    }
  ]
}
```

- 既存のローカルファイルをS3へ移すには、キーがファイル名と同じなので `aws s3 sync backend/uploads s3://your-bucket/uploads/` でよい
- 動作確認はS3の動作を真似たローカルサーバーで行った。実際のS3(リージョン・IAM・バケット設定)での確認は、利用者側の環境で行うこと
