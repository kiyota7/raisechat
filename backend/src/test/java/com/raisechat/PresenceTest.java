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
		properties = { "spring.datasource.url=jdbc:sqlite:target/test-${random.uuid}.db?foreign_keys=true", "app.presence.offline-grace-ms=300" })
class PresenceTest extends StompTestBase {
	/** 指定ユーザーの変化(online=true/false)を待つ。他ユーザーの合図は読み飛ばす */
	private static Map<String, Object> awaitPresence(BlockingQueue<Map<String, Object>> q, long userId, boolean online, long timeoutMs)
			throws InterruptedException {
		long end = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < end) {
			Map<String, Object> ev = q.poll(end - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
			if (ev != null && ((Number) ev.get("userId")).longValue() == userId && Boolean.valueOf(online).equals(ev.get("online"))) {
				return ev;
			}
		}
		return null;
	}

	@Test
	void onlineStateReachesWorkspaceMembersOnly() throws Exception {
		String sfx = UUID.randomUUID().toString().substring(0, 6);
		String ot = register("pro_" + sfx), mt = register("prm_" + sfx), xt = register("prx_" + sfx);
		long ownerId = userId(ot);
		long ws = call("POST", "/workspaces", ot, "{\"name\":\"WS\"}").get("id").asLong();
		call("POST", "/workspaces/" + ws + "/invite", ot, "{\"username\":\"prm_" + sfx + "\"}");

		StompSession member = connect(mt).connected.get(5, TimeUnit.SECONDS);
		BlockingQueue<Map<String, Object>> memberQ = subscribe(member, "/user/queue/presence");
		StompSession stranger = connect(xt).connected.get(5, TimeUnit.SECONDS);
		BlockingQueue<Map<String, Object>> strangerQ = subscribe(stranger, "/user/queue/presence");
		Thread.sleep(300);

		// オーナーが接続 → 同じワークスペースのメンバーにだけ online=true が届き、概要APIにも反映される
		StompSession owner = connect(ot).connected.get(5, TimeUnit.SECONDS);
		assertNotNull(awaitPresence(memberQ, ownerId, true, 5000));
		assertNull(awaitPresence(strangerQ, ownerId, true, 800), "ワークスペース外のユーザーには届かない");
		JsonNode ov = call("GET", "/workspaces/" + ws, mt, null);
		assertTrue(ov.get("onlineUserIds").toString().contains(String.valueOf(ownerId)));

		// 切断 → 猶予のあとオフラインになる
		owner.disconnect();
		assertNotNull(awaitPresence(memberQ, ownerId, false, 5000));
		JsonNode ov2 = call("GET", "/workspaces/" + ws, mt, null);
		assertFalse(ov2.get("onlineUserIds").toString().contains(String.valueOf(ownerId)));
	}

	@Test
	void staysOnlineUntilLastConnectionCloses() throws Exception {
		String sfx = UUID.randomUUID().toString().substring(0, 6);
		String ot = register("pso_" + sfx), mt = register("psm_" + sfx);
		long ownerId = userId(ot);
		long ws = call("POST", "/workspaces", ot, "{\"name\":\"WS\"}").get("id").asLong();
		call("POST", "/workspaces/" + ws + "/invite", ot, "{\"username\":\"psm_" + sfx + "\"}");

		StompSession member = connect(mt).connected.get(5, TimeUnit.SECONDS);
		BlockingQueue<Map<String, Object>> memberQ = subscribe(member, "/user/queue/presence");
		Thread.sleep(300);

		StompSession tab1 = connect(ot).connected.get(5, TimeUnit.SECONDS);
		StompSession tab2 = connect(ot).connected.get(5, TimeUnit.SECONDS);
		assertNotNull(awaitPresence(memberQ, ownerId, true, 5000));

		tab1.disconnect();
		assertNull(awaitPresence(memberQ, ownerId, false, 1200), "もう1つの接続が残っている間はオンライン");

		tab2.disconnect();
		assertNotNull(awaitPresence(memberQ, ownerId, false, 5000));
	}

	@Test
	void reconnectWithinGraceKeepsOnline() throws Exception {
		String sfx = UUID.randomUUID().toString().substring(0, 6);
		String ot = register("prc_" + sfx), mt = register("prd_" + sfx);
		long ownerId = userId(ot);
		long ws = call("POST", "/workspaces", ot, "{\"name\":\"WS\"}").get("id").asLong();
		call("POST", "/workspaces/" + ws + "/invite", ot, "{\"username\":\"prd_" + sfx + "\"}");

		StompSession member = connect(mt).connected.get(5, TimeUnit.SECONDS);
		BlockingQueue<Map<String, Object>> memberQ = subscribe(member, "/user/queue/presence");
		Thread.sleep(300);

		StompSession first = connect(ot).connected.get(5, TimeUnit.SECONDS);
		assertNotNull(awaitPresence(memberQ, ownerId, true, 5000));
		first.disconnect();
		connect(ot).connected.get(5, TimeUnit.SECONDS); // ページの再読み込み相当(猶予内に再接続)
		assertNull(awaitPresence(memberQ, ownerId, false, 1200), "猶予内の再接続ではオフラインにならない");
	}

	@Test
	void typingReachesChannelMembersOnly() throws Exception {
		String sfx = UUID.randomUUID().toString().substring(0, 6);
		String ot = register("pto_" + sfx), mt = register("ptm_" + sfx), xt = register("ptx_" + sfx);
		long ownerId = userId(ot);
		long ws = call("POST", "/workspaces", ot, "{\"name\":\"WS\"}").get("id").asLong();
		call("POST", "/workspaces/" + ws + "/invite", ot, "{\"username\":\"ptm_" + sfx + "\"}");
		long general = call("GET", "/workspaces/" + ws, ot, null).get("channels").get(0).get("id").asLong();

		StompSession member = connect(mt).connected.get(5, TimeUnit.SECONDS);
		BlockingQueue<Map<String, Object>> typingQ = subscribe(member, "/topic/channels/" + general + "/typing");
		BlockingQueue<Map<String, Object>> msgQ = subscribe(member, "/topic/channels/" + general);
		Thread.sleep(300);

		// メンバーの入力中は届く。送信者IDは認証済みユーザーから付く(クライアントが偽装できない)
		StompSession owner = connect(ot).connected.get(5, TimeUnit.SECONDS);
		owner.send("/app/typing", Map.of("channelId", general, "userId", 99999));
		Map<String, Object> ev = typingQ.poll(5, TimeUnit.SECONDS);
		assertNotNull(ev);
		assertEquals(ownerId, ((Number) ev.get("userId")).longValue());
		assertNull(msgQ.poll(800, TimeUnit.MILLISECONDS), "入力中の通知はメッセージ用の宛先には流れない");

		// チャンネルのメンバーでないユーザーの入力中は捨てられる
		StompSession stranger = connect(xt).connected.get(5, TimeUnit.SECONDS);
		stranger.send("/app/typing", Map.of("channelId", general));
		assertNull(typingQ.poll(1, TimeUnit.SECONDS));

		// 非メンバーは入力中の宛先も購読できない
		Handler g = connect(xt);
		StompSession gs = g.connected.get(5, TimeUnit.SECONDS);
		subscribe(gs, "/topic/channels/" + general + "/typing");
		assertNotNull(g.error.get(5, TimeUnit.SECONDS));

		// /app/typing 以外へのSENDは引き続き拒否される
		Handler h = connect(mt);
		StompSession hs = h.connected.get(5, TimeUnit.SECONDS);
		hs.send("/topic/channels/" + general + "/typing", Map.of("userId", ownerId));
		assertNotNull(h.error.get(5, TimeUnit.SECONDS));
	}
}
