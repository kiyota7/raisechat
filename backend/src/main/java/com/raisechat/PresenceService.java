package com.raisechat;

import jakarta.annotation.PreDestroy;
import java.security.Principal;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * STOMP接続の有無からオンライン状態を管理する(メモリ上)。
 * 複数タブ/複数接続は、最後の接続が切れるまでオンラインとする。
 * ページ再読み込みなどの一瞬の切断で表示が点滅しないよう、オフラインへの変更は猶予時間だけ遅らせる。
 */
@Component
public class PresenceService {
	private final SimpMessagingTemplate template;
	private final JdbcTemplate jdbc;
	private final long offlineGraceMs;
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "presence-offline");
		t.setDaemon(true);
		return t;
	});

	private final Map<String, Long> sessionUsers = new HashMap<>();
	private final Map<Long, Set<String>> sessions = new HashMap<>();
	private final Set<Long> online = new HashSet<>();
	private final Map<Long, ScheduledFuture<?>> pendingOffline = new HashMap<>();

	public PresenceService(SimpMessagingTemplate template, JdbcTemplate jdbc,
			@Value("${app.presence.offline-grace-ms:3000}") long offlineGraceMs) {
		this.template = template;
		this.jdbc = jdbc;
		this.offlineGraceMs = offlineGraceMs;
	}

	@EventListener
	public void onConnected(SessionConnectedEvent event) {
		Principal user = event.getUser();
		String sessionId = StompHeaderAccessor.wrap(event.getMessage()).getSessionId();
		if (user == null || sessionId == null) {
			return;
		}
		long uid = Long.parseLong(user.getName());
		boolean changed;
		synchronized (this) {
			sessionUsers.put(sessionId, uid);
			sessions.computeIfAbsent(uid, k -> new LinkedHashSet<>()).add(sessionId);
			ScheduledFuture<?> pending = pendingOffline.remove(uid);
			if (pending != null) {
				pending.cancel(false);
			}
			changed = online.add(uid);
		}
		if (changed) {
			broadcast(uid, true);
		}
	}

	@EventListener
	public void onDisconnected(SessionDisconnectEvent event) {
		String sessionId = event.getSessionId();
		synchronized (this) {
			Long uid = sessionUsers.remove(sessionId);
			if (uid == null) {
				return;
			}
			Set<String> set = sessions.get(uid);
			if (set != null) {
				set.remove(sessionId);
				if (set.isEmpty()) {
					sessions.remove(uid);
					pendingOffline.put(uid, scheduler.schedule(() -> goOffline(uid), offlineGraceMs, TimeUnit.MILLISECONDS));
				}
			}
		}
	}

	private void goOffline(long uid) {
		boolean changed;
		synchronized (this) {
			pendingOffline.remove(uid);
			if (sessions.containsKey(uid)) {
				return;
			}
			changed = online.remove(uid);
		}
		if (changed) {
			broadcast(uid, false);
		}
	}

	public synchronized boolean isOnline(long userId) {
		return online.contains(userId);
	}

	/** 渡されたユーザーのうち、オンラインのIDだけを返す */
	public synchronized List<Long> onlineAmong(Collection<Long> userIds) {
		return userIds.stream().filter(online::contains).toList();
	}

	/** 同じワークスペースに所属するメンバーにだけ、状態の変化を伝える */
	private void broadcast(long uid, boolean isOnline) {
		List<Long> peers = jdbc.queryForList(
				"SELECT DISTINCT m2.user_id FROM workspace_members m1 JOIN workspace_members m2 ON m2.workspace_id = m1.workspace_id WHERE m1.user_id = ?",
				Long.class, uid);
		Map<String, Object> payload = Map.of("userId", uid, "online", isOnline);
		for (long peer : peers) {
			template.convertAndSendToUser(String.valueOf(peer), "/queue/presence", payload);
		}
	}

	@PreDestroy
	void shutdown() {
		scheduler.shutdownNow();
	}
}
