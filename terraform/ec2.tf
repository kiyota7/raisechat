# AMI IDをハードコードせず、AWSが公式に提供するSSMパラメータから、最新のAmazon Linux 2023(x86_64)を取得する
data "aws_ssm_parameter" "al2023_ami" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

# Elastic IPを先に確保して、インスタンスの起動スクリプト(公開URLの組み立て)で使う。
# 関連付けは、インスタンス作成後の aws_eip_association で行う(依存の循環を避けるため)
resource "aws_eip" "app" {
  domain = "vpc"

  tags = {
    Name = "${var.name}-eip"
  }
}

locals {
  # ドメインが未設定の間は、IPを含む <a-b-c-d>.sslip.io の名前(IPに解決される)でHTTPSを動かす。検証用
  site_address = var.domain != "" ? var.domain : "${replace(aws_eip.app.public_ip, ".", "-")}.sslip.io"
}

resource "aws_instance" "app" {
  ami                    = data.aws_ssm_parameter.al2023_ami.value
  instance_type          = var.instance_type
  subnet_id              = data.aws_subnet.selected.id
  vpc_security_group_ids = [aws_security_group.app.id]
  iam_instance_profile   = aws_iam_instance_profile.app.name

  # IMDSv2のみ(SSRFなどでの認証情報の取得を難しくする)
  metadata_options {
    http_endpoint = "enabled"
    http_tokens   = "required"
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = 8
    encrypted   = true
  }

  user_data = templatefile("${path.module}/user_data.sh.tpl", {
    site_address     = local.site_address
    artifacts_bucket = aws_s3_bucket.artifacts.id
    aws_region       = var.aws_region
    caddy_version    = var.caddy_version
    # NVMeのデバイス名は起動ごとに変わるため、ボリュームIDから固定のパスで探す(ハイフンなし)
    data_volume_id = replace(aws_ebs_volume.data.id, "-", "")
  })

  tags = {
    Name = "${var.name}-app"
  }

  lifecycle {
    # AMIが更新されても、勝手に作り直さない(作り直すときは `terraform apply -replace=aws_instance.app`)。
    # user_dataは初回起動のときだけ実行されるので、変更しても反映されない
    ignore_changes = [ami, user_data]
  }
}

resource "aws_eip_association" "app" {
  instance_id   = aws_instance.app.id
  allocation_id = aws_eip.app.id
}

resource "aws_volume_attachment" "data" {
  device_name = "/dev/sdf"
  volume_id   = aws_ebs_volume.data.id
  instance_id = aws_instance.app.id

  # インスタンスを作り直すときに、マウント中のボリュームを安全に切り離すため、先にインスタンスを止める
  stop_instance_before_detaching = true
}
