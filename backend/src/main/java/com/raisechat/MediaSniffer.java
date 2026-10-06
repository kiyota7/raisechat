package com.raisechat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.web.multipart.MultipartFile;

/**
 * ファイルの先頭バイトから種類を判定する。クライアントが申告する Content-Type やファイル名は信用しない。
 * 対応する形式だけを許可リストで受け付ける(SVGやHTMLなど、スクリプトを含み得る形式は対象外)。
 */
final class MediaSniffer {
	/** 判定結果。保存する拡張子と配信時の Content-Type は、ここで決めた値だけを使う */
	record Detected(String kind, String ext, String contentType) {
	}

	private MediaSniffer() {
	}

	static Optional<Detected> detect(MultipartFile file) throws IOException {
		byte[] head = new byte[16];
		int n;
		try (InputStream in = file.getInputStream()) {
			n = in.readNBytes(head, 0, head.length);
		}
		return detect(Arrays.copyOf(head, n));
	}

	static Optional<Detected> detect(byte[] h) {
		if (startsWith(h, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
			return image("png", "image/png");
		}
		if (startsWith(h, 0, 0xFF, 0xD8, 0xFF)) {
			return image("jpg", "image/jpeg");
		}
		if (startsWith(h, 0, 'G', 'I', 'F', '8') && h.length > 4 && (h[4] == '7' || h[4] == '9') && h.length > 5 && h[5] == 'a') {
			return image("gif", "image/gif");
		}
		if (startsWith(h, 0, 'R', 'I', 'F', 'F') && startsWith(h, 8, 'W', 'E', 'B', 'P')) {
			return image("webp", "image/webp");
		}
		if (startsWith(h, 4, 'f', 't', 'y', 'p')) {
			return Optional.of(new Detected("video", "mp4", "video/mp4"));
		}
		if (startsWith(h, 0, 0x1A, 0x45, 0xDF, 0xA3)) {
			return Optional.of(new Detected("video", "webm", "video/webm"));
		}
		return Optional.empty();
	}

	private static Optional<Detected> image(String ext, String contentType) {
		return Optional.of(new Detected("image", ext, contentType));
	}

	private static boolean startsWith(byte[] h, int offset, int... expected) {
		if (h.length < offset + expected.length) {
			return false;
		}
		for (int i = 0; i < expected.length; i++) {
			if ((h[offset + i] & 0xFF) != expected[i]) {
				return false;
			}
		}
		return true;
	}
}
