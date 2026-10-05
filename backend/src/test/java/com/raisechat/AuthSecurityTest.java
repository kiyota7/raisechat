package com.raisechat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** 登録時のパスワード検査と、ログインの失敗回数の制限(APIを通して確認する)。専用の設定で、他のテストと制限の状態を分ける */
@SpringBootTest(properties = { "spring.datasource.url=jdbc:sqlite:target/test-${random.uuid}.db?foreign_keys=true",
		"app.login.max-failures=3", "app.login.ip-max-failures=100", "app.login.window-minutes=15" })
@AutoConfigureMockMvc
class AuthSecurityTest {
	private static final String GOOD = "Chat-Test-1x";

	@Autowired
	MockMvc mvc;

	private static RequestPostProcessor fromIp(String ip) {
		return r -> {
			r.setRemoteAddr(ip);
			return r;
		};
	}

	private ResultActions register(String user, String pw) throws Exception {
		return mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"" + user + "\",\"password\":\"" + pw + "\"}"));
	}

	private ResultActions login(String user, String pw, String ip) throws Exception {
		return mvc.perform(post("/api/auth/login").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"" + user + "\",\"password\":\"" + pw + "\"}"));
	}

	private static String uniq(String prefix) {
		return prefix + "_" + UUID.randomUUID().toString().substring(0, 6);
	}

	@Test
	void registrationRejectsWeakPasswordsWithClearMessages() throws Exception {
		String u = uniq("reg");
		register(u, "12345678").andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("よくある")));
		register(u, "abcdefgh").andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("連続")));
		register(u, u + "-pass").andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("ユーザーID")));
		// 長さの検査(8文字以上)は従来どおり
		register(u, "Ab1-xyz").andExpect(status().isBadRequest());
		register(u, GOOD).andExpect(status().isOk());
	}

	@Test
	void registrationRejectsPasswordsOverBcryptLimitInsteadOfFailing() throws Exception {
		// 日本語30文字は90バイト。以前はBCryptの例外で500になっていた
		register(uniq("jp"), "あ".repeat(30)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("長すぎ")));
	}

	@Test
	void blocksLoginAfterRepeatedFailuresEvenWithTheCorrectPassword() throws Exception {
		String u = uniq("lock");
		register(u, GOOD).andExpect(status().isOk());
		for (int i = 0; i < 3; i++) {
			login(u, "wrong-pass-" + i, "10.0.0.1").andExpect(status().isUnauthorized());
		}
		login(u, "wrong-pass-x", "10.0.0.1").andExpect(status().isTooManyRequests())
				.andExpect(header().string("Retry-After", matchesPattern("\\d+")))
				.andExpect(jsonPath("$.message").value(containsString("分ほど待って")));
		// 制限中は、正しいパスワードでも拒否する(総当たりで当たりを引いても通さない)
		login(u, GOOD, "10.0.0.1").andExpect(status().isTooManyRequests());
		// 別のIPからは、そのユーザー自身は引き続きログインできる
		login(u, GOOD, "10.0.0.2").andExpect(status().isOk());
	}

	@Test
	void unknownUsernamesAreLimitedTheSameWay() throws Exception {
		String ghost = uniq("ghost");
		for (int i = 0; i < 3; i++) {
			login(ghost, "whatever-" + i, "10.0.1.1").andExpect(status().isUnauthorized());
		}
		login(ghost, "whatever-x", "10.0.1.1").andExpect(status().isTooManyRequests()); // 存在するIDと同じ挙動(有無が分からない)
	}

	@Test
	void successfulLoginResetsTheFailureCount() throws Exception {
		String u = uniq("reset");
		register(u, GOOD).andExpect(status().isOk());
		login(u, "wrong-1", "10.0.2.1").andExpect(status().isUnauthorized());
		login(u, "wrong-2", "10.0.2.1").andExpect(status().isUnauthorized());
		login(u, GOOD, "10.0.2.1").andExpect(status().isOk());
		login(u, "wrong-3", "10.0.2.1").andExpect(status().isUnauthorized());
		login(u, "wrong-4", "10.0.2.1").andExpect(status().isUnauthorized());
		login(u, GOOD, "10.0.2.1").andExpect(status().isOk()); // リセットされているので、まだ制限されない
	}

	@Test
	void veryLongLoginPasswordIsJustAFailureNotAServerError() throws Exception {
		String u = uniq("long");
		register(u, GOOD).andExpect(status().isOk());
		login(u, "x".repeat(500), "10.0.3.1").andExpect(status().isUnauthorized());
	}
}
