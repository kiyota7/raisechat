package com.raisechat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** APIの応答(認証エラーを含む)に、基本的なセキュリティヘッダーが付く */
@SpringBootTest(properties = { TestDb.URL, TestDb.DRIVER, TestDb.POOL, TestDb.USER, TestDb.PASSWORD })
@AutoConfigureMockMvc
class SecurityHeadersTest {
	@Autowired
	MockMvc mvc;

	@Test
	void apiResponsesCarrySecurityHeaders() throws Exception {
		mvc.perform(get("/api/me")) // 未認証の401にも付く
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(header().string("X-Frame-Options", "DENY"))
				.andExpect(header().string("Referrer-Policy", "no-referrer"));
	}

	@Test
	void notFoundAlsoCarriesThem() throws Exception {
		mvc.perform(get("/no-such-path")).andExpect(header().string("X-Content-Type-Options", "nosniff"));
	}
}
