package com.raisechat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** メッセージの取得(投稿者・返信数・リアクションつき)と検索 */
@Component
public class MessageQueries {
	private static final String BASE_SQL =
			"SELECT m.id, m.channel_id AS \"channelId\", m.parent_id AS \"parentId\", m.content, m.attachment_url AS \"attachmentUrl\", "
					+ "m.attachment_type AS \"attachmentType\", m.created_at AS \"createdAt\", m.edited_at AS \"editedAt\", m.deleted, "
					+ "u.id AS \"userId\", u.username, u.display_name AS \"displayName\", u.avatar_url AS \"avatarUrl\", "
					+ "(SELECT COUNT(*) FROM messages r WHERE r.parent_id = m.id AND r.deleted = 0) AS \"replyCount\" "
					+ "FROM messages m JOIN users u ON u.id = m.user_id ";

	private final JdbcTemplate jdbc;

	public MessageQueries(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	static boolean isDeleted(Map<String, Object> m) {
		return ((Number) m.get("deleted")).intValue() == 1;
	}

	/** チャンネルの最新200件(返信を除く。古い順)。削除済みでも返信があれば残す */
	public List<Map<String, Object>> channelMessages(long channelId) {
		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT * FROM (" + BASE_SQL + "WHERE m.channel_id = ? AND m.parent_id IS NULL ORDER BY m.id DESC LIMIT 200) t ORDER BY id",
				channelId);
		rows.removeIf(r -> isDeleted(r) && ((Number) r.get("replyCount")).intValue() == 0);
		return enrich(rows);
	}

	/** スレッド(親と、削除されていない返信) */
	public Map<String, Object> thread(long rootId) {
		List<Map<String, Object>> parent = enrich(new ArrayList<>(jdbc.queryForList(BASE_SQL + "WHERE m.id = ?", rootId)));
		List<Map<String, Object>> replies = jdbc.queryForList(BASE_SQL + "WHERE m.parent_id = ? AND m.deleted = 0 ORDER BY m.id", rootId);
		Map<String, Object> res = new LinkedHashMap<>();
		res.put("parent", parent.get(0));
		res.put("replies", enrich(replies));
		return res;
	}

	/** 1件のメッセージ(投稿・編集の応答用) */
	public Map<String, Object> find(long messageId) {
		return enrich(new ArrayList<>(jdbc.queryForList(BASE_SQL + "WHERE m.id = ?", messageId))).get(0);
	}

	/** ユーザーが参加しているチャンネルの、削除されていないメッセージを新しい順に50件まで。%と_はワイルドカードとして扱わない */
	public List<Map<String, Object>> search(long userId, long workspaceId, String q) {
		String like = "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
		return jdbc.queryForList(
				"SELECT m.id, m.channel_id AS \"channelId\", m.parent_id AS \"parentId\", c.name AS \"channelName\", c.is_dm AS \"isDm\", "
						+ "m.content, m.created_at AS \"createdAt\", u.display_name AS \"displayName\", u.avatar_url AS \"avatarUrl\" "
						+ "FROM messages m JOIN channels c ON c.id = m.channel_id JOIN users u ON u.id = m.user_id "
						+ "JOIN channel_members cm ON cm.channel_id = c.id AND cm.user_id = ? "
						+ "WHERE c.workspace_id = ? AND m.deleted = 0 AND LOWER(m.content) LIKE LOWER(?) ESCAPE '\\' "
						+ "ORDER BY m.id DESC LIMIT 50",
				userId, workspaceId, like);
	}

	/** リアクション一覧(絵文字ごとにユーザーIDをまとめたもの)を付与し、deletedを真偽値にする */
	private List<Map<String, Object>> enrich(List<Map<String, Object>> rows) {
		if (rows.isEmpty()) {
			return rows;
		}
		String in = String.join(",", rows.stream().map(r -> String.valueOf(r.get("id"))).toList());
		List<Map<String, Object>> rs = jdbc.queryForList(
				"SELECT message_id AS \"messageId\", emoji, user_id AS \"userId\" FROM reactions WHERE message_id IN (" + in + ") ORDER BY id");
		for (Map<String, Object> row : rows) {
			Map<String, List<Object>> byEmoji = new LinkedHashMap<>();
			for (Map<String, Object> r : rs) {
				if (r.get("messageId").equals(row.get("id"))) {
					byEmoji.computeIfAbsent((String) r.get("emoji"), k -> new ArrayList<>()).add(r.get("userId"));
				}
			}
			List<Map<String, Object>> reactions = new ArrayList<>();
			byEmoji.forEach((e, users) -> reactions.add(Map.of("emoji", e, "userIds", users)));
			row.put("reactions", reactions);
			row.put("deleted", isDeleted(row));
		}
		return rows;
	}
}
