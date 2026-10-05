package com.raisechat;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** メッセージ本文の @ユーザーID を、メンションとして保存する */
@Component
public class MentionService {
	private static final Pattern MENTION = Pattern.compile("(?<![A-Za-z0-9_])@([A-Za-z0-9_]{3,20})");

	private final JdbcTemplate jdbc;

	public MentionService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** 本文中のメンションを保存する。ワークスペースのメンバーだけが対象で、同じ人への重複は1回にまとめる */
	public void save(long messageId, String content, long workspaceId) {
		Set<String> names = new HashSet<>();
		Matcher mt = MENTION.matcher(content);
		while (mt.find()) {
			names.add(mt.group(1));
		}
		for (String name : names) {
			jdbc.update("INSERT OR IGNORE INTO mentions (message_id, user_id) "
					+ "SELECT ?, u.id FROM users u JOIN workspace_members m ON m.user_id = u.id AND m.workspace_id = ? WHERE u.username = ?",
					messageId, workspaceId, name);
		}
	}

	public void clear(long messageId) {
		jdbc.update("DELETE FROM mentions WHERE message_id = ?", messageId);
	}

	/** 編集時に、メンションを付け替える */
	public void replace(long messageId, String content, long workspaceId) {
		clear(messageId);
		save(messageId, content, workspaceId);
	}
}
