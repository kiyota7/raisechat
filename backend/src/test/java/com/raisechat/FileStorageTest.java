package com.raisechat;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

/** アップロードの保存処理(種類の判定・保存名・拒否) */
class FileStorageTest {
	private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3 };
	private static final byte[] WEBM = { 0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 1, 2, 3, 4 };

	private static MockMultipartFile image(String name) {
		return new MockMultipartFile("file", name, "image/png", PNG);
	}

	private static byte[] bytes(String s) {
		return s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
	}

	@Test
	void localStorageWritesFileAndReturnsUploadsUrl(@TempDir Path dir) throws Exception {
		LocalFileStorage storage = new LocalFileStorage(dir.toString());
		Map<String, String> res = storage.save(image("Photo.PNG"), false);

		assertEquals("image", res.get("type"));
		assertTrue(res.get("url").matches("/uploads/[0-9a-f-]{36}\\.png"));
		Path saved = dir.resolve(res.get("url").substring("/uploads/".length()));
		assertArrayEquals(PNG, Files.readAllBytes(saved));
	}

	@Test
	void rejectsNonMediaAndNonImageAvatars(@TempDir Path dir) {
		LocalFileStorage storage = new LocalFileStorage(dir.toString());
		MockMultipartFile text = new MockMultipartFile("file", "a.txt", "text/plain", new byte[] { 1 });
		MockMultipartFile video = new MockMultipartFile("file", "a.webm", "video/webm", WEBM);

		assertEquals(400, assertThrows(ApiException.class, () -> storage.save(text, false)).getStatus().value());
		// アバターは画像のみ
		assertEquals(400, assertThrows(ApiException.class, () -> storage.save(video, true)).getStatus().value());
		assertEquals("video", storage.save(video, false).get("type"));
	}

	@Test
	void rejectsHtmlDisguisedAsImage(@TempDir Path dir) {
		LocalFileStorage storage = new LocalFileStorage(dir.toString());
		var html = new MockMultipartFile("file", "poc.html", "image/png", bytes("<html><script>alert(1)</script></html>"));
		assertEquals(400, assertThrows(ApiException.class, () -> storage.save(html, false)).getStatus().value());
		assertEquals(0, dir.toFile().list() == null ? 0 : dir.toFile().list().length);
	}

	@Test
	void rejectsSvgEvenWithImageContentType(@TempDir Path dir) {
		LocalFileStorage storage = new LocalFileStorage(dir.toString());
		var svg = new MockMultipartFile("file", "a.svg", "image/svg+xml",
				bytes("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"));
		assertEquals(400, assertThrows(ApiException.class, () -> storage.save(svg, false)).getStatus().value());
	}

	@Test
	void extensionComesFromDetectedTypeNotFileName(@TempDir Path dir) {
		LocalFileStorage storage = new LocalFileStorage(dir.toString());
		var f = new MockMultipartFile("file", "evil.html", "text/html", PNG);
		Map<String, String> res = storage.save(f, false);
		assertEquals("image", res.get("type"));
		assertTrue(res.get("url").matches("/uploads/[0-9a-f-]{36}\\.png"), res.get("url"));
	}

	@Test
	void detectsSupportedFormats(@TempDir Path dir) {
		LocalFileStorage storage = new LocalFileStorage(dir.toString());
		byte[] jpg = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0 };
		byte[] gif = bytes("GIF89a....");
		byte[] webp = bytes("RIFF\u0001\u0002\u0003\u0004WEBPVP8 ");
		byte[] mp4 = bytes("\0\0\0\u0018ftypisom\0\0\0\0");
		assertTrue(storage.save(new MockMultipartFile("file", "x", "x", jpg), false).get("url").endsWith(".jpg"));
		assertTrue(storage.save(new MockMultipartFile("file", "x", "x", gif), false).get("url").endsWith(".gif"));
		assertTrue(storage.save(new MockMultipartFile("file", "x", "x", webp), false).get("url").endsWith(".webp"));
		Map<String, String> v = storage.save(new MockMultipartFile("file", "x", "x", mp4), false);
		assertTrue(v.get("url").endsWith(".mp4"));
		assertEquals("video", v.get("type"));
		assertTrue(storage.save(new MockMultipartFile("file", "x", "x", WEBM), false).get("url").endsWith(".webm"));
	}

}
