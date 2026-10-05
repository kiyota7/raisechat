package com.raisechat;

import java.util.Map;
import org.springframework.web.multipart.MultipartFile;

/** アップロードファイルの保存先。実装は設定(app.storage.type)で切り替える */
public interface FileStorage {
	/** @return url(/uploads/{ファイル名}。保存先によらず共通)と type(image / video) */
	Map<String, String> save(MultipartFile file, boolean imageOnly);
}
