package com.raisechat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 複数サーバー用(app.cluster.mode=redis)。全サーバーの接続を、Redisにまとめて記録する。
 * <ul>
 * <li>ユーザーごとのソート済みセット(メンバー=サーバーID+セッションID、スコア=有効期限)に、接続を記録する</li>
 * <li>「オンライン」のセットに、今オンラインのユーザーを入れる。入れたとき・外したときだけ true を返すので、
 * 切り替えの通知は、複数のサーバーが同時に気づいても、1回だけになる(Luaスクリプトで原子的に行う)</li>
 * <li>各サーバーが、自分の接続の有効期限を定期的に延ばす。サーバーが落ちると、接続は期限で消え、定期処理で、オフラインにされる</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "app.cluster.mode", havingValue = "redis")
public class RedisPresenceStore implements PresenceStore {
	private static final Logger log = LoggerFactory.getLogger(RedisPresenceStore.class);

	/** KEYS: ユーザーの接続, オンラインのセット / ARGV: メンバー, 有効期間(ms), ユーザーID。期限切れを捨てて、接続を追加し、新しくオンラインになったら1 */
	private static final DefaultRedisScript<Long> CONNECT = new DefaultRedisScript<>("""
			local t = redis.call('TIME')
			local now = t[1] * 1000 + math.floor(t[2] / 1000)
			redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
			redis.call('ZADD', KEYS[1], now + tonumber(ARGV[2]), ARGV[1])
			redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[2]) * 2)
			return redis.call('SADD', KEYS[2], ARGV[3])
			""", Long.class);

	/** KEYS: ユーザーの接続, オンラインのセット / ARGV: ユーザーID。有効な接続がなければオンラインから外し、外したら1 */
	private static final DefaultRedisScript<Long> MARK_OFFLINE = new DefaultRedisScript<>("""
			local t = redis.call('TIME')
			local now = t[1] * 1000 + math.floor(t[2] / 1000)
			redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
			if redis.call('ZCARD', KEYS[1]) == 0 then
			  return redis.call('SREM', KEYS[2], ARGV[1])
			end
			return 0
			""", Long.class);

	private final StringRedisTemplate redis;
	private final String instanceId = UUID.randomUUID().toString();
	private final String prefix;
	private final long ttlMs;
	private final long heartbeatMs;
	/** このサーバーに繋がっているセッション(セッションID → ユーザーID) */
	private final Map<String, Long> localSessions = new ConcurrentHashMap<>();

	public RedisPresenceStore(StringRedisTemplate redis, @Value("${app.cluster.key-prefix:raisechat}") String prefix,
			@Value("${app.cluster.presence-ttl-ms:30000}") long ttlMs,
			@Value("${app.cluster.presence-heartbeat-ms:10000}") long heartbeatMs) {
		this.redis = redis;
		this.prefix = prefix;
		this.ttlMs = ttlMs;
		this.heartbeatMs = heartbeatMs;
	}

	private String userKey(long uid) {
		return prefix + ":presence:user:" + uid;
	}

	private String onlineKey() {
		return prefix + ":presence:online";
	}

	private String member(String sessionId) {
		return instanceId + ":" + sessionId;
	}

	private boolean runConnect(long userId, String sessionId) {
		Long added = redis.execute(CONNECT, List.of(userKey(userId), onlineKey()), member(sessionId), String.valueOf(ttlMs),
				String.valueOf(userId));
		return added != null && added == 1L;
	}

	private boolean runMarkOffline(long userId) {
		Long removed = redis.execute(MARK_OFFLINE, List.of(userKey(userId), onlineKey()), String.valueOf(userId));
		return removed != null && removed == 1L;
	}

	@Override
	public boolean connect(long userId, String sessionId) {
		localSessions.put(sessionId, userId);
		return runConnect(userId, sessionId);
	}

	@Override
	public Long disconnect(String sessionId) {
		Long uid = localSessions.remove(sessionId);
		if (uid != null) {
			redis.opsForZSet().remove(userKey(uid), member(sessionId));
		}
		return uid;
	}

	@Override
	public boolean markOfflineIfNoConnection(long userId) {
		return runMarkOffline(userId);
	}

	@Override
	public boolean isOnline(long userId) {
		return Boolean.TRUE.equals(redis.opsForSet().isMember(onlineKey(), String.valueOf(userId)));
	}

	@Override
	public List<Long> onlineAmong(Collection<Long> userIds) {
		if (userIds.isEmpty()) {
			return List.of();
		}
		Object[] ids = userIds.stream().map(String::valueOf).toArray();
		Map<Object, Boolean> result = redis.opsForSet().isMember(onlineKey(), ids);
		List<Long> online = new ArrayList<>();
		if (result != null) {
			for (Long id : userIds) {
				if (Boolean.TRUE.equals(result.get(String.valueOf(id)))) {
					online.add(id);
				}
			}
		}
		return online;
	}

	@Override
	public long maintenanceIntervalMs() {
		return heartbeatMs;
	}

	@Override
	public List<Long> heartbeat() {
		List<Long> backOnline = new ArrayList<>();
		localSessions.forEach((sessionId, uid) -> {
			try {
				if (runConnect(uid, sessionId)) {
					backOnline.add(uid);
				}
			} catch (RuntimeException e) {
				log.warn("接続の有効期限を延ばせなかった: {}", e.toString());
			}
		});
		return backOnline;
	}

	@Override
	public List<Long> sweep() {
		List<Long> wentOffline = new ArrayList<>();
		Set<String> online = redis.opsForSet().members(onlineKey());
		if (online != null) {
			for (String id : online) {
				long uid = Long.parseLong(id);
				if (runMarkOffline(uid)) {
					wentOffline.add(uid);
				}
			}
		}
		return wentOffline;
	}
}
