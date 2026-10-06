# --- 成果物(ビルドしたjarと画面)を置く、非公開のS3バケット --------------------
# アプリの添付ファイル保存先ではない(添付ファイルは、データ用EBSに保存する)
resource "aws_s3_bucket" "artifacts" {
  bucket        = "${var.name}-artifacts-${data.aws_caller_identity.current.account_id}"
  force_destroy = true # 成果物だけなので、destroyで中身ごと消してよい
}

resource "aws_s3_bucket_public_access_block" "artifacts" {
  bucket                  = aws_s3_bucket.artifacts.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

data "aws_iam_policy_document" "artifacts_tls_only" {
  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.artifacts.arn, "${aws_s3_bucket.artifacts.arn}/*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "artifacts" {
  bucket = aws_s3_bucket.artifacts.id
  policy = data.aws_iam_policy_document.artifacts_tls_only.json

  depends_on = [aws_s3_bucket_public_access_block.artifacts]
}

# --- データ用EBS: DB(SQLite)・添付ファイル・JWT秘密鍵 --------------------------
# インスタンスを作り直しても、データは残る(別のボリュームのため)。
# ただし `terraform destroy` では、このボリュームも消える(必要なら、先にスナップショットを取る)
resource "aws_ebs_volume" "data" {
  availability_zone = data.aws_subnet.selected.availability_zone
  size              = var.data_volume_size
  type              = "gp3"
  encrypted         = true

  tags = {
    Name     = "${var.name}-data"
    Snapshot = var.name # 下のスナップショットの対象
  }
}

# 毎日スナップショットを取り、7世代残す(03:00 JST = 18:00 UTC)
resource "aws_dlm_lifecycle_policy" "data" {
  description        = "${var.name} data volume daily snapshots"
  execution_role_arn = aws_iam_role.dlm.arn
  state              = "ENABLED"

  policy_details {
    resource_types = ["VOLUME"]

    target_tags = {
      Snapshot = var.name
    }

    schedule {
      name = "daily"

      create_rule {
        interval      = 24
        interval_unit = "HOURS"
        times         = ["18:00"]
      }

      retain_rule {
        count = 7
      }

      copy_tags = true
    }
  }
}
