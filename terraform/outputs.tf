output "app_url" {
  description = "アプリのURL(ドメイン未設定の間は sslip.io の名前)"
  value       = "https://${local.site_address}"
}

output "public_ip" {
  description = "Elastic IP。ドメインを設定するときは、DNSのAレコードをこのIPに向ける"
  value       = aws_eip.app.public_ip
}

output "instance_id" {
  description = "EC2のインスタンスID(SSMでの操作に使う)"
  value       = aws_instance.app.id
}

output "artifacts_bucket" {
  description = "成果物(jar・画面)をアップロードするS3バケット"
  value       = aws_s3_bucket.artifacts.id
}

output "region" {
  description = "リージョン"
  value       = var.aws_region
}

output "ssm_session_command" {
  description = "インスタンスに入るコマンド(SSH不要)"
  value       = "aws ssm start-session --target ${aws_instance.app.id} --region ${var.aws_region}"
}
