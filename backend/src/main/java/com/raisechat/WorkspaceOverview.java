package com.raisechat;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** サイドバー表示用の、ワークスペースの概要(メンバー・オンライン・チャンネル・DM・未読数)を組み立てる */
@Component
public class WorkspaceOverview {
	/** 未読数とメンション数(自分の投稿は数えない。最後に読んだメッセージより後のもの)。パラメーターは3つ(uid, uid, uid) */
	private static final String COUNTS = "(SELECT COUNT(*) FROM messages x WHERE x.channel_id = c.id AND x.deleted = 0 AND x.user_id <> ? "
			+ "AND x.id > COALESCE(cm.last_read_message_id, 0)) AS unread, "
			+ "(SELECT COUNT(*) FROM messages x JOIN mentions mt ON mt.message_id = x.id AND mt.user_id = ? "
			+ "WHERE x.channel_id = c.id AND x.deleted = 0 AND x.user_id <> ? AND x.id > COALESCE(cm.last_read_message_id, 0)) AS mentions";

	private final JdbcTemplate jdbc;
	private final Access access;
	private final Realtime realtime;
	private final PresenceService presence;

	public WorkspaceOverview(JdbcTemplate jdbc, Access access, Realtime realtime, PresenceService presence) {
		this.jdbc = jdbc;
		this.access = access;
		this.realtime = realtime;
		this.presence = presence;
	}

	public Map<String, Object> build(long uid, long workspaceId) {
		Map<String, Object> res = new LinkedHashMap<>();
		res.put("workspace", access.workspace(workspaceId));
		res.put("members", jdbc.queryForList(
				"SELECT u.id, u.username, u.display_name AS \"displayName\", u.status, u.avatar_url AS \"avatarUrl\" "
						+ "FROM users u JOIN workspace_members m ON m.user_id = u.id WHERE m.workspace_id = ? ORDER BY u.id", workspaceId));
		res.put("onlineUserIds", presence.onlineAmong(realtime.workspaceMembers(workspaceId)));
		List<Map<String, Object>> channels = jdbc.queryForList(
				"SELECT c.id, c.name, c.is_private AS \"isPrivate\", CASE WHEN cm.user_id IS NOT NULL THEN 1 ELSE 0 END AS joined, " + COUNTS
						+ " FROM channels c LEFT JOIN channel_members cm ON cm.channel_id = c.id AND cm.user_id = ? "
						+ "WHERE c.workspace_id = ? AND c.is_dm = 0 AND (c.is_private = 0 OR cm.user_id IS NOT NULL)",
				uid, uid, uid, uid, workspaceId);
		// 名前の順(DBの照合順序に依存しないよう、Javaで並べる)
		channels.sort(Comparator.comparing(c -> (String) c.get("name")));
		res.put("channels", channels);
		res.put("dms", jdbc.queryForList(
				"SELECT c.id, other.user_id AS \"userId\", " + COUNTS
						+ " FROM channels c JOIN channel_members cm ON cm.channel_id = c.id AND cm.user_id = ? "
						+ "JOIN channel_members other ON other.channel_id = c.id AND other.user_id <> ? "
						+ "WHERE c.workspace_id = ? AND c.is_dm = 1 ORDER BY c.id",
				uid, uid, uid, uid, uid, workspaceId));
		return res;
	}
}
