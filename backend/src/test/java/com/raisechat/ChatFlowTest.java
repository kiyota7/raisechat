package com.raisechat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = { TestDb.URL, TestDb.DRIVER, TestDb.POOL, TestDb.USER, TestDb.PASSWORD })
@AutoConfigureMockMvc
class ChatFlowTest {
	@Autowired
	MockMvc mvc;
	final ObjectMapper om = new ObjectMapper();

	JsonNode call(MockHttpServletRequestBuilder b, String token, String body) throws Exception {
		b.contentType(MediaType.APPLICATION_JSON);
		if (token != null) b.header("Authorization", "Bearer " + token);
		if (body != null) b.content(body);
		String res = mvc.perform(b).andReturn().getResponse().getContentAsString();
		return res.isEmpty() ? null : om.readTree(res);
	}

	String register(String name) throws Exception {
		return call(post("/api/auth/register"), null, "{\"username\":\"" + name + "\",\"password\":\"Chat-Test-1x\"}").get("token").asText();
	}

	@Test
	void ownerInviteMentionThreadAndKick() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 6);
		String owner = "own_" + suffix, guest = "gst_" + suffix;
		String ot = register(owner), gt = register(guest);

		long ws = call(post("/api/workspaces"), ot, "{\"name\":\"WS\"}").get("id").asLong();
		// オーナー以外は招待できない
		mvc.perform(post("/api/workspaces/" + ws + "/invite").header("Authorization", "Bearer " + gt)
				.contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"" + owner + "\"}")).andExpect(status().isForbidden());
		call(post("/api/workspaces/" + ws + "/invite"), ot, "{\"username\":\"" + guest + "\"}");

		JsonNode ov = call(get("/api/workspaces/" + ws), ot, null);
		long general = ov.get("channels").get(0).get("id").asLong();
		long msg = call(post("/api/channels/" + general + "/messages"), ot, "{\"content\":\"hello @" + guest + "\"}").get("id").asLong();

		JsonNode guestOv = call(get("/api/workspaces/" + ws), gt, null);
		assertEquals(1, guestOv.get("channels").get(0).get("mentions").asInt());

		call(post("/api/channels/" + general + "/messages"), gt, "{\"content\":\"re\",\"parentId\":" + msg + "}");
		assertEquals(1, call(get("/api/messages/" + msg + "/thread"), ot, null).get("replies").size());

		// 他人のメッセージは編集できない
		mvc.perform(put("/api/messages/" + msg).header("Authorization", "Bearer " + gt)
				.contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"x\"}")).andExpect(status().isForbidden());

		// プライベートチャンネルは招待されるまで見えない
		long priv = call(post("/api/workspaces/" + ws + "/channels"), ot, "{\"name\":\"secret\",\"isPrivate\":true}").get("id").asLong();
		assertEquals(1, call(get("/api/workspaces/" + ws), gt, null).get("channels").size());
		mvc.perform(get("/api/channels/" + priv + "/messages").header("Authorization", "Bearer " + gt)).andExpect(status().isForbidden());

		long guestId = guestOv.get("members").get(1).get("id").asLong();
		mvc.perform(delete("/api/workspaces/" + ws + "/members/" + guestId).header("Authorization", "Bearer " + ot)).andExpect(status().isOk());
		mvc.perform(get("/api/workspaces/" + ws).header("Authorization", "Bearer " + gt)).andExpect(status().isForbidden());
	}

	@Test
	void unauthenticatedRequestsAreRejected() throws Exception {
		mvc.perform(get("/api/workspaces")).andExpect(status().isUnauthorized());
	}
}
