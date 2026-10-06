# RaiseChat

Slack風チャットアプリケーション(スクール上級編課題)。

## 技術スタック

| 層 | 内容 |
|---|---|
| バックエンド | Java 21 / Spring Boot 3.5 / JdbcTemplate / Flyway / SQLite(既定)または PostgreSQL / JWT(jjwt) |
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
| Backend (PostgreSQL) | 同じテストを、PostgreSQLのサービスコンテナで実行する(下記「PostgreSQLで動かす」の環境変数 `TEST_DB_*`) |
| Docker | `docker compose` で、イメージのビルドと、全サービスの起動、画面とAPIへの到達、バックエンド2台のRedis購読を確認する |
| Frontend | `cd frontend && npm ci && npm run lint && npm test && npm run build`(Node 24) |

手元で同じ確認をするには、上のコマンドをそのまま実行する。リントは `--deny-warnings` のため、**警告(warning)があっても失敗する**。やむを得ず抑制する場合は、`oxlint-disable-next-line` に理由のコメントを添える。

## 実装済み機能

- **認証・ユーザー**: 新規登録 / ログイン / ログアウト / プロフィール(アバター画像・表示名・ステータス)
- **メッセージング**: チャンネルチャット / DM / 編集・削除 / スレッド返信 / マークダウン / 画像・動画添付 / 絵文字リアクション / `@ユーザーID` メンション(入力補完つき) / メッセージ検索
- **ワークスペース・チャンネル**: ワークスペース作成 / パブリック・プライベートチャンネル作成 / 参加・招待
- **オーナー権限**: ユーザー招待 / キック / チャンネル削除(`#general` とDMは削除不可)
- **通知**: 未読バッジ / メンションバッジ / メンション到着トースト / タブタイトルの未読件数

## 仕様メモ

- ユーザーIDは半角英数字とアンダースコア3〜20文字。`@ユーザーID` でメンションする
- パブリックチャンネルはワークスペースの誰でも「参加」で読み書き可能。プライベートはチャンネルメンバーからの招待が必要
- ワークスペースに招待されたユーザーは自動で `#general` に参加する
- 添付ファイルは既定で `backend/uploads/` に保存(`/uploads/**` で配信)。DBは `backend/raisechat.db`(いずれもgit管理外)。S3に切り替える方法は下記
- 対象外: ボイスチャンネル

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

**ログイン**

- 存在しないユーザーIDでもパスワードの照合を行い、応答時間の差からIDの有無が分からないようにしている

## 本番のデプロイ(HTTPS)

独自ドメインで公開するときは、`docker-compose.prod.yml` を重ねて、Caddy で自動HTTPSにする。手順は [docs/本番デプロイ手順.md](docs/本番デプロイ手順.md)。

## リクエストの制限

- **JSON本文の上限:** 1MB(`app.max-json-body-bytes`)。超えると413。ファイルのアップロードは別に50MBまで
- **WebSocketの接続元:** 既定では、画面と同じオリジンからの接続だけ許可する。別のオリジンから使うときは `app.websocket.allowed-origins`(カンマ区切り、例 `https://chat.example.com`)に指定する。nginxは、Host(ポート付き)とX-Forwarded-Portを渡して、この判定が合うようにしている

## 複数サーバーで動かす(Redis)

既定では、通知が、**サーバー1台のメモリ上**にある。バックエンドを複数台にすると、別のサーバーに繋がっている人に通知が届かず、ログイン失敗の回数も台ごとになる。`app.cluster.mode=redis` にすると、Redisで共有する。

| 共有するもの | 仕組み |
|---|---|
| 通知(メッセージ・サイドバー更新など) | 各サーバーがRedisのチャンネル(`{prefix}:events`)に発行し、全サーバーが受信して、自分に繋がっている人へ届ける |

```bash
# Redisを用意する(開発用)
docker run -d --name raisechat-redis -p 6379:6379 redis:7-alpine

# 全サーバー共通の設定
export APP_CLUSTER_MODE=redis
export SPRING_DATA_REDIS_HOST=localhost      # 既定のポートは6379(SPRING_DATA_REDIS_PORT)
export JWT_SECRET='全サーバーで同じ値(32バイト以上)'   # 異なると、別のサーバーで発行したログインが無効になる。開発用の既定値のままだと起動しない

# 1台目(固定ポート8080)
cd backend && mvn spring-boot:run
# 2台目(複数台を試すときだけ、別のポートで)
cd backend && mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8081
```

設定: `app.cluster.mode`(`memory` 既定 / `redis`)、`app.cluster.key-prefix`(同じRedisを別の環境と共有するときの区別。既定 `raisechat`)。

**制約と注意点**

- **データベース:** SQLiteは、同じマシン上の複数プロセスなら、同じファイルを共有できる(WAL)。**別のマシンに分けるには、PostgreSQLを使う**(下記「PostgreSQLで動かす」)
- **添付ファイル:** ローカルディスク保存では、別のサーバーのファイルが見えない。複数台では、S3(上記)か、共有ストレージを使う
- **Redisに繋がらないとき:** 起動時に繋がらなければ、**起動に失敗する**(他のサーバーと通知をやり取りできない状態で動かないため)。稼働中に繋がらなくなった場合は、**APIは止めずに続ける**(通知は届かない / ログインの制限はかからない。警告をログに出す)。繋がり直せば、通知は再開する。取りこぼした分は、クライアントの再接続時の再取得で補われる
- **ログインの制限:** 確認と記録が別々の操作なので、同時に大量に送られた場合は、上限を数件超えて通ることがある
- **テスト:** 複数サーバーのテスト(`ClusterRedisTest`)は、Redisに繋がる環境(既定 `localhost:6379`。環境変数 `REDIS_HOST` / `REDIS_PORT`)でだけ実行され、なければ飛ばされる。CIでは、Redisのサービスコンテナで実行する

## PostgreSQLで動かす

既定はSQLite(設定なしで動く)。バックエンドを**別のマシンに分ける**ときなどは、プロファイル `postgres` でPostgreSQLを使う。SQL(クエリ)は、SQLiteとPostgreSQLの**両方で同じ**ものが動く書き方にしてあり、テーブルの定義(Flyway)だけがDBごとに分かれている(`backend/src/main/resources/db/migration/sqlite` と `postgresql`)。

```bash
# PostgreSQLを用意する(開発用)
docker run -d --name raisechat-pg -e POSTGRES_USER=raisechat -e POSTGRES_PASSWORD=パスワード -e POSTGRES_DB=raisechat -p 5432:5432 postgres:16-alpine

# バックエンドを、PostgreSQLで起動する(テーブルは、起動時にFlywayが作る)
export SPRING_PROFILES_ACTIVE=postgres
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/raisechat
export SPRING_DATASOURCE_USERNAME=raisechat
export SPRING_DATASOURCE_PASSWORD=パスワード
cd backend && mvn spring-boot:run
```

- 接続プールは、PostgreSQLでは10本(SQLiteは、書き込みが直列なので1本)
- 複数サーバー(Redis)と組み合わせる場合は、全サーバーに同じ接続先を設定する
- **テストをPostgreSQLで実行する:** 環境変数 `TEST_DB_URL`(例 `jdbc:postgresql://localhost:5432/raisechat_test`)、`TEST_DB_DRIVER=org.postgresql.Driver`、`TEST_DB_POOL=10`、`TEST_DB_USER`、`TEST_DB_PASSWORD` を指定して、`mvn test` を実行する。全テストが1つのDBを共有するため、ユーザー名などは、テストごとに別の値にしてある。CIは、これを自動で実行する

**既存のSQLiteのデータを移す**

1. 上の手順で、**空のPostgreSQLに、バックエンドを一度起動**して、テーブルを作る(起動したら止めてよい)
2. バックエンドを止めて、移行スクリプトを実行する(`sqlite3` と `psql` が必要)

```bash
PSQL="psql -h localhost -U raisechat -d raisechat" backend/scripts/migrate-sqlite-to-postgres.sh backend/raisechat.db
```

- データだけを移す。**全体を1つのトランザクション**で行い、途中で失敗したら、PostgreSQLには何も残らない。移行先が空でないときは、何も変更せずに止まる
- IDはそのまま引き継ぎ、連番の続きは、最大のIDの次から始まる。最後に、テーブルごとの件数を、SQLiteとPostgreSQLで見比べて表示する
- 添付ファイル(`uploads/`)は移さない(同じ場所を使うか、S3へ移す)
- このスクリプトは、引用符・改行・絵文字・空文字・NULLを含むデータで、移行前後の応答が一致することを確認してある

## Docker Composeで一式を動かす

PostgreSQL・Redis・バックエンド(既定2台)・フロントエンド(nginx)を、1コマンドで起動できる。JavaやNodeが入っていなくても動く(ビルドもDockerの中で行う)。

```bash
cp .env.example .env     # JWT_SECRET と POSTGRES_PASSWORD を設定する(未設定だと、起動時にエラーになる)
docker compose up --build
```

画面は http://localhost:5173 。

| サービス | 内容 |
|---|---|
| `frontend` | nginx。画面を配信し、`/api`・`/uploads`・`/ws`(WebSocket)をバックエンドへ中継する。**公開するのは、このポート(5173)だけ** |
| `backend` | 既定2台。`BACKEND_REPLICAS` で台数を変えられる。通知は、Redisで共有する |
| `postgres` / `redis` | データは、`pgdata` ボリュームに保存される |

- **秘密情報:** `JWT_SECRET`(例 `openssl rand -base64 48`)と `POSTGRES_PASSWORD` は、`.env` で必ず指定する(既定値は置いていない)。`.env` は、gitに入らない。postgres プロファイルや `app.cluster.mode=redis` では、開発用の既定値や32バイト未満の `JWT_SECRET` だと、バックエンドが起動時にエラーで止まる
- **ポート:** 5173が使用中(ローカルの開発用サーバーなど)だと、起動に失敗する。別のポートに逃がさず、先に止める
- **データ:** `docker compose down` では、データ(PostgreSQL・添付ファイル)は残る。**`docker compose down -v` は、データも削除する**
- **接続元のIP:** nginxが `X-Forwarded-For` を付け、バックエンドは `SERVER_FORWARD_HEADERS_STRATEGY=native` で、それを使う
- **添付ファイル:** バックエンドの複数台で、同じボリュームを共有する(同じマシンの中だけ)。複数ホストに分けるときは、S3を使う
- ログ: `docker compose logs -f backend` / 状態: `docker compose ps`
- 環境の作り方・構成の詳細は、上の「複数サーバーで動かす」「PostgreSQLで動かす」を参照
