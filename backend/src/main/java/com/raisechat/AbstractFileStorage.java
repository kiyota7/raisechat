package com.raisechat;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

/** ファイルの種類チェックと保存名の決定を共通化する。実際の書き込みは保存先ごとに実装する */
abstract class AbstractFileStorage implements FileStorage {
	@Override
	public Map<String, String> save(MultipartFile file, boolean imageOnly) {
		MediaSniffer.Detected d;
		try {
			d = MediaSniffer.detect(file).orElse(null);
		} catch (IOException e) {
			throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ファイルの保存に失敗しました");
		}
		if (d == null || (imageOnly && !d.kind().equals("image"))) {
			throw new ApiException(HttpStatus.BAD_REQUEST,
					imageOnly ? "画像ファイルを選択してください" : "画像または動画ファイルを選択してください");
		}
		// 拡張子と Content-Type は、申告ではなく判定した内容から決める
		String stored = UUID.randomUUID() + "." + d.ext();
		try {
			write(stored, file, d.contentType());
		} catch (IOException | RuntimeException e) {
			throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ファイルの保存に失敗しました");
		}
		return Map.of("url", "/uploads/" + stored, "type", d.kind());
	}

	/** 保存名(UUID+拡張子)でファイルを書き込む。contentType は判定した値 */
	protected abstract void write(String storedName, MultipartFile file, String contentType) throws IOException;
}
