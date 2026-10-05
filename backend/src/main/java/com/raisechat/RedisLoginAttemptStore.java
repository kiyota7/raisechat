package com.raisechat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

/**
 * 複数サーバー用(app.cluster.mode=redis)。ログインの失敗の時刻を、Redisのソート済みセット
 * (スコア=時刻)に記録し、全サーバーの失敗をまとめて数える。
 * Redisに繋がらないときは、ログインを止めないよう、制限をかけずに続ける(警告をログに出す)。
 */
@Component
@ConditionalOnProperty(name = "app.cluster.mode", havingValue = "redis")
public class RedisLoginAttemptStore implements LoginAttemptStore {
	private static final Logger log = LoggerFactory.getLogger(RedisLoginAttemptStore.class);

	private final StringRedisTemplate redis;
	private final String prefix;

	public RedisLoginAttemptStore(StringRedisTemplate redis, @Value("${app.cluster.key-prefix:raisechat}") String prefix) {
		this.redis = redis;
		this.prefix = prefix;
	}

	private String redisKey(String key) {
		return prefix + ":login:" + key;
	}

	@Override
	public List<Long> recent(String key, long nowMs, long windowMs) {
		try {
			redis.opsForZSet().removeRangeByScore(redisKey(key), Double.NEGATIVE_INFINITY, nowMs - windowMs);
			Set<ZSetOperations.TypedTuple<String>> entries = redis.opsForZSet().rangeWithScores(redisKey(key), 0, -1);
			List<Long> times = new ArrayList<>();
			if (entries != null) {
				for (ZSetOperations.TypedTuple<String> t : entries) {
					if (t.getScore() != null) {
						times.add(t.getScore().longValue());
					}
				}
			}
			return times;
		} catch (RuntimeException e) {
			log.warn("ログイン失敗の記録を読めなかった(制限をかけずに続ける): {}", e.toString());
			return List.of();
		}
	}

	@Override
	public void add(String key, long nowMs, long windowMs) {
		try {
			String k = redisKey(key);
			redis.opsForZSet().removeRangeByScore(k, Double.NEGATIVE_INFINITY, nowMs - windowMs);
			redis.opsForZSet().add(k, nowMs + "-" + UUID.randomUUID(), nowMs);
			redis.expire(k, Duration.ofMillis(windowMs));
		} catch (RuntimeException e) {
			log.warn("ログイン失敗を記録できなかった: {}", e.toString());
		}
	}

	@Override
	public void remove(String key) {
		try {
			redis.delete(redisKey(key));
		} catch (RuntimeException e) {
			log.warn("ログイン失敗の記録を消せなかった: {}", e.toString());
		}
	}
}
