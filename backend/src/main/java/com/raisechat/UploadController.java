package com.raisechat;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * S3モードの添付ファイル配信。DBに保存したURL(/uploads/{ファイル名})のまま、短時間だけ有効な
 * 署名付きURLへリダイレクトする。画像・動画タグはAuthorizationヘッダーを送れないため、
 * ローカル配信と同じくURLを知っていること(UUIDで推測不能)を条件にしている。
 */
@RestController
@ConditionalOnProperty(name = "app.storage.type", havingValue = "s3")
public class UploadController {
	static final Duration URL_TTL = Duration.ofMinutes(10);
	/** UUID+拡張子の形だけ受け付ける。先頭は英数字(".." や隠しファイル名を拒否) */
	private static final String KEY_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]*";

	private final S3Presigner presigner;
	private final String bucket;
	private final String prefix;

	public UploadController(S3Presigner presigner, @Value("${app.storage.s3.bucket}") String bucket,
			@Value("${app.storage.s3.prefix:uploads/}") String prefix) {
		this.presigner = presigner;
		this.bucket = bucket;
		this.prefix = prefix;
	}

	@GetMapping("/uploads/{key}")
	public ResponseEntity<Void> get(@PathVariable String key) {
		if (!key.matches(KEY_PATTERN)) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
		}
		GetObjectPresignRequest req = GetObjectPresignRequest.builder().signatureDuration(URL_TTL)
				.getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(prefix + key).build()).build();
		String url = presigner.presignGetObject(req).url().toString();
		// 署名の有効期間(10分)より短くキャッシュさせ、期限切れのURLを再利用しないようにする
		return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, url)
				.cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate()).build();
	}
}
