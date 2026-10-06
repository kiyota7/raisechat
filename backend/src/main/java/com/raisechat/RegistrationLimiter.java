package com.raisechat;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 登録APIのIPごとの回数制限(大量のアカウント作成や、パスワードのハッシュ化による負荷への対策)。
 * 成功・失敗を問わず、試行を1回として数える。記録先はログインの制限と同じ LoginAttemptStore
 * (1台ならメモリ、複数台ならRedis)で、鍵の頭が "r:" なのでログインの回数とは混ざらない。
 */
@Component
public class RegistrationLimiter {
	private final int maxPerIp;
	private final long windowMs;
	private final LoginAttemptStore store;
	private final Clock clock = Clock.systemUTC();

	public RegistrationLimiter(@Value("${app.register.max-per-ip:10}") int maxPerIp,
			@Value("${app.register.window-minutes:60}") long windowMinutes, LoginAttemptStore store) {
		this.maxPerIp = maxPerIp;
		this.windowMs = windowMinutes * 60_000;
		this.store = store;
	}

	/** 試行を1回記録する。上限を超えていたら 429(Retry-After つき)を投げる */
	public synchronized void attempt(String ip) {
		long now = clock.millis();
		String key = "r:" + ip;
		List<Long> times = store.recent(key, now, windowMs);
		if (times.size() >= maxPerIp) {
			long seconds = (times.get(times.size() - maxPerIp) + windowMs - now + 999) / 1000;
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
					"登録の試行回数が多すぎます。しばらく待ってからもう一度お試しください",
					Map.of("Retry-After", String.valueOf(Math.max(1, seconds))));
		}
		store.add(key, now, windowMs);
	}
}
