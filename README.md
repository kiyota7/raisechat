# RaiseChat

Slack風チャットアプリケーション(スクール上級編課題)。

## 技術スタック

| 層 | 内容 |
|---|---|
| バックエンド | Java 21 / Spring Boot 3.5 / JdbcTemplate / Flyway / SQLite / JWT(jjwt) |
| フロントエンド | React 19 + TypeScript / Vite / react-markdown(+GFM) |
| ファイル保存 | 既定はローカルディスク。設定でAWS S3にも保存可能(下記「添付ファイルの保存先」) |
| リアルタイム更新 | WebSocket (STOMP)。更新の合図のみ配信し、データはRESTで再取得。切断時は自動再接続。複数サーバーは、Redis Pub/Subで対応(下記「複数サーバーで動かす」) |

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
テストは `cd backend && mvn test`(バックエンド)、`cd frontend && npm test`(フロントエンド)。

### CI

GitHub Actions(`.github/workflows/ci.yml`)が、PRと `main` へのpushのたびに、次を自動実行する。

| ジョブ | 内容 |
|---|---|
| Backend | `cd backend && mvn test`(Java 21。複数サーバーのテストのため、Redisのサービスコンテナを使う) |
| Frontend | `cd frontend && npm ci && npm run lint && npm test && npm run build`(Node 24) |

手元で同じ確認をするには、上のコマンドをそのまま実行する。リントは `--deny-warnings` のため、**警告(warning)があっても失敗する**。やむを得ず抑制する場合は、`oxlint-disable-next-line` に理由のコメントを添える。

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

## 認証のセキュリティ

**パスワード(登録時)**

- 8文字以上64文字以内で、**72バイト以内**(BCryptの上限。日本語は1文字が3バイトなので、24文字程度まで)
- ユーザーIDを含むもの、よくある弱いパスワード(`12345678`、`password1` など)、同じ文字の繰り返し、連続した並び(`abcdefgh` など)は使えない

**ログイン試行の制限**

- 失敗が続くと、一時的に拒否する(HTTP 429 + `Retry-After`)。**ユーザーID+IPごとに5回**、**IPごとに20回**(別のユーザーIDを順に試す攻撃への対策)、いずれも15分間
- 制限中は、**正しいパスワードでも拒否する**。成功したら、そのユーザーID+IPの失敗回数をリセットする
- 存在しないユーザーIDも同じように数え、パスワードの照合にかかる時間も揃えて、IDの有無が分からないようにしている
- 設定: `app.login.max-failures`(5)、`app.login.ip-max-failures`(20)、`app.login.window-minutes`(15)。環境変数なら `APP_LOGIN_MAX_FAILURES` など
- **失敗回数はサーバーのメモリ上**にある。再起動で消え、サーバーを複数台にする場合は、台ごとに数える(共有するにはRedisなどが必要)
- **リバースプロキシ配下で使うとき:** 既定では接続元のIPをそのまま使うので、全員がプロキシのIPとして数えられ、IPごとの制限に巻き込まれる。プロキシが `X-Forwarded-For` を付ける構成なら、`server.forward-headers-strategy=native` を設定する(プロキシ以外から直接届かないことが前提。そうでないと、ヘッダーを偽って制限を回避される)

## 複数サーバーで動かす(Redis)

既定では、通知・オンライン状態・ログインの失敗回数が、**サーバー1台のメモリ上**にある。バックエンドを複数台にすると、別のサーバーに繋がっている人に通知が届かず、オンライン表示も見えない。`app.cluster.mode=redis` にすると、Redisで共有する。

| 共有するもの | 仕組み |
|---|---|
| 通知(メッセージ・入力中・サイドバー更新など) | 各サーバーがRedisのチャンネル(`{prefix}:events`)に発行し、全サーバーが受信して、自分に繋がっている人へ届ける |
| オンライン表示 | 全サーバーの接続を、有効期限(30秒)つきでRedisに記録する。各サーバーが10秒ごとに延ばし、サーバーが落ちても期限で消える。オンライン/オフラインの切り替えの通知は、複数のサーバーが同時に気づいても、1回だけ |
| ログインの失敗回数 | Redisに記録し、全サーバーの失敗をまとめて数える |

```bash
# Redisを用意する(開発用)
docker run -d --name raisechat-redis -p 6379:6379 redis:7-alpine

# 全サーバー共通の設定
export APP_CLUSTER_MODE=redis
export SPRING_DATA_REDIS_HOST=localhost      # 既定のポートは6379(SPRING_DATA_REDIS_PORT)
export JWT_SECRET='全サーバーで同じ値'          # 異なると、別のサーバーで発行したログインが無効になる

# 1台目(固定ポート8080)
cd backend && mvn spring-boot:run
# 2台目(複数台を試すときだけ、別のポートで)
cd backend && mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8081
```

設定: `app.cluster.mode`(`memory` 既定 / `redis`)、`app.cluster.key-prefix`(同じRedisを別の環境と共有するときの区別。既定 `raisechat`)、`app.cluster.presence-ttl-ms`(30000)、`app.cluster.presence-heartbeat-ms`(10000)。

**制約と注意点**

- **データベース:** SQLiteは、同じマシン上の複数プロセスなら、同じファイルを共有できる(WAL)。**別のマシンに分けるには、ネットワーク越しのDB(PostgreSQLなど)への移行が別途必要**
- **添付ファイル:** ローカルディスク保存では、別のサーバーのファイルが見えない。複数台では、S3(上記)か、共有ストレージを使う
- **Redisに繋がらないとき:** 起動時に繋がらなければ、**起動に失敗する**(他のサーバーと通知をやり取りできない状態で動かないため)。稼働中に繋がらなくなった場合は、**APIは止めずに続ける**(通知は届かない / 概要のオンライン状態は全員オフライン / ログインの制限はかからない。警告をログに出す)。繋がり直せば、通知は再開する。取りこぼした分は、クライアントの再接続時の再取得で補われる
- **ログインの制限:** 確認と記録が別々の操作なので、同時に大量に送られた場合は、上限を数件超えて通ることがある
- **テスト:** 複数サーバーのテスト(`ClusterRedisTest`)は、Redisに繋がる環境(既定 `localhost:6379`。環境変数 `REDIS_HOST` / `REDIS_PORT`)でだけ実行され、なければ飛ばされる。CIでは、Redisのサービスコンテナで実行する
