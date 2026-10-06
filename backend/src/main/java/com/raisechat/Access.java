package com.raisechat;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 権限チェックの共通処理 */
@Component
public class Access {
	private final JdbcTemplate jdbc;

	public Access(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Map<String, Object> workspace(long wsId) {
		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT id, name, owner_id AS \"ownerId\" FROM workspaces WHERE id = ?", wsId);
		if (rows.isEmpty()) {
			throw new ApiException(HttpStatus.NOT_FOUND, "ワークスペースが見つかりません");
		}
		return rows.get(0);
	}

	public void requireWorkspaceMember(long wsId, long uid) {
		Integer n = jdbc.queryForObject(
				"SELECT COUNT(*) FROM workspace_members WHERE workspace_id = ? AND user_id = ?",
				Integer.class, wsId, uid);
		if (n == null || n == 0) {
			throw new ApiException(HttpStatus.FORBIDDEN, "このワークスペースのメンバーではありません");
		}
	}

	public void requireOwner(long wsId, long uid) {
		Map<String, Object> ws = workspace(wsId);
		if (((Number) ws.get("ownerId")).longValue() != uid) {
			throw new ApiException(HttpStatus.FORBIDDEN, "オーナーのみ実行できます");
		}
	}

	public Map<String, Object> channel(long channelId) {
		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT id, workspace_id AS \"workspaceId\", name, is_private AS \"isPrivate\", is_dm AS \"isDm\" FROM channels WHERE id = ?",
				channelId);
		if (rows.isEmpty()) {
			throw new ApiException(HttpStatus.NOT_FOUND, "チャンネルが見つかりません");
		}
		return rows.get(0);
	}

	/** チャンネルのメンバーであることを要求し、チャンネル情報を返す */
	public Map<String, Object> requireChannelMember(long channelId, long uid) {
		Map<String, Object> ch = channel(channelId);
		Integer n = jdbc.queryForObject(
				"SELECT COUNT(*) FROM channel_members WHERE channel_id = ? AND user_id = ?",
				Integer.class, channelId, uid);
		if (n == null || n == 0) {
			throw new ApiException(HttpStatus.FORBIDDEN, "このチャンネルに参加していません");
		}
		return ch;
	}

	public Map<String, Object> message(long messageId) {
		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT id, channel_id AS \"channelId\", user_id AS \"userId\", parent_id AS \"parentId\", deleted FROM messages WHERE id = ?",
				messageId);
		if (rows.isEmpty()) {
			throw new ApiException(HttpStatus.NOT_FOUND, "メッセージが見つかりません");
		}
		return rows.get(0);
	}
}
