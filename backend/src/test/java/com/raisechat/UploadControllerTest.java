package com.raisechat;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** S3モードの配信: /uploads/{ファイル名} が、署名付きURLへのリダイレクトになる(ネットワーク不要) */
@SpringBootTest(properties = { TestDb.URL, TestDb.DRIVER, TestDb.POOL, TestDb.USER, TestDb.PASSWORD,
		"app.storage.type=s3", "app.storage.s3.bucket=test-bucket", "app.storage.s3.region=ap-northeast-1" })
@AutoConfigureMockMvc
class UploadControllerTest {
	static {
		// 署名の生成に必要なダミーの認証情報(実際のAWSには接続しない)
		System.setProperty("aws.accessKeyId", "test-access-key");
		System.setProperty("aws.secretAccessKey", "test-secret-key");
	}

	@Autowired
	MockMvc mvc;
	@Autowired
	FileStorage storage;

	@Test
	void usesS3Storage() {
		assertInstanceOf(S3FileStorage.class, storage);
	}

	@Test
	void redirectsToPresignedUrl() throws Exception {
		mvc.perform(get("/uploads/0a28be51-daf0-47f0-b5f4-9fb9d2cc09d3.png"))
				.andExpect(status().isFound())
				.andExpect(header().string("Location", containsString("test-bucket")))
				.andExpect(header().string("Location", containsString("uploads/0a28be51-daf0-47f0-b5f4-9fb9d2cc09d3.png")))
				.andExpect(header().string("Location", containsString("X-Amz-Signature=")))
				.andExpect(header().string("Location", containsString("X-Amz-Expires=600")))
				.andExpect(header().string("Cache-Control", containsString("private")));
	}

	@Test
	void rejectsUnexpectedKeys() throws Exception {
		mvc.perform(get("/uploads/..")).andExpect(status().isNotFound());
		mvc.perform(get("/uploads/.hidden")).andExpect(status().isNotFound());
		mvc.perform(get("/uploads/a%2Fb.png")).andExpect(status().is4xxClientError());
	}
}
