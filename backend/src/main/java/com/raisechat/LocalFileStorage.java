package com.raisechat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** アップロードファイルをローカルディスクへ保存する(既定) */
@Component
@ConditionalOnProperty(name = "app.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalFileStorage extends AbstractFileStorage {
	private final Path dir;

	public LocalFileStorage(@Value("${app.upload-dir}") String uploadDir) {
		this.dir = Path.of(uploadDir).toAbsolutePath();
	}

	@Override
	protected void write(String storedName, MultipartFile file) throws IOException {
		Files.createDirectories(dir);
		file.transferTo(dir.resolve(storedName));
	}
}
