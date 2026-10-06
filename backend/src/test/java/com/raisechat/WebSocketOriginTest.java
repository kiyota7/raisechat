package com.raisechat;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/** WebSocketの接続元(Origin)の制限。ブラウザ以外(Originなし)は、JWT認証に任せて通す */
class WebSocketOriginTest {
	static boolean connects(int port, String origin) throws Exception {
		WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
		if (origin != null) {
			headers.setOrigin(origin);
		}
		WebSocketHandler handler = new AbstractWebSocketHandler() {
		};
		try {
			new StandardWebSocketClient().execute(handler, headers, URI.create("ws://localhost:" + port + "/ws"))
					.get(10, TimeUnit.SECONDS).close();
			return true;
		} catch (java.util.concurrent.ExecutionException e) {
			return false;
		}
	}

	@Nested
	@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = { TestDb.URL, TestDb.DRIVER,
			TestDb.POOL, TestDb.USER, TestDb.PASSWORD, "app.websocket.allowed-origins=https://chat.example.com" })
	class Configured {
		@LocalServerPort
		int port;

		@Test
		void allowsOnlyConfiguredOrigins() throws Exception {
			assertTrue(connects(port, "https://chat.example.com"));
			assertFalse(connects(port, "https://evil.example"));
			assertTrue(connects(port, "http://localhost:" + port)); // 同じオリジンは、設定にかかわらず通る(Springの仕様)
			assertTrue(connects(port, null));
		}
	}

	@Nested
	@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = { TestDb.URL, TestDb.DRIVER,
			TestDb.POOL, TestDb.USER, TestDb.PASSWORD })
	class Default {
		@LocalServerPort
		int port;

		@Test
		void allowsSameOriginOnly() throws Exception {
			assertTrue(connects(port, "http://localhost:" + port));
			assertFalse(connects(port, "https://evil.example"));
			assertTrue(connects(port, null));
		}
	}
}
