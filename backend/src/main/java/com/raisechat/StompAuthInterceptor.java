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

/** STOMPのJWT認証と購読の認可。クライアントからのSENDは入力中の通知(/app/typing)だけ許可する(メッセージ送信はREST) */
@Component
public class StompAuthInterceptor implements ChannelInterceptor {
	/** メッセージ用(/topic/channels/{id})と入力中用(/topic/channels/{id}/typing)。どちらもチャンネルメンバーのみ */
	private static final Pattern CHANNEL_TOPIC = Pattern.compile("^/topic/channels/(\\d+)(/typing)?$");
	private static final String TYPING_DEST = "/app/typing";

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
			} else if (!dest.equals("/user/queue/overview") && !dest.equals("/user/queue/presence")) {
				throw new MessagingException("購読できません");
			}
		} else if (cmd == StompCommand.SEND && !TYPING_DEST.equals(acc.getDestination())) {
			throw new MessagingException("クライアントからの送信はできません");
		}
		return message;
	}
}
