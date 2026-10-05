package com.raisechat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * 複数サーバー用(app.cluster.mode=redis)。送りたい内容をRedisのチャンネルに発行し、
 * 全サーバー(自分も含む)が受信して、それぞれに繋がっている人へ届ける。
 * 配信は、できる範囲で行う(Redisに繋がらなくても、データの保存や応答は失敗させない。
 * 取りこぼした分は、クライアントが再接続したときに取り直す)。
 */
@Component
@ConditionalOnProperty(name = "app.cluster.mode", havingValue = "redis")
public class RedisMessageRelay implements MessageRelay {
	private static final Logger log = LoggerFactory.getLogger(RedisMessageRelay.class);

	/** Redisを流れる1件分。kind は topic / user */
	record Envelope(String kind, String user, String destination, Object payload) {
	}

	private final StringRedisTemplate redis;
	private final SimpMessagingTemplate local;
	private final ObjectMapper mapper;
	private final String channel;

	public RedisMessageRelay(StringRedisTemplate redis, SimpMessagingTemplate local, ObjectMapper mapper,
			@Value("${app.cluster.key-prefix:raisechat}") String prefix) {
		this.redis = redis;
		this.local = local;
		this.mapper = mapper;
		this.channel = prefix + ":events";
	}

	String channel() {
		return channel;
	}

	@Override
	public void toTopic(String destination, Object payload) {
		publish(new Envelope("topic", null, destination, payload));
	}

	@Override
	public void toUser(String userId, String destination, Object payload) {
		publish(new Envelope("user", userId, destination, payload));
	}

	private void publish(Envelope e) {
		try {
			redis.convertAndSend(channel, mapper.writeValueAsString(e));
		} catch (Exception ex) {
			log.warn("通知をRedisに発行できなかった({}): {}", e.destination(), ex.toString());
		}
	}

	/** Redisから受け取った1件を、このサーバーに繋がっている人へ届ける */
	void deliver(String json) {
		try {
			Envelope e = mapper.readValue(json, Envelope.class);
			if ("user".equals(e.kind())) {
				local.convertAndSendToUser(e.user(), e.destination(), e.payload());
			} else {
				local.convertAndSend(e.destination(), e.payload());
			}
		} catch (Exception ex) {
			log.warn("Redisから受け取った通知を配信できなかった: {}", ex.toString());
		}
	}
}
