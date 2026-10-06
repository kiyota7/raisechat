# 専用のVPCは作らず、デフォルトVPCを使う(NAT Gatewayなどの時間課金を避ける)
data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }

  filter {
    name   = "default-for-az"
    values = ["true"]
  }
}

data "aws_subnet" "selected" {
  id = sort(data.aws_subnets.default.ids)[0]
}

# 公開するのはWeb(80/443)だけ。SSHは開けず、操作はSSM Session Manager経由で行う。
# 80番はHTTPSへの転送と、証明書の取得(Let's Encrypt)に使う。
resource "aws_security_group" "app" {
  name        = "${var.name}-app"
  description = "RaiseChat web (80/443 only; no SSH)"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "HTTP (redirect to HTTPS / ACME)"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTPS"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "all outbound (OS updates, ACME, SSM, S3)"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.name}-app"
  }
}
