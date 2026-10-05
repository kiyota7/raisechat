package com.raisechat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** サーバー1台用(既定)。失敗の時刻を、メモリ上で管理する */
@Component
@ConditionalOnProperty(name = "app.cluster.mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryLoginAttemptStore implements LoginAttemptStore {
	private final Map<String, Deque<Long>> failures = new HashMap<>();

	@Override
	public synchronized List<Long> recent(String key, long nowMs, long windowMs) {
		Deque<Long> times = failures.get(key);
		if (times == null) {
			return List.of();
		}
		while (!times.isEmpty() && nowMs - times.peekFirst() >= windowMs) {
			times.pollFirst();
		}
		return new ArrayList<>(times);
	}

	@Override
	public synchronized void add(String key, long nowMs, long windowMs) {
		failures.values().removeIf(times -> times.isEmpty() || nowMs - times.peekLast() >= windowMs);
		failures.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(nowMs);
	}

	@Override
	public synchronized void remove(String key) {
		failures.remove(key);
	}
}
