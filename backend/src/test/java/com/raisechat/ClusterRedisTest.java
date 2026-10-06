package com.raisechat;

import static com.raisechat.StompSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.stomp.StompSession;

/**
 * 複数サーバー(app.cluster.mode=redis)のテスト。同じJVMの中で、サーバーを2台(別ポート)起動し、
 * 同じRedisと同じDBに繋ぐ。Redisに繋がらない環境(ローカルで未起動など)では、実行を飛ばす。
 * Redisの接続先は、環境変数 REDIS_HOST / REDIS_PORT(既定 localhost:6379)。
 */
class ClusterRedisTest {
	private static final String HOST = System.getenv().getOrDefault("REDIS_HOST", "localhost");
	private static final int REDIS_PORT = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));

	static ConfigurableApplicationContext ctxA, ctxB;
	static int portA, portB;
	static String prefix;

	static boolean redisAvailable() {
		try (Socket s = new Socket()) {
			s.connect(new InetSocketAddress(HOST, REDIS_PORT), 500);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * サーバーを起動する。設定は、application.properties より優先されるよう、コマンドライン引数の形で渡す
	 * (SpringApplicationBuilder.properties() の設定は優先度が低く、server.port=8080 に負ける)
	 */
	static ConfigurableApplicationContext run(String... settings) {
		String[] args = new String[settings.length + 2];
		args[0] = "--server.port=0";
		args[1] = "--spring.main.banner-mode=off";
		for (int i = 0; i < settings.length; i++) {
			args[i + 2] = "--" + settings[i];
		}
		return new SpringApplicationBuilder(RaiseChatApplication.class).run(args);
	}

	static ConfigurableApplicationContext start(String db) {
		List<String> settings = new ArrayList<>(TestDb.settings(db));
		settings.addAll(List.of("app.upload-dir=target/cluster-uploads", "app.cluster.mode=redis", "app.cluster.key-prefix=" + prefix,
				"spring.data.redis.host=" + HOST, "spring.data.redis.port=" + REDIS_PORT,
				"app.cluster.presence-ttl-ms=3000", "app.cluster.presence-heartbeat-ms=500", "app.presence.offline-grace-ms=300",
				"app.login.max-failures=5", "app.login.ip-max-failures=1000"));
		return run(settings.toArray(new String[0]));
	}

	static int portOf(ConfigurableApplicationContext c) {
		return Integer.parseInt(c.getEnvironment().getProperty("local.server.port"));
	}

	@BeforeAll
	static void startTwoServers() {
		org.junit.jupiter.api.Assumptions.assumeTrue(redisAvailable(), "Redisに繋がらないため、複数サーバーのテストを飛ばす(" + HOST + ":" + REDIS_PORT + ")");
		prefix = "test-" + UUID.randomUUID().toString().substring(0, 8);
		String db = "target/cluster-" + UUID.randomUUID() + ".db";
		ctxA = start(db);
		ctxB = start(db);
		portA = portOf(ctxA);
		portB = portOf(ctxB);
	}

	@AfterAll
	static void stopServers() {
		if (ctxB != null) ctxB.close();
		if (ctxA != null) ctxA.close();
	}

	private static String uniq(String p) {
		return p + "_" + UUID.randomUUID().toString().substring(0, 6);
	}

	/** オーナーとゲストが入ったワークスペース。登録・作成はサーバーAで行う */
	record Space(User owner, User guest, long ws, long general) {
	}

	private Space space() throws Exception {
		User owner = register(portA, uniq("own")), guest = register(portA, uniq("gst"));
		long ws = ok(portA, "POST", "/workspaces", owner.token(), "{\"name\":\"WS\"}").get("id").asLong();
		ok(portA, "POST", "/workspaces/" + ws + "/invite", owner.token(), "{\"username\":\"" + guest.name() + "\"}");
		long general = ok(portA, "GET", "/workspaces/" + ws, owner.token(), null).get("channels").get(0).get("id").asLong();
		return new Space(owner, guest, ws, general);
	}

	private static Map<String, Object> next(BlockingQueue<Map<String, Object>> q, long seconds) throws InterruptedException {
		return q.poll(seconds, TimeUnit.SECONDS);
	}

	private static boolean sawPresence(BlockingQueue<Map<String, Object>> q, long userId, boolean online, long seconds) throws InterruptedException {
		long end = System.currentTimeMillis() + seconds * 1000;
		while (System.currentTimeMillis() < end) {
			Map<String, Object> ev = q.poll(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
			if (ev != null && ((Number) ev.get("userId")).longValue() == userId && Boolean.valueOf(online).equals(ev.get("online"))) {
				return true;
			}
		}
		return false;
	}

	@Test
	void eventsReachClientsConnectedToTheOtherServer() throws Exception {
		Space s = space();
		// ゲストはサーバーBに接続して購読し、オーナーの投稿はサーバーAに送る(逆も)
		StompSession onB = connect(portB, s.guest().token());
		BlockingQueue<Map<String, Object>> topicB = subscribe(onB, "/topic/channels/" + s.general());
		BlockingQueue<Map<String, Object>> overviewB = subscribe(onB, "/user/queue/overview");
		StompSession onA = connect(portA, s.owner().token());
		BlockingQueue<Map<String, Object>> topicA = subscribe(onA, "/topic/channels/" + s.general());
		Thread.sleep(500);

		long m1 = ok(portA, "POST", "/channels/" + s.general() + "/messages", s.owner().token(), "{\"content\":\"via A\"}").get("id").asLong();
		Map<String, Object> ev = next(topicB, 5);
		assertNotNull(ev, "サーバーAの投稿が、サーバーBに繋がった人に届く");
		assertEquals("created", ev.get("type"));
		assertEquals(m1, ((Number) ev.get("messageId")).longValue());
		assertNotNull(next(overviewB, 5), "サイドバー更新の合図も届く");
		assertNotNull(next(topicA, 5), "サーバーAに繋がった人にも届く(自分のサーバーも、Redis経由で配信する)");

		long m2 = ok(portB, "POST", "/channels/" + s.general() + "/messages", s.guest().token(), "{\"content\":\"via B\"}").get("id").asLong();
		Map<String, Object> back = next(topicA, 5);
		assertNotNull(back, "サーバーBの投稿が、サーバーAに繋がった人に届く");
		assertEquals(m2, ((Number) back.get("messageId")).longValue());
		assertNull(next(topicA, 1), "同じ通知が重複して届かない");
	}

	@Test
	void typingIsRelayedAcrossServers() throws Exception {
		Space s = space();
		StompSession watcher = connect(portA, s.owner().token());
		BlockingQueue<Map<String, Object>> typing = subscribe(watcher, "/topic/channels/" + s.general() + "/typing");
		StompSession typer = connect(portB, s.guest().token());
		Thread.sleep(500);

		typer.send("/app/typing", Map.of("channelId", s.general()));
		Map<String, Object> ev = next(typing, 5);
		assertNotNull(ev);
		assertEquals(s.guest().id(), ((Number) ev.get("userId")).longValue());
	}

	@Test
	void presenceIsSharedAndOfflineIsAnnouncedOnlyOnce() throws Exception {
		Space s = space();
		StompSession watcher = connect(portA, s.owner().token());
		BlockingQueue<Map<String, Object>> presence = subscribe(watcher, "/user/queue/presence");
		Thread.sleep(500);
		presence.clear();

		// ゲストが、サーバーAとBの両方に接続する
		StompSession g1 = connect(portB, s.guest().token());
		assertTrue(sawPresence(presence, s.guest().id(), true, 5), "別のサーバーでの接続が、オンラインとして伝わる");
		StompSession g2 = connect(portA, s.guest().token());
		assertFalse(sawPresence(presence, s.guest().id(), true, 1), "2つ目の接続では、オンラインを重ねて知らせない");
		assertTrue(ok(portA, "GET", "/workspaces/" + s.ws(), s.owner().token(), null).get("onlineUserIds").toString().contains(String.valueOf(s.guest().id())));

		// 片方を切っても、もう片方が残っている間はオンライン
		g1.disconnect();
		assertFalse(sawPresence(presence, s.guest().id(), false, 2), "別のサーバーの接続が残っているので、オフラインにならない");

		// 両方のサーバーの接続を、ほぼ同時に切る → オフラインの通知は、1回だけ
		StompSession g3 = connect(portB, s.guest().token());
		presence.clear();
		Thread t1 = new Thread(g2::disconnect), t2 = new Thread(g3::disconnect);
		t1.start();
		t2.start();
		t1.join();
		t2.join();
		assertTrue(sawPresence(presence, s.guest().id(), false, 5), "全部切れたらオフラインを知らせる");
		assertFalse(sawPresence(presence, s.guest().id(), false, 2), "オフラインの通知が重複しない(切り替えは1回だけ)");
		assertFalse(ok(portA, "GET", "/workspaces/" + s.ws(), s.owner().token(), null).get("onlineUserIds").toString().contains(String.valueOf(s.guest().id())));
	}

	@Test
	void connectionsOfACrashedServerExpireAndTheUserGoesOffline() throws Exception {
		Space s = space();
		StompSession watcher = connect(portA, s.owner().token());
		BlockingQueue<Map<String, Object>> presence = subscribe(watcher, "/user/queue/presence");
		Thread.sleep(500);
		presence.clear();

		// 落ちたサーバーの接続が残った状態を作る: オンラインの記録と、まもなく期限切れになる接続だけを、Redisに直接入れる
		StringRedisTemplate redis = ctxA.getBean(StringRedisTemplate.class);
		long uid = s.guest().id();
		redis.opsForSet().add(prefix + ":presence:online", String.valueOf(uid));
		redis.opsForZSet().add(prefix + ":presence:user:" + uid, "dead-server:s1", System.currentTimeMillis() + 800);

		assertTrue(sawPresence(presence, uid, false, 8), "期限が切れると、残った記録からオフラインにされ、知らされる");
		assertFalse(ok(portB, "GET", "/workspaces/" + s.ws(), s.owner().token(), null).get("onlineUserIds").toString().contains(String.valueOf(uid)));
	}

	@Test
	void loginFailuresAreCountedAcrossServers() throws Exception {
		User u = register(portA, uniq("login"));
		for (int i = 0; i < 3; i++) {
			assertEquals(401, http(portA, "POST", "/auth/login", null, "{\"username\":\"" + u.name() + "\",\"password\":\"wrong-" + i + "\"}").status());
		}
		for (int i = 0; i < 2; i++) {
			assertEquals(401, http(portB, "POST", "/auth/login", null, "{\"username\":\"" + u.name() + "\",\"password\":\"wrong-b" + i + "\"}").status());
		}
		// A で3回 + B で2回 = 5回。どちらのサーバーでも、次は拒否される(正しいパスワードでも)
		assertEquals(429, http(portA, "POST", "/auth/login", null, "{\"username\":\"" + u.name() + "\",\"password\":\"x\"}").status());
		assertEquals(429, http(portB, "POST", "/auth/login", null, "{\"username\":\"" + u.name() + "\",\"password\":\"Cluster-Test-1x\"}").status());
	}
}
