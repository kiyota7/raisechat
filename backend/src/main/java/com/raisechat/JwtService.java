package com.raisechat;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtService {
	private static final long TTL_MS = 7L * 24 * 60 * 60 * 1000;
	private final SecretKey key;

	public JwtService(@Value("${app.jwt-secret}") String secret) {
		this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
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
