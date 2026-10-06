package com.raisechat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** アップロードファイルをローカルディスク(app.upload-dir)へ保存する。種類の判定と保存名の決定もここで行う */
@Component
public class LocalFileStorage {
	private final Path dir;

	public LocalFileStorage(@Value("${app.upload-dir}") String uploadDir) {
		this.dir = Path.of(uploadDir).toAbsolutePath();
	}

	/** @return url(/uploads/{ファイル名})と type(image / video) */
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
		// 拡張子は、申告されたファイル名ではなく、判定した内容から決める
		String stored = UUID.randomUUID() + "." + d.ext();
		try {
			Files.createDirectories(dir);
			file.transferTo(dir.resolve(stored));
		} catch (IOException | RuntimeException e) {
			throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ファイルの保存に失敗しました");
		}
		return Map.of("url", "/uploads/" + stored, "type", d.kind());
	}
}
