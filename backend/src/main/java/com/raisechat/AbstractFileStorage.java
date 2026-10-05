package com.raisechat;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

/** ファイルの種類チェックと保存名の決定を共通化する。実際の書き込みは保存先ごとに実装する */
abstract class AbstractFileStorage implements FileStorage {
	@Override
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
			write(stored, file);
		} catch (IOException | RuntimeException e) {
			throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ファイルの保存に失敗しました");
		}
		return Map.of("url", "/uploads/" + stored, "type", type);
	}

	/** 保存名(UUID+拡張子)でファイルを書き込む */
	protected abstract void write(String storedName, MultipartFile file) throws IOException;
}
