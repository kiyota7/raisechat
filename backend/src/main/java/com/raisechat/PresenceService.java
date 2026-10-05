package com.raisechat;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.security.Principal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * STOMP接続の有無からオンライン状態を管理し、変化を同じワークスペースのメンバーに伝える。
 * 状態の保存先は PresenceStore(1台ならメモリ、複数台ならRedis)。
 * ページ再読み込みなどの一瞬の切断で表示が点滅しないよう、オフラインへの変更は猶予時間だけ遅らせる。
 */
@Component
public class PresenceService {
	private static final Logger log = LoggerFactory.getLogger(PresenceService.class);

	private final PresenceStore store;
	private final MessageRelay relay;
	private final JdbcTemplate jdbc;
	private final long offlineGraceMs;
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "presence-offline");
		t.setDaemon(true);
		return t;
	});

	/** このサーバーで、猶予中(再接続を待っている)のユーザー */
	private final Map<Long, ScheduledFuture<?>> pendingOffline = new HashMap<>();

	public PresenceService(PresenceStore store, MessageRelay relay, JdbcTemplate jdbc,
			@Value("${app.presence.offline-grace-ms:3000}") long offlineGraceMs) {
		this.store = store;
		this.relay = relay;
		this.jdbc = jdbc;
		this.offlineGraceMs = offlineGraceMs;
	}

	@PostConstruct
	void startMaintenance() {
		long interval = store.maintenanceIntervalMs();
		if (interval > 0) {
			scheduler.scheduleWithFixedDelay(this::maintain, interval, interval, TimeUnit.MILLISECONDS);
		}
	}

	@EventListener
	public void onConnected(SessionConnectedEvent event) {
		Principal user = event.getUser();
		String sessionId = StompHeaderAccessor.wrap(event.getMessage()).getSessionId();
		if (user == null || sessionId == null) {
			return;
		}
		long uid = Long.parseLong(user.getName());
		synchronized (this) {
			ScheduledFuture<?> pending = pendingOffline.remove(uid);
			if (pending != null) {
				pending.cancel(false);
			}
		}
		if (store.connect(uid, sessionId)) {
			broadcast(uid, true);
		}
	}

	@EventListener
	public void onDisconnected(SessionDisconnectEvent event) {
		Long uid = store.disconnect(event.getSessionId());
		if (uid == null) {
			return;
		}
		// 猶予のあとで、有効な接続が残っていなければオフラインにする(別のサーバーでの再接続も、保存先の判定で分かる)
		synchronized (this) {
			pendingOffline.put(uid, scheduler.schedule(() -> goOffline(uid), offlineGraceMs, TimeUnit.MILLISECONDS));
		}
	}

	private void goOffline(long uid) {
		synchronized (this) {
			pendingOffline.remove(uid);
		}
		if (store.markOfflineIfNoConnection(uid)) {
			broadcast(uid, false);
		}
	}

	/** 定期処理。保存先が共有(Redis)のときだけ動く */
	private void maintain() {
		try {
			store.heartbeat().forEach(uid -> broadcast(uid, true));
			store.sweep().forEach(uid -> broadcast(uid, false));
		} catch (RuntimeException e) {
			log.warn("オンライン状態の定期処理に失敗した: {}", e.toString());
		}
	}

	public boolean isOnline(long userId) {
		return store.isOnline(userId);
	}

	/** 渡されたユーザーのうち、オンラインのIDだけを返す */
	public List<Long> onlineAmong(Collection<Long> userIds) {
		return store.onlineAmong(userIds);
	}

	/** 同じワークスペースに所属するメンバーにだけ、状態の変化を伝える */
	private void broadcast(long uid, boolean isOnline) {
		List<Long> peers = jdbc.queryForList(
				"SELECT DISTINCT m2.user_id FROM workspace_members m1 JOIN workspace_members m2 ON m2.workspace_id = m1.workspace_id WHERE m1.user_id = ?",
				Long.class, uid);
		Map<String, Object> payload = Map.of("userId", uid, "online", isOnline);
		for (long peer : peers) {
			relay.toUser(String.valueOf(peer), "/queue/presence", payload);
		}
	}

	@PreDestroy
	void shutdown() {
		scheduler.shutdownNow();
	}
}
