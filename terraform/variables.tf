variable "aws_region" {
  description = "デプロイ先のAWSリージョン"
  type        = string
  default     = "ap-northeast-1"
}

variable "name" {
  description = "リソース名の接頭辞"
  type        = string
  default     = "raisechat"
}

variable "instance_type" {
  description = "EC2のインスタンスタイプ。無料利用枠の対象は t3.micro(アカウントの条件による)。負荷が増えたらここを上げる(スケールアップ)"
  type        = string
  default     = "t3.micro"
}

variable "data_volume_size" {
  description = "データ用EBS(DB・添付ファイル)の容量(GB)。増やすことはできるが、減らすことはできない"
  type        = number
  default     = 10
}

variable "domain" {
  description = "公開するドメイン名(例 chat.example.com)。空の間は、Elastic IPから作る <IP>.sslip.io で動かす(検証用)。設定する場合は、DNSのAレコードを output の public_ip に向ける"
  type        = string
  default     = ""
}

variable "caddy_version" {
  description = "インスタンスに入れるCaddyのバージョン(固定して、配布物のチェックサムを検証する)"
  type        = string
  default     = "2.8.4"
}
