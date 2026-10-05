package com.raisechat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

/** トークンは有効だが、ユーザーが存在しない(DBを作り直した後や、ユーザー削除後の古いログイン状態)場合は、500ではなく401を返す */
class StaleTokenTest extends ApiTestBase {
	@Autowired
	JdbcTemplate jdbc;

	/** ユーザーを登録して、そのユーザーをDBから消す。トークンだけが残る */
	private TestUser userWhoWasDeleted() throws Exception {
		TestUser u = register("gone");
		jdbc.update("DELETE FROM users WHERE id = ?", u.id());
		return u;
	}

	@Test
	void meReturns401WhenTheUserNoLongerExists() throws Exception {
		TestUser u = userWhoWasDeleted();
		Res r = send("GET", "/me", u.token(), null);
		assertEquals(401, r.status());
		assertEquals("セッションが無効です。再度ログインしてください", r.body().get("message").asText());
	}

	@Test
	void profileUpdateAndAvatarReturn401WhenTheUserNoLongerExists() throws Exception {
		TestUser u = userWhoWasDeleted();
		assertEquals(401, status("PUT", "/me", u.token(), "{\"displayName\":\"x\",\"status\":\"\"}"));

		byte[] png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");
		int st = mvc.perform(multipart("/api/me/avatar").file(new MockMultipartFile("file", "a.png", "image/png", png))
				.header("Authorization", "Bearer " + u.token())).andReturn().getResponse().getStatus();
		assertEquals(401, st);
	}

	@Test
	void existingUsersAreUnaffected() throws Exception {
		TestUser u = register("here");
		assertEquals(u.name(), ok("GET", "/me", u.token(), null).get("username").asText());
		assertEquals("Renamed", ok("PUT", "/me", u.token(), "{\"displayName\":\"Renamed\",\"status\":\"\"}").get("displayName").asText());
	}
}
