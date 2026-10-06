package com.raisechat;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public class JwtService {
	private static final long TTL_MS = 7L * 24 * 60 * 60 * 1000;
	private final SecretKey key;

	/** application.properties の開発用の既定値。公開されているので、本番では使わせない */
	static final String DEV_DEFAULT_SECRET = "raisechat-dev-secret-key-change-me-in-production-0123456789";
	private static final int MIN_PRODUCTION_SECRET_BYTES = 32;

	public JwtService(@Value("${app.jwt-secret}") String secret, Environment env) {
		if (isProductionLike(env)) {
			if (DEV_DEFAULT_SECRET.equals(secret)) {
				throw new IllegalStateException(
						"JWT_SECRET が開発用の既定値のままです。prod プロファイル(SPRING_PROFILES_ACTIVE=prod)では、推測されない秘密鍵を JWT_SECRET に設定してください");
			}
			if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_PRODUCTION_SECRET_BYTES) {
				throw new IllegalStateException("JWT_SECRET は32バイト以上にしてください");
			}
		}
		this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
	}

	/** prod プロファイルは、本番とみなす(既定値や短い鍵では起動させない) */
	private static boolean isProductionLike(Environment env) {
		return env.acceptsProfiles(Profiles.of("prod"));
	}

	public String issue(long userId) {
		Date now = new Date();
		return Jwts.builder().subject(String.valueOf(userId)).issuedAt(now)
				.expiration(new Date(now.getTime() + TTL_MS)).signWith(key).compact();
	}

	/** 検証に失敗した場合は例外を投げる */
	public long parse(String token) {
		Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
		return Long.parseLong(c.getSubject());
	}
}
