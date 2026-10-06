package com.raisechat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** REST APIをMockMvcで呼ぶテストの共通処理 */
@SpringBootTest(properties = { TestDb.URL, TestDb.DRIVER, TestDb.POOL, TestDb.USER, TestDb.PASSWORD })
@AutoConfigureMockMvc
abstract class ApiTestBase {
	@Autowired
	MockMvc mvc;
	final ObjectMapper om = new ObjectMapper();

	record Res(int status, JsonNode body) {
	}

	record TestUser(String name, String token, long id) {
	}

	Res send(String method, String path, String token, String body) throws Exception {
		MockHttpServletRequestBuilder b = MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method), "/api" + path)
				.contentType(MediaType.APPLICATION_JSON);
		if (token != null) {
			b.header("Authorization", "Bearer " + token);
		}
		if (body != null) {
			b.content(body);
		}
		MockHttpServletResponse res = mvc.perform(b).andReturn().getResponse();
		String text = res.getContentAsString();
		return new Res(res.getStatus(), text.isEmpty() ? null : om.readTree(text));
	}

	/** 200で返ることを確認して、本文を返す */
	JsonNode ok(String method, String path, String token, String body) throws Exception {
		Res r = send(method, path, token, body);
		assertEquals(200, r.status(), method + " " + path + " → " + r.body());
		return r.body();
	}

	int status(String method, String path, String token, String body) throws Exception {
		return send(method, path, token, body).status();
	}

	String uniq(String prefix) {
		return prefix + "_" + UUID.randomUUID().toString().substring(0, 6);
	}

	TestUser register(String prefix) throws Exception {
		String name = uniq(prefix);
		JsonNode r = ok("POST", "/auth/register", null, "{\"username\":\"" + name + "\",\"password\":\"Chat-Test-1x\"}");
		return new TestUser(name, r.get("token").asText(), r.get("user").get("id").asLong());
	}

	long createWorkspace(TestUser owner) throws Exception {
		return ok("POST", "/workspaces", owner.token(), "{\"name\":\"WS\"}").get("id").asLong();
	}

	void invite(long ws, TestUser owner, TestUser guest) throws Exception {
		ok("POST", "/workspaces/" + ws + "/invite", owner.token(), "{\"username\":\"" + guest.name() + "\"}");
	}

	JsonNode overview(long ws, TestUser u) throws Exception {
		return ok("GET", "/workspaces/" + ws, u.token(), null);
	}

	long channelId(JsonNode overview, String name) {
		for (JsonNode c : overview.get("channels")) {
			if (name.equals(c.get("name").asText())) {
				return c.get("id").asLong();
			}
		}
		throw new AssertionError("チャンネルがない: " + name);
	}

	JsonNode channel(JsonNode overview, String name) {
		return overview.get("channels").get((int) indexOf(overview, name));
	}

	private int indexOf(JsonNode overview, String name) {
		for (int i = 0; i < overview.get("channels").size(); i++) {
			if (name.equals(overview.get("channels").get(i).get("name").asText())) {
				return i;
			}
		}
		throw new AssertionError("チャンネルがない: " + name);
	}

	long post(long channel, TestUser u, String content) throws Exception {
		return ok("POST", "/channels/" + channel + "/messages", u.token(), "{\"content\":\"" + content + "\"}").get("id").asLong();
	}

	long reply(long channel, TestUser u, String content, long parent) throws Exception {
		return ok("POST", "/channels/" + channel + "/messages", u.token(), "{\"content\":\"" + content + "\",\"parentId\":" + parent + "}").get("id").asLong();
	}
}
