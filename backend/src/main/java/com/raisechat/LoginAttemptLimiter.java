package com.raisechat;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * ログインの失敗回数の制限(総当たり攻撃への対策)。メモリ上で管理する。
 * <ul>
 * <li>ユーザーID+IPごと: window内に maxFailures 回失敗すると、その期間は拒否する</li>
 * <li>IPごと: 別のユーザーIDを順に試す攻撃への対策として、ipMaxFailures 回で拒否する</li>
 * </ul>
 * ユーザーIDが存在するかどうかで挙動を変えない(存在しないIDの失敗も同じように数える)。
 * 成功したら、そのユーザーID+IPの失敗回数だけをリセットする(IPごとの回数は残す)。
 */
@Component
public class LoginAttemptLimiter {
	/** 鍵に使うユーザーIDの最大長(極端に長い入力でメモリを使わせないため) */
	private static final int MAX_KEY_USERNAME = 64;

	private final int maxFailures;
	private final int ipMaxFailures;
	private final long windowMs;
	private final Clock clock;
	private final Map<String, Deque<Long>> failures = new HashMap<>();

	@Autowired
	public LoginAttemptLimiter(@Value("${app.login.max-failures:5}") int maxFailures,
			@Value("${app.login.ip-max-failures:20}") int ipMaxFailures,
			@Value("${app.login.window-minutes:15}") long windowMinutes) {
		this(maxFailures, ipMaxFailures, Duration.ofMinutes(windowMinutes), Clock.systemUTC());
	}

	LoginAttemptLimiter(int maxFailures, int ipMaxFailures, Duration window, Clock clock) {
		this.maxFailures = maxFailures;
		this.ipMaxFailures = ipMaxFailures;
		this.windowMs = window.toMillis();
		this.clock = clock;
	}

	/** 制限中なら 429(Retry-After つき)を投げる */
	public synchronized void check(String username, String ip) {
		long now = clock.millis();
		long wait = Math.max(waitMs(userKey(username, ip), maxFailures, now), waitMs(ipKey(ip), ipMaxFailures, now));
		if (wait > 0) {
			long seconds = (wait + 999) / 1000;
			long minutes = Math.max(1, (seconds + 59) / 60);
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
					"ログインの試行回数が多すぎます。" + minutes + "分ほど待ってからもう一度お試しください",
					Map.of("Retry-After", String.valueOf(seconds)));
		}
	}

	public synchronized void recordFailure(String username, String ip) {
		long now = clock.millis();
		purgeExpired(now);
		failures.computeIfAbsent(userKey(username, ip), k -> new ArrayDeque<>()).addLast(now);
		failures.computeIfAbsent(ipKey(ip), k -> new ArrayDeque<>()).addLast(now);
	}

	public synchronized void recordSuccess(String username, String ip) {
		failures.remove(userKey(username, ip));
	}

	/** 制限を解除できるまでの残りミリ秒。制限中でなければ0 */
	private long waitMs(String key, int max, long now) {
		Deque<Long> times = failures.get(key);
		if (times == null) {
			return 0;
		}
		while (!times.isEmpty() && now - times.peekFirst() >= windowMs) {
			times.pollFirst();
		}
		if (times.size() < max) {
			return 0;
		}
		// 古い方から (size - max + 1) 番目の失敗が期間外になれば、max未満に戻る
		long expiring = times.stream().skip(times.size() - max).findFirst().orElseThrow();
		return expiring + windowMs - now;
	}

	private void purgeExpired(long now) {
		failures.values().removeIf(times -> times.isEmpty() || now - times.peekLast() >= windowMs);
	}

	private static String userKey(String username, String ip) {
		String u = username.toLowerCase(Locale.ROOT);
		return "u:" + (u.length() > MAX_KEY_USERNAME ? u.substring(0, MAX_KEY_USERNAME) : u) + "|" + ip;
	}

	private static String ipKey(String ip) {
		return "i:" + ip;
	}
}
