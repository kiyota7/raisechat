package com.raisechat;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** prod プロファイルでは、公開されている既定の秘密鍵や短い秘密鍵で起動させない */
class JwtSecretTest {
	private static final String STRONG = "a-very-long-and-random-secret-for-tests-0123456789";

	private static MockEnvironment dev() {
		return new MockEnvironment();
	}

	private static MockEnvironment prod() {
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles("prod");
		return env;
	}

	@Test
	void developmentMayUseTheDefaultSecret() {
		assertDoesNotThrow(() -> new JwtService(JwtService.DEV_DEFAULT_SECRET, dev()));
	}

	@Test
	void prodProfileRejectsDefaultSecret() {
		assertThrows(IllegalStateException.class, () -> new JwtService(JwtService.DEV_DEFAULT_SECRET, prod()));
	}

	@Test
	void prodProfileRejectsShortSecret() {
		assertThrows(IllegalStateException.class, () -> new JwtService("too-short", prod()));
		assertThrows(IllegalStateException.class, () -> new JwtService("x".repeat(31), prod()));
	}

	@Test
	void prodProfileAcceptsStrongSecret() {
		JwtService jwt = new JwtService(STRONG, prod());
		assertEquals(7L, jwt.parse(jwt.issue(7L)));
		assertDoesNotThrow(() -> new JwtService("x".repeat(32), prod()));
	}
}
