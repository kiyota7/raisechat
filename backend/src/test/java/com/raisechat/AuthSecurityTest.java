package com.raisechat;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** 登録・ログインまわりの境界の挙動(パスワードの長さ、ID重複、同時登録)。APIを通して確認する */
@SpringBootTest(properties = { TestDb.URL, TestDb.DRIVER, TestDb.POOL, TestDb.USER, TestDb.PASSWORD })
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
	void registrationRejectsTooShortPasswords() throws Exception {
		String u = uniq("reg");
		// 長さの検査(8文字以上)
		register(u, "Ab1-xyz").andExpect(status().isBadRequest());
		register(u, GOOD).andExpect(status().isOk());
	}

	@Test
	void registrationRejectsPasswordsOverBcryptLimitInsteadOfFailing() throws Exception {
		// 日本語30文字は90バイト。以前はBCryptの例外で500になっていた
		register(uniq("jp"), "あ".repeat(30)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("長すぎ")));
	}

	@Test
	void veryLongLoginPasswordIsJustAFailureNotAServerError() throws Exception {
		String u = uniq("long");
		register(u, GOOD).andExpect(status().isOk());
		login(u, "x".repeat(500), "10.0.3.1").andExpect(status().isUnauthorized());
	}
	@Test
	void registeringAnExistingUsernameReturnsConflictNotServerError() throws Exception {
		String u = uniq("dup");
		register(u, GOOD).andExpect(status().isOk());
		register(u, GOOD).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("このユーザーIDは既に使われています"));
		register(u, "Another-Pass-9z").andExpect(status().isConflict()); // パスワードが違っても同じ
		// 別のユーザーIDなら、引き続き登録できる
		register(uniq("dup"), GOOD).andExpect(status().isOk());
	}

	@Test
	void simultaneousRegistrationsOfTheSameUsernameYieldOneSuccessAndConflictsOnly() throws Exception {
		String u = uniq("race");
		int n = 8;
		ExecutorService pool = Executors.newFixedThreadPool(n);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			Callable<Integer> task = () -> {
				go.await();
				return register(u, GOOD).andReturn().getResponse().getStatus();
			};
			results.add(pool.submit(task));
		}
		go.countDown(); // 全員を同時に開始する
		int ok = 0, conflict = 0, other = 0;
		for (Future<Integer> f : results) {
			int st = f.get();
			if (st == 200) ok++;
			else if (st == 409) conflict++;
			else other++;
		}
		pool.shutdown();
		org.junit.jupiter.api.Assertions.assertEquals(1, ok, "登録できるのは1人だけ");
		org.junit.jupiter.api.Assertions.assertEquals(n - 1, conflict, "残りは409(500になってはいけない)");
		org.junit.jupiter.api.Assertions.assertEquals(0, other);
	}
}
