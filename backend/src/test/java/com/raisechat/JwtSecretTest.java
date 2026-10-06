package com.raisechat;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** 本番相当の構成では、公開されている既定の秘密鍵や短い秘密鍵で起動させない */
class JwtSecretTest {
	private static final String STRONG = "a-very-long-and-random-secret-for-tests-0123456789";

	private static MockEnvironment dev() {
		return new MockEnvironment();
	}

	private static MockEnvironment postgres() {
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles("postgres");
		return env;
	}

	private static MockEnvironment redis() {
		return new MockEnvironment().withProperty("app.cluster.mode", "redis");
	}

	@Test
	void developmentMayUseTheDefaultSecret() {
		assertDoesNotThrow(() -> new JwtService(JwtService.DEV_DEFAULT_SECRET, dev()));
		assertDoesNotThrow(() -> new JwtService("short-but-ok-for-32-bytes-0000000", new MockEnvironment().withProperty("app.cluster.mode", "memory")));
	}

	@Test
	void postgresProfileRejectsDefaultSecret() {
		assertThrows(IllegalStateException.class, () -> new JwtService(JwtService.DEV_DEFAULT_SECRET, postgres()));
	}

	@Test
	void redisClusterRejectsDefaultSecret() {
		assertThrows(IllegalStateException.class, () -> new JwtService(JwtService.DEV_DEFAULT_SECRET, redis()));
	}

	@Test
	void productionLikeRejectsShortSecret() {
		assertThrows(IllegalStateException.class, () -> new JwtService("too-short", postgres()));
		assertThrows(IllegalStateException.class, () -> new JwtService("x".repeat(31), redis()));
	}

	@Test
	void productionLikeAcceptsStrongSecret() {
		JwtService jwt = new JwtService(STRONG, postgres());
		assertEquals(7L, jwt.parse(jwt.issue(7L)));
		assertDoesNotThrow(() -> new JwtService("x".repeat(32), redis()));
	}
}
