package com.raisechat;

import java.io.IOException;
import java.io.InputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** アップロードファイルをS3へ保存する(app.storage.type=s3)。バケットは非公開を前提とし、配信は署名付きURLで行う */
@Component
@ConditionalOnProperty(name = "app.storage.type", havingValue = "s3")
public class S3FileStorage extends AbstractFileStorage {
	private final S3Client s3;
	private final String bucket;
	private final String prefix;

	public S3FileStorage(S3Client s3, @Value("${app.storage.s3.bucket}") String bucket,
			@Value("${app.storage.s3.prefix:uploads/}") String prefix) {
		this.s3 = s3;
		this.bucket = bucket;
		this.prefix = prefix;
	}

	@Override
	protected void write(String storedName, MultipartFile file) throws IOException {
		PutObjectRequest req = PutObjectRequest.builder().bucket(bucket).key(prefix + storedName)
				.contentType(file.getContentType()).build();
		try (InputStream in = file.getInputStream()) {
			s3.putObject(req, RequestBody.fromInputStream(in, file.getSize()));
		}
	}
}
