package com.raisechat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 登録APIは、同じIPからの試行が多すぎると429で止まる(専用の設定で、他のテストと状態を分ける) */
@SpringBootTest(properties = { TestDb.URL, TestDb.DRIVER, TestDb.POOL, TestDb.USER, TestDb.PASSWORD,
		"app.register.max-per-ip=3", "app.register.window-minutes=60" })
@AutoConfigureMockMvc
class RegisterRateLimitTest {
	@Autowired
	MockMvc mvc;

	private ResultActions register(String ip, String user, String pw) throws Exception {
		return mvc.perform(post("/api/auth/register").with(r -> {
			r.setRemoteAddr(ip);
			return r;
		}).contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"" + user + "\",\"password\":\"" + pw + "\"}"));
	}

	private static String uniq() {
		return "rl_" + UUID.randomUUID().toString().substring(0, 8);
	}

	@Test
	void blocksTheIpAfterMaxAttemptsButNotOtherIps() throws Exception {
		for (int i = 0; i < 3; i++) {
			register("10.9.0.1", uniq(), "Zq7!kLm29xPw").andExpect(status().isOk());
		}
		register("10.9.0.1", uniq(), "Zq7!kLm29xPw").andExpect(status().isTooManyRequests())
				.andExpect(header().string("Retry-After", "3600"));
		register("10.9.0.2", uniq(), "Zq7!kLm29xPw").andExpect(status().isOk()); // 別のIPは影響を受けない
	}

	@Test
	void failedAttemptsCountToo() throws Exception {
		// 方針チェックで弾かれた失敗も数える(長さなどの入力形式の検証は、コントローラの前に行われるので対象外)
		for (int i = 0; i < 3; i++) {
			register("10.9.1.1", uniq(), "aaaaaaaaaa").andExpect(status().isBadRequest());
		}
		register("10.9.1.1", uniq(), "Zq7!kLm29xPw").andExpect(status().isTooManyRequests());
	}
}
