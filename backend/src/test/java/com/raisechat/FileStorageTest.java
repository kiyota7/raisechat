package com.raisechat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** 保存先(ローカル / S3)ごとの保存処理。S3はモックのクライアントで、リクエストの中身を確認する */
class FileStorageTest {
	private static MockMultipartFile image(String name) {
		return new MockMultipartFile("file", name, "image/png", new byte[] { 1, 2, 3 });
	}

	@Test
	void localStorageWritesFileAndReturnsUploadsUrl(@TempDir Path dir) throws Exception {
		LocalFileStorage storage = new LocalFileStorage(dir.toString());
		Map<String, String> res = storage.save(image("Photo.PNG"), false);

		assertEquals("image", res.get("type"));
		assertTrue(res.get("url").matches("/uploads/[0-9a-f-]{36}\\.png"));
		Path saved = dir.resolve(res.get("url").substring("/uploads/".length()));
		assertArrayEquals(new byte[] { 1, 2, 3 }, Files.readAllBytes(saved));
	}

	@Test
	void s3StoragePutsObjectWithPrefixAndContentType() {
		S3Client s3 = mock(S3Client.class);
		S3FileStorage storage = new S3FileStorage(s3, "my-bucket", "uploads/");
		Map<String, String> res = storage.save(image("a.png"), false);

		ArgumentCaptor<PutObjectRequest> req = ArgumentCaptor.forClass(PutObjectRequest.class);
		verify(s3).putObject(req.capture(), any(RequestBody.class));
		assertEquals("my-bucket", req.getValue().bucket());
		assertEquals("image/png", req.getValue().contentType());
		// DBに保存するURLは保存先によらず /uploads/{ファイル名}。S3のキーはprefixが付く
		String stored = res.get("url").substring("/uploads/".length());
		assertTrue(stored.matches("[0-9a-f-]{36}\\.png"));
		assertEquals("uploads/" + stored, req.getValue().key());
	}

	@Test
	void s3FailureBecomesApiError() {
		S3Client s3 = mock(S3Client.class);
		when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(new RuntimeException("boom"));
		S3FileStorage storage = new S3FileStorage(s3, "my-bucket", "uploads/");

		ApiException e = assertThrows(ApiException.class, () -> storage.save(image("a.png"), false));
		assertEquals(500, e.getStatus().value());
	}

	@Test
	void rejectsNonMediaAndNonImageAvatars(@TempDir Path dir) {
		LocalFileStorage storage = new LocalFileStorage(dir.toString());
		MockMultipartFile text = new MockMultipartFile("file", "a.txt", "text/plain", new byte[] { 1 });
		MockMultipartFile video = new MockMultipartFile("file", "a.webm", "video/webm", new byte[] { 1 });

		assertEquals(400, assertThrows(ApiException.class, () -> storage.save(text, false)).getStatus().value());
		// アバターは画像のみ
		assertEquals(400, assertThrows(ApiException.class, () -> storage.save(video, true)).getStatus().value());
		assertEquals("video", storage.save(video, false).get("type"));
	}
}
