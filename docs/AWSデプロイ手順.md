# AWSデプロイ手順(EC2 1台 + Terraform)

RaiseChat を AWS に公開する手順。構成は **EC2 1台**(オートスケールなし)。インフラは `terraform/` のコードで作り、アプリの更新は `deploy/deploy.sh` で行う。

## 1. 構成

```
利用者 ─HTTPS(443)→ [Elastic IP] ─→ EC2(Amazon Linux 2023)
                                      ├─ Caddy ........ 自動HTTPS、画面(静的ファイル)の配信、/api /uploads /ws をバックエンドへ中継
                                      └─ バックエンド ... Java 21 / Spring Boot(127.0.0.1:8080。外には公開しない)
                                      データ用EBS(暗号化)… DB(SQLite)・添付ファイル・JWT秘密鍵。日次スナップショット7世代
S3(非公開) ......... ビルドしたjarと画面(成果物)の置き場。アプリの添付ファイルの保存先ではない
```

| 項目 | 内容 |
|---|---|
| 公開ポート | 80 / 443 のみ。**SSHは開けない**(操作はSSM Session Manager) |
| データの置き場所 | データ用EBS。インスタンスを作り直しても残る |
| 秘密鍵(JWT) | データ用EBS上に、初回だけ生成。Terraformの state や git には載らない |
| 負荷が増えたら | **スケールアップ**(`instance_type` を上げる)。この構成は、オートスケール(台数を増やす方法)には対応していない(→ 9章) |

## 2. 事前準備

- AWS アカウントと、操作用の認証(IAMユーザーやSSOなど)。**認証情報は、このリポジトリやコードに書かない**。AWS CLI(`aws configure` / `aws sso login`)で、あなたの環境に設定する
- ローカルに: Terraform 1.5 以上、AWS CLI、[Session Manager プラグイン](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-working-with-install-plugin.html)(インスタンスに入るときだけ)、Java 21 + Maven、Node.js(デプロイ時のビルド用)

## 3. インフラを作る(初回)

```bash
cd terraform
cp terraform.tfvars.example terraform.tfvars   # 必要なら編集(リージョン、インスタンスタイプなど)
terraform init
terraform plan      # 作られるものを確認する
terraform apply     # 確認して yes。2〜3分
```

作成されるもの: EC2、Elastic IP、データ用EBS(+日次スナップショットの設定)、成果物用S3、セキュリティグループ、IAMロール(最小限)。
完了すると、`app_url`(例 `https://203-0-113-10.sslip.io`)などが表示される。ドメイン未設定の間は、IPから作る **sslip.io** の名前でHTTPSが動く(検証用。→ 5章)。

## 4. アプリをデプロイする

```bash
./deploy/deploy.sh                 # テストも実行してから、ビルド → S3 → 更新・再起動 → 疎通確認
SKIP_TESTS=1 ./deploy/deploy.sh    # テストを省く場合
```

初回は、HTTPS証明書の取得で1〜2分かかることがある。`OK (401)` と出れば成功(未ログインのAPIが401を返す=アプリが応答している)。`app_url` を開いて、新規登録から始める。

更新も同じコマンド。再起動中の数秒は、接続が切れる(画面は自動で再接続する)。

## 5. ドメインを設定する

sslip.io は、IPアドレスをそのまま名前にする無料の仕組みで、ドメイン未取得の間の検証に使う。Let's Encrypt の発行回数制限が、他の利用者と共有で影響することがあるので、**本番では、自分のドメインを使う**。

1. ドメインを取得し、DNS の **A レコード**を、`terraform output public_ip` のIPに向ける
2. `terraform.tfvars` に `domain = "chat.example.com"` を書く
3. 起動スクリプトは初回起動のときだけ実行されるため、インスタンスを作り直して反映する(**数分のダウンタイム。データ・ログイン情報は、データ用EBSに残る**):
   ```bash
   cd terraform && terraform apply -replace=aws_instance.app
   cd .. && ./deploy/deploy.sh
   ```

## 6. 運用

```bash
# インスタンスに入る(SSH不要)
aws ssm start-session --target $(terraform -chdir=terraform output -raw instance_id) --region ap-northeast-1

# 入ったあと
sudo systemctl status raisechat caddy
sudo journalctl -u raisechat -n 100 --no-pager   # アプリのログ
sudo journalctl -u caddy -n 100 --no-pager       # 証明書・中継のログ
sudo cat /var/log/raisechat-bootstrap.log        # 初回起動スクリプトのログ
```

- **バックアップ:** データ用EBSの日次スナップショット(7世代)が自動で作られる(03:00 JST)。手動で取るには、コンソールかCLIで `Snapshot=raisechat` タグのボリュームのスナップショットを作る。
- **復元:** スナップショットから新しいボリュームを作り、`terraform` の管理に取り込むか、インスタンスを止めてボリュームを差し替える。大きな操作なので、事前に手順を練習しておく。
- **スケールアップ:** `terraform.tfvars` の `instance_type` を上げて `terraform apply`(再起動で数分止まる)。データ容量は、`data_volume_size` を増やす(減らすことはできない。増やしたあとは、インスタンス内で `sudo xfs_growfs /var/lib/raisechat`)。

## 7. 費用の目安

月額は、リージョンや時期で変わるので、[AWS 料金](https://aws.amazon.com/pricing/)で確認する。課金されるもの:

- **EC2**(`t3.micro`。無料利用枠の対象かどうかは、アカウントの条件による)
- **Elastic IP / 公開IPv4アドレス**(時間課金。インスタンスに紐づけていても課金される)
- **EBS**(ルート8GB + データ用。容量課金)とスナップショット
- **S3**(成果物だけなので、わずか)とデータ転送

NAT Gateway・ALB・RDS などの、固定の時間課金が大きいものは使っていない。

## 8. 削除

```bash
cd terraform && terraform destroy
```

**データ用EBSも消える**(DB・添付ファイルが失われる)。必要なら、先にスナップショットを取る(自動のスナップショットも、ボリューム削除後は管理対象外になるので注意)。

## 9. スケーリングについて(この構成の限界と、将来)

この構成は **1台専用**。理由は、データが台の中にあるため:

| 部分 | 現状 | 複数台にすると |
|---|---|---|
| データベース | SQLite(EBS上のファイル) | 台ごとに別のデータになる |
| 添付ファイル | EBS上 | 別の台のファイルが見えない |
| WebSocketの通知 | サーバー内のメモリ | 別の台に繋がった人に届かない |

負荷が増えたら、まず**スケールアップ**(`instance_type`)で対応する。**台数を増やす(オートスケール)**には、次の3つを、複数台で共有できるものに替える必要がある。要件にない機能なので、必要になったときに、別途検討する。

- DB → RDS(PostgreSQL)
- 添付ファイル → S3
- 通知 → ElastiCache(Redis)など

そのうえで、ALB + Auto Scaling グループ(CPU使用率の目標追跡など)にする。

## 10. トラブルシュート

| 症状 | 確認すること |
|---|---|
| `deploy.sh` の最後で疎通できない | 証明書の取得待ち(数分)。`journalctl -u caddy` を見る。80/443が開いているか、ドメイン設定時はAレコードが正しいか |
| ログイン画面は出るが、リアルタイム更新が来ない | WebSocketの接続元(Origin)の制限。`/etc/raisechat/env` の `APP_WEBSOCKET_ALLOWED_ORIGINS` が、実際のURLと一致しているか |
| `aws ssm start-session` が繋がらない | インスタンスが起動して数分たっているか、IAMロールにSSMのポリシーが付いているか |
| アプリが起動しない | `journalctl -u raisechat`。`JWT_SECRET` が空/短いと、`prod` では起動しない |
| `terraform apply` で、S3のバケット名が重複する | バケット名は、アカウントIDを含めて作っている。`name` 変数を変えて作り直す |
