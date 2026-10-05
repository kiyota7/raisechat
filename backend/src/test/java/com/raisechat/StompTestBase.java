package com.raisechat;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/** STOMP/WebSocketのテストで共通に使う接続・購読・API呼び出しのヘルパー */
abstract class StompTestBase {
	@LocalServerPort
	int port;
	@Autowired
	TestRestTemplate rest;
	final ObjectMapper om = new ObjectMapper();

	/** 接続結果(成功/エラー)を保持するハンドラ */
	static class Handler extends StompSessionHandlerAdapter {
		final CompletableFuture<StompSession> connected = new CompletableFuture<>();
		final CompletableFuture<String> error = new CompletableFuture<>();

		@Override
		public void afterConnected(StompSession session, StompHeaders headers) {
			connected.complete(session);
		}

		@Override
		public void handleFrame(StompHeaders headers, Object payload) {
			error.complete(String.valueOf(headers.getFirst("message")));
		}

		@Override
		public void handleTransportError(StompSession session, Throwable ex) {
			error.complete(String.valueOf(ex));
		}

		@Override
		public void handleException(StompSession session, org.springframework.messaging.simp.stomp.StompCommand command,
				StompHeaders headers, byte[] payload, Throwable ex) {
			error.complete(String.valueOf(ex));
		}
	}

	Handler connect(String token) {
		WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
		client.setMessageConverter(new MappingJackson2MessageConverter());
		StompHeaders h = new StompHeaders();
		if (token != null) h.add("Authorization", "Bearer " + token);
		Handler handler = new Handler();
		client.connectAsync("ws://localhost:" + port + "/ws", new org.springframework.web.socket.WebSocketHttpHeaders(), h, handler);
		return handler;
	}

	BlockingQueue<Map<String, Object>> subscribe(StompSession s, String dest) {
		BlockingQueue<Map<String, Object>> q = new LinkedBlockingQueue<>();
		s.subscribe(dest, new StompFrameHandler() {
			@Override
			public Type getPayloadType(StompHeaders headers) {
				return Map.class;
			}

			@Override
			@SuppressWarnings("unchecked")
			public void handleFrame(StompHeaders headers, Object payload) {
				q.add((Map<String, Object>) payload);
			}
		});
		return q;
	}

	JsonNode call(String method, String path, String token, String body) throws Exception {
		HttpHeaders h = new HttpHeaders();
		h.setContentType(MediaType.APPLICATION_JSON);
		if (token != null) h.setBearerAuth(token);
		String res = rest.exchange("/api" + path, org.springframework.http.HttpMethod.valueOf(method),
				new HttpEntity<>(body, h), String.class).getBody();
		return res == null || res.isEmpty() ? null : om.readTree(res);
	}

	String register(String name) throws Exception {
		return call("POST", "/auth/register", null, "{\"username\":\"" + name + "\",\"password\":\"password1\"}").get("token").asText();
	}

	long userId(String token) throws Exception {
		return call("GET", "/me", token, null).get("id").asLong();
	}
}
