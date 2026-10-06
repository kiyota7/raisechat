package com.raisechat;

import java.security.Principal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/** STOMPのJWT認証と購読の認可。クライアントからのSENDは許可しない(メッセージ送信はREST) */
@Component
public class StompAuthInterceptor implements ChannelInterceptor {
	/** チャンネルの通知(/topic/channels/{id})。チャンネルメンバーのみ */
	private static final Pattern CHANNEL_TOPIC = Pattern.compile("^/topic/channels/(\\d+)$");

	private final JwtService jwt;
	private final Access access;

	public StompAuthInterceptor(JwtService jwt, Access access) {
		this.jwt = jwt;
		this.access = access;
	}

	@Override
	public Message<?> preSend(Message<?> message, MessageChannel channel) {
		StompHeaderAccessor acc = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
		if (acc == null || acc.getCommand() == null) {
			return message;
		}
		StompCommand cmd = acc.getCommand();
		if (cmd == StompCommand.CONNECT) {
			String h = acc.getFirstNativeHeader("Authorization");
			if (h == null || !h.startsWith("Bearer ")) {
				throw new MessagingException("ログインが必要です");
			}
			try {
				String uid = String.valueOf(jwt.parse(h.substring(7)));
				acc.setUser(() -> uid);
			} catch (Exception e) {
				throw new MessagingException("セッションが無効です");
			}
		} else if (cmd == StompCommand.SUBSCRIBE) {
			Principal user = acc.getUser();
			String dest = acc.getDestination();
			if (user == null || dest == null) {
				throw new MessagingException("購読できません");
			}
			Matcher m = CHANNEL_TOPIC.matcher(dest);
			if (m.matches()) {
				access.requireChannelMember(Long.parseLong(m.group(1)), Long.parseLong(user.getName()));
			} else if (!dest.equals("/user/queue/overview")) {
				throw new MessagingException("購読できません");
			}
		} else if (cmd == StompCommand.SEND) {
			throw new MessagingException("クライアントからの送信はできません");
		}
		return message;
	}
}
