package com.raisechat;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** サーバー1台用(既定)。接続のセッションを、メモリ上で管理する。複数接続(複数タブ)は、最後の接続が切れるまでオンライン */
@Component
@ConditionalOnProperty(name = "app.cluster.mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryPresenceStore implements PresenceStore {
	private final Map<String, Long> sessionUsers = new HashMap<>();
	private final Map<Long, Set<String>> sessions = new HashMap<>();
	private final Set<Long> online = new HashSet<>();

	@Override
	public synchronized boolean connect(long userId, String sessionId) {
		sessionUsers.put(sessionId, userId);
		sessions.computeIfAbsent(userId, k -> new LinkedHashSet<>()).add(sessionId);
		return online.add(userId);
	}

	@Override
	public synchronized Long disconnect(String sessionId) {
		Long uid = sessionUsers.remove(sessionId);
		if (uid == null) {
			return null;
		}
		Set<String> set = sessions.get(uid);
		if (set != null) {
			set.remove(sessionId);
			if (set.isEmpty()) {
				sessions.remove(uid);
			}
		}
		return uid;
	}

	@Override
	public synchronized boolean markOfflineIfNoConnection(long userId) {
		if (sessions.containsKey(userId)) {
			return false;
		}
		return online.remove(userId);
	}

	@Override
	public synchronized boolean isOnline(long userId) {
		return online.contains(userId);
	}

	@Override
	public synchronized List<Long> onlineAmong(Collection<Long> userIds) {
		return userIds.stream().filter(online::contains).toList();
	}
}
