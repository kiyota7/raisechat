package com.raisechat;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.ServletInputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/** JSONの本文に上限をかけ、巨大なリクエストでメモリを使わせない */
@SpringBootTest(properties = { TestDb.URL, TestDb.DRIVER, TestDb.POOL, TestDb.USER, TestDb.PASSWORD,
		"app.max-json-body-bytes=1000" })
@AutoConfigureMockMvc
class BodyLimitTest {
	@Autowired
	MockMvc mvc;
	@Autowired
	JsonBodyLimitFilter filter;

	@Test
	void rejectsOversizedJsonByContentLength() throws Exception {
		String big = "{\"username\":\"x\",\"password\":\"" + "a".repeat(2000) + "\"}";
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(big))
				.andExpect(status().isPayloadTooLarge());
	}

	@Test
	void smallJsonStillWorks() throws Exception {
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"nobody\",\"password\":\"whatever-1\"}")).andExpect(status().isUnauthorized());
	}

	@Test
	void rejectsOversizedChunkedBodyWhileReading() throws Exception {
		byte[] body = ("{\"a\":\"" + "b".repeat(2000) + "\"}").getBytes();
		MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/login") {
			@Override
			public long getContentLengthLong() {
				return -1; // Transfer-Encoding: chunked の想定(長さが分からない)
			}

			@Override
			public int getContentLength() {
				return -1;
			}
		};
		req.setContentType("application/json");
		req.setContent(body);
		MockHttpServletResponse res = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		filter.doFilter(req, res, chain);
		// 本文を読み始めると、上限で止まる
		ServletInputStream in = ((jakarta.servlet.http.HttpServletRequest) chain.getRequest()).getInputStream();
		assertThrows(IOException.class, () -> in.readAllBytes());
	}
}
