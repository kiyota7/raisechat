package com.raisechat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** アップロードファイルをローカルディスクへ保存する */
@Component
public class FileStorage {
	private final Path dir;

	public FileStorage(@Value("${app.upload-dir}") String uploadDir) {
		this.dir = Path.of(uploadDir).toAbsolutePath();
	}

	/** @return url と type(image / video) */
	public Map<String, String> save(MultipartFile file, boolean imageOnly) {
		String ct = file.getContentType() == null ? "" : file.getContentType();
		String type = ct.startsWith("image/") ? "image" : ct.startsWith("video/") ? "video" : null;
		if (type == null || (imageOnly && !type.equals("image"))) {
			throw new ApiException(HttpStatus.BAD_REQUEST,
					imageOnly ? "画像ファイルを選択してください" : "画像または動画ファイルを選択してください");
		}
		String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
		int dot = name.lastIndexOf('.');
		String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "") : "";
		String stored = UUID.randomUUID() + (ext.isEmpty() ? "" : "." + ext);
		try {
			Files.createDirectories(dir);
			file.transferTo(dir.resolve(stored));
		} catch (IOException e) {
			throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ファイルの保存に失敗しました");
		}
		return Map.of("url", "/uploads/" + stored, "type", type);
	}
}
