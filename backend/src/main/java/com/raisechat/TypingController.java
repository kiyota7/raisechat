package com.raisechat;

import java.security.Principal;
import java.util.Map;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

/**
 * 入力中の通知。クライアントが /app/typing に送ると、同じチャンネルの購読者へ転送する(保存はしない)。
 * 送信者のIDは、クライアントが送った値ではなく認証済みのユーザーから付ける。
 */
@Controller
public class TypingController {
	public record TypingRequest(long channelId) {
	}

	private final SimpMessagingTemplate template;
	private final Access access;

	public TypingController(SimpMessagingTemplate template, Access access) {
		this.template = template;
		this.access = access;
	}

	@MessageMapping("/typing")
	public void typing(Principal principal, TypingRequest req) {
		if (principal == null) {
			return;
		}
		long uid = Long.parseLong(principal.getName());
		try {
			access.requireChannelMember(req.channelId(), uid);
		} catch (ApiException e) {
			return; // メンバーでない場合は黙って捨てる
		}
		template.convertAndSend("/topic/channels/" + req.channelId() + "/typing", Map.of("userId", uid));
	}
}
