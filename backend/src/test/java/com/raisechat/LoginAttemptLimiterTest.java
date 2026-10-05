package com.raisechat;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LoginAttemptLimiterTest {
	/** テストから進められる時計 */
	static class MutableClock extends Clock {
		long now = 1_000_000_000L;

		void advance(Duration d) {
			now += d.toMillis();
		}

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return Instant.ofEpochMilli(now);
		}
	}

	private final MutableClock clock = new MutableClock();
	// ユーザーID+IPごとに3回 / IPごとに5回 / 10分
	private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(3, 5, Duration.ofMinutes(10), clock);

	private void fail(String user, String ip) {
		limiter.check(user, ip);
		limiter.recordFailure(user, ip);
	}

	private static ApiException blocked(Runnable r) {
		return assertThrows(ApiException.class, r::run);
	}

	@Test
	void blocksAfterMaxFailuresWithRetryAfter() {
		fail("alice", "1.1.1.1");
		fail("alice", "1.1.1.1");
		assertDoesNotThrow(() -> limiter.check("alice", "1.1.1.1")); // 2回まではまだ大丈夫
		fail("alice", "1.1.1.1");

		ApiException e = blocked(() -> limiter.check("alice", "1.1.1.1"));
		assertEquals(429, e.getStatus().value());
		assertEquals("600", e.getHeaders().get("Retry-After"));
		assertTrue(e.getMessage().contains("10分"));

		clock.advance(Duration.ofMinutes(4));
		assertEquals("360", blocked(() -> limiter.check("alice", "1.1.1.1")).getHeaders().get("Retry-After"));

		clock.advance(Duration.ofMinutes(6)); // 最初の失敗から10分
		assertDoesNotThrow(() -> limiter.check("alice", "1.1.1.1"));
	}

	@Test
	void countsFailuresInASlidingWindow() {
		fail("alice", "1.1.1.1"); // t=0
		clock.advance(Duration.ofMinutes(4));
		fail("alice", "1.1.1.1"); // t=4
		clock.advance(Duration.ofMinutes(4));
		fail("alice", "1.1.1.1"); // t=8 → 3回で制限
		assertEquals("120", blocked(() -> limiter.check("alice", "1.1.1.1")).getHeaders().get("Retry-After"));

		clock.advance(Duration.ofMinutes(2)); // t=10: 最初の失敗が期間外になり、2回に戻る
		assertDoesNotThrow(() -> limiter.check("alice", "1.1.1.1"));
	}

	@Test
	void successResetsOnlyThatUsersCount() {
		fail("alice", "1.1.1.1");
		fail("alice", "1.1.1.1");
		limiter.recordSuccess("alice", "1.1.1.1");
		fail("alice", "1.1.1.1");
		fail("alice", "1.1.1.1");
		assertDoesNotThrow(() -> limiter.check("alice", "1.1.1.1")); // リセットされたので、まだ2回
		fail("alice", "1.1.1.1");
		blocked(() -> limiter.check("alice", "1.1.1.1"));
	}

	@Test
	void ipLimitCoversManyDifferentUsernames() {
		for (String u : new String[] { "u1", "u2", "u3", "u4", "u5" }) {
			fail(u, "9.9.9.9"); // ユーザーIDごとには1回ずつだが、IPでは5回
		}
		assertEquals(429, blocked(() -> limiter.check("another", "9.9.9.9")).getStatus().value());
		assertDoesNotThrow(() -> limiter.check("another", "8.8.8.8")); // 別のIPは影響を受けない
	}

	@Test
	void ipsAreIndependentForTheSameUsername() {
		fail("alice", "1.1.1.1");
		fail("alice", "1.1.1.1");
		fail("alice", "1.1.1.1");
		blocked(() -> limiter.check("alice", "1.1.1.1"));
		assertDoesNotThrow(() -> limiter.check("alice", "2.2.2.2"));
	}

	@Test
	void usernameIsCaseInsensitiveAndLengthIsBounded() {
		fail("Alice", "1.1.1.1");
		fail("ALICE", "1.1.1.1");
		fail("alice", "1.1.1.1");
		blocked(() -> limiter.check("aLiCe", "1.1.1.1"));

		// 極端に長いユーザーIDでも、鍵の長さを抑えて同じものとして数える
		String longName = "x".repeat(1000);
		fail(longName, "3.3.3.3");
		fail(longName + "y", "3.3.3.3");
		fail(longName + "z", "3.3.3.3");
		blocked(() -> limiter.check(longName, "3.3.3.3"));
	}

	@Test
	void expiredEntriesAreForgotten() {
		fail("alice", "1.1.1.1");
		clock.advance(Duration.ofMinutes(11));
		fail("bob", "2.2.2.2"); // 記録のたびに、期限切れの項目を捨てる
		clock.advance(Duration.ofMinutes(1));
		assertDoesNotThrow(() -> limiter.check("alice", "1.1.1.1"));
	}
}
