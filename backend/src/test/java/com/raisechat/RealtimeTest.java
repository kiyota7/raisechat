package com.raisechat;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.stomp.StompSession;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "spring.datasource.url=jdbc:sqlite:target/test-${random.uuid}.db?foreign_keys=true")
class RealtimeTest extends StompTestBase {
	@Test
	void connectRequiresValidJwt() throws Exception {
		Handler none = connect(null);
		assertNotNull(none.error.get(5, TimeUnit.SECONDS));
		assertFalse(none.connected.isDone());

		Handler bad = connect("not-a-jwt");
		assertNotNull(bad.error.get(5, TimeUnit.SECONDS));
		assertFalse(bad.connected.isDone());
	}

	@Test
	void messageEventsReachMembersOnly() throws Exception {
		String sfx = UUID.randomUUID().toString().substring(0, 6);
		String owner = "own_" + sfx, guest = "gst_" + sfx;
		String ot = register(owner), gt = register(guest);
		long ws = call("POST", "/workspaces", ot, "{\"name\":\"WS\"}").get("id").asLong();
		call("POST", "/workspaces/" + ws + "/invite", ot, "{\"username\":\"" + guest + "\"}");
		JsonNode ov = call("GET", "/workspaces/" + ws, ot, null);
		long general = ov.get("channels").get(0).get("id").asLong();
		long priv = call("POST", "/workspaces/" + ws + "/channels", ot, "{\"name\":\"secret\",\"isPrivate\":true}").get("id").asLong();

		// メンバーは購読でき、投稿イベントとサイドバー更新の合図を受け取る
		Handler g = connect(gt);
		StompSession gs = g.connected.get(5, TimeUnit.SECONDS);
		BlockingQueue<Map<String, Object>> topic = subscribe(gs, "/topic/channels/" + general);
		BlockingQueue<Map<String, Object>> overview = subscribe(gs, "/user/queue/overview");
		Thread.sleep(300);
		long msg = call("POST", "/channels/" + general + "/messages", ot, "{\"content\":\"hi\"}").get("id").asLong();

		Map<String, Object> ev = topic.poll(5, TimeUnit.SECONDS);
		assertNotNull(ev);
		assertEquals("created", ev.get("type"));
		assertEquals(msg, ((Number) ev.get("messageId")).longValue());
		Map<String, Object> ovEv = overview.poll(5, TimeUnit.SECONDS);
		assertNotNull(ovEv);
		assertEquals(ws, ((Number) ovEv.get("workspaceId")).longValue());

		// 非メンバーのプライベートチャンネル購読は拒否される
		Handler g2 = connect(gt);
		StompSession gs2 = g2.connected.get(5, TimeUnit.SECONDS);
		subscribe(gs2, "/topic/channels/" + priv);
		assertNotNull(g2.error.get(5, TimeUnit.SECONDS));

		// クライアントからのSENDは拒否される
		Handler g3 = connect(gt);
		StompSession gs3 = g3.connected.get(5, TimeUnit.SECONDS);
		gs3.send("/topic/channels/" + general, Map.of("type", "fake"));
		assertNotNull(g3.error.get(5, TimeUnit.SECONDS));
	}
	@Test
	void profileChangeNotifiesWorkspaceMembersWithProfileType() throws Exception {
		String sfx = UUID.randomUUID().toString().substring(0, 6);
		String owner = "pown_" + sfx, guest = "pgst_" + sfx;
		String ot = register(owner), gt = register(guest);
		long ws = call("POST", "/workspaces", ot, "{\"name\":\"WS\"}").get("id").asLong();
		call("POST", "/workspaces/" + ws + "/invite", ot, "{\"username\":\"" + guest + "\"}");

		Handler g = connect(gt);
		StompSession gs = g.connected.get(5, TimeUnit.SECONDS);
		BlockingQueue<Map<String, Object>> overview = subscribe(gs, "/user/queue/overview");
		Thread.sleep(300);
		overview.clear();
		call("PUT", "/me", ot, "{\"displayName\":\"Renamed\",\"status\":\"\"}");

		Map<String, Object> ev = overview.poll(5, TimeUnit.SECONDS);
		assertNotNull(ev);
		assertEquals("profile", ev.get("type"));
		assertEquals(ws, ((Number) ev.get("workspaceId")).longValue());
	}
}
