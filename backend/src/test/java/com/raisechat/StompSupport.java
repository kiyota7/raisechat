package com.raisechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/** 起動中のサーバー(ポート指定)に、REST と STOMP で接続するテスト用の補助 */
final class StompSupport {
	private static final ObjectMapper OM = new ObjectMapper();
	private static final HttpClient HTTP = HttpClient.newHttpClient();

	private StompSupport() {
	}

	record Res(int status, JsonNode body) {
	}

	record User(String name, String token, long id) {
	}

	static Res http(int port, String method, String path, String token, String body) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api" + path))
				.header("Content-Type", "application/json");
		if (token != null) {
			b.header("Authorization", "Bearer " + token);
		}
		b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
		HttpResponse<String> res = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
		return new Res(res.statusCode(), res.body().isEmpty() ? null : OM.readTree(res.body()));
	}

	static JsonNode ok(int port, String method, String path, String token, String body) throws Exception {
		Res r = http(port, method, path, token, body);
		if (r.status() != 200) {
			throw new AssertionError(method + " " + path + " (port " + port + ") → " + r.status() + " " + r.body());
		}
		return r.body();
	}

	static User register(int port, String name) throws Exception {
		JsonNode r = ok(port, "POST", "/auth/register", null, "{\"username\":\"" + name + "\",\"password\":\"Cluster-Test-1x\"}");
		return new User(name, r.get("token").asText(), r.get("user").get("id").asLong());
	}

	/** 接続の結果(成功/エラー)を保持するハンドラ */
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
	}

	static StompSession connect(int port, String token) throws Exception {
		WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
		client.setMessageConverter(new MappingJackson2MessageConverter());
		StompHeaders h = new StompHeaders();
		h.add("Authorization", "Bearer " + token);
		Handler handler = new Handler();
		client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), h, handler);
		return handler.connected.get(10, java.util.concurrent.TimeUnit.SECONDS);
	}

	static BlockingQueue<Map<String, Object>> subscribe(StompSession s, String dest) {
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
}
