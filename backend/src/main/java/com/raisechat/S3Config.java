package com.raisechat;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3クライアントの設定。認証情報は設定ファイルに持たず、AWS標準の取得方法
 * (環境変数 / ~/.aws / IAMロールなど)に任せる。endpoint はMinIOなどS3互換サーバー用。
 */
@Configuration
@ConditionalOnProperty(name = "app.storage.type", havingValue = "s3")
public class S3Config {
	@Bean
	S3Client s3Client(@Value("${app.storage.s3.region}") String region,
			@Value("${app.storage.s3.endpoint:}") String endpoint,
			@Value("${app.storage.s3.path-style:false}") boolean pathStyle) {
		var b = S3Client.builder().region(Region.of(region)).forcePathStyle(pathStyle);
		if (!endpoint.isBlank()) {
			b.endpointOverride(URI.create(endpoint));
		}
		return b.build();
	}

	@Bean
	S3Presigner s3Presigner(@Value("${app.storage.s3.region}") String region,
			@Value("${app.storage.s3.endpoint:}") String endpoint,
			@Value("${app.storage.s3.path-style:false}") boolean pathStyle) {
		var b = S3Presigner.builder().region(Region.of(region))
				.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build());
		if (!endpoint.isBlank()) {
			b.endpointOverride(URI.create(endpoint));
		}
		return b.build();
	}
}
