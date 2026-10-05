package com.raisechat;

import java.util.List;

/**
 * ログインの失敗の記録。サーバー1台のときはメモリ(InMemoryLoginAttemptStore)、
 * 複数台のときはRedis(RedisLoginAttemptStore)で、全サーバーの失敗をまとめて数える。
 */
public interface LoginAttemptStore {
	/** key の、期間(window)内の失敗の時刻(ミリ秒)を古い順に返す。期間より前のものは捨てる */
	List<Long> recent(String key, long nowMs, long windowMs);

	/** 失敗を1件記録する。期間を過ぎたものは、あわせて捨てる */
	void add(String key, long nowMs, long windowMs);

	void remove(String key);
}
