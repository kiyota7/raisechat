package com.raisechat;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/** サーバー1台用(既定)。このサーバーに繋がっている人にだけ、直接送る */
@Component
@ConditionalOnProperty(name = "app.cluster.mode", havingValue = "memory", matchIfMissing = true)
public class LocalMessageRelay implements MessageRelay {
	private final SimpMessagingTemplate template;

	public LocalMessageRelay(SimpMessagingTemplate template) {
		this.template = template;
	}

	@Override
	public void toTopic(String destination, Object payload) {
		template.convertAndSend(destination, payload);
	}

	@Override
	public void toUser(String userId, String destination, Object payload) {
		template.convertAndSendToUser(userId, destination, payload);
	}
}
