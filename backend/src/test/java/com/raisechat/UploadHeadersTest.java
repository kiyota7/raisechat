package com.raisechat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** ローカル配信の /uploads は、ブラウザに中身を解釈(スクリプト実行)させない */
@SpringBootTest(properties = { TestDb.URL, TestDb.DRIVER, TestDb.POOL, TestDb.USER, TestDb.PASSWORD,
		"app.upload-dir=target/headers-uploads" })
@AutoConfigureMockMvc
class UploadHeadersTest {
	@Autowired
	MockMvc mvc;

	@Test
	void uploadsAreServedWithNosniffAndSandboxingCsp() throws Exception {
		Path dir = Path.of("target/headers-uploads").toAbsolutePath();
		Files.createDirectories(dir);
		Files.write(dir.resolve("probe.png"), new byte[] { (byte) 0x89, 'P', 'N', 'G' });

		mvc.perform(get("/uploads/probe.png")).andExpect(status().isOk())
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"));
		assertTrue(Files.exists(dir.resolve("probe.png")));
		assertEquals(404, mvc.perform(get("/uploads/none.png")).andReturn().getResponse().getStatus());
	}
}
