package com.raisechat;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/channels")
public class ChannelController {
	private final JdbcTemplate jdbc;
	private final Access access;

	public ChannelController(JdbcTemplate jdbc, Access access) {
		this.jdbc = jdbc;
		this.access = access;
	}

	/** パブリックチャンネルへの参加 */
	@PostMapping("/{id}/join")
	public void join(@RequestAttribute("userId") long uid, @PathVariable long id) {
		Map<String, Object> ch = access.channel(id);
		access.requireWorkspaceMember(((Number) ch.get("workspaceId")).longValue(), uid);
		if (((Number) ch.get("isPrivate")).intValue() == 1) {
			throw new ApiException(HttpStatus.FORBIDDEN, "プライベートチャンネルには招待が必要です");
		}
		addMember(id, uid);
	}

	/** プライベートチャンネルへの招待(チャンネルメンバーが実行可能) */
	@PostMapping("/{id}/members")
	public void invite(@RequestAttribute("userId") long uid, @PathVariable long id,
			@Valid @RequestBody WorkspaceController.UsernameRequest req) {
		Map<String, Object> ch = access.requireChannelMember(id, uid);
		if (((Number) ch.get("isDm")).intValue() == 1) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "DMには招待できません");
		}
		List<Long> ids = jdbc.queryForList(
				"SELECT u.id FROM users u JOIN workspace_members m ON m.user_id = u.id AND m.workspace_id = ? WHERE u.username = ?",
				Long.class, ch.get("workspaceId"), req.username().trim());
		if (ids.isEmpty()) {
			throw new ApiException(HttpStatus.NOT_FOUND, "ワークスペースにそのユーザーはいません");
		}
		addMember(id, ids.get(0));
	}

	@GetMapping("/{id}/members")
	public List<Map<String, Object>> members(@RequestAttribute("userId") long uid, @PathVariable long id) {
		access.requireChannelMember(id, uid);
		return jdbc.queryForList(
				"SELECT u.id, u.username, u.display_name AS displayName, u.status, u.avatar_url AS avatarUrl "
						+ "FROM users u JOIN channel_members m ON m.user_id = u.id WHERE m.channel_id = ? ORDER BY u.id", id);
	}

	/** チャンネル削除(ワークスペースオーナーのみ。#generalとDMは不可) */
	@DeleteMapping("/{id}")
	public void delete(@RequestAttribute("userId") long uid, @PathVariable long id) {
		Map<String, Object> ch = access.channel(id);
		access.requireOwner(((Number) ch.get("workspaceId")).longValue(), uid);
		if (((Number) ch.get("isDm")).intValue() == 1 || "general".equals(ch.get("name"))) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "このチャンネルは削除できません");
		}
		jdbc.update("DELETE FROM channels WHERE id = ?", id);
	}

	@PostMapping("/{id}/read")
	public void markRead(@RequestAttribute("userId") long uid, @PathVariable long id) {
		access.requireChannelMember(id, uid);
		jdbc.update("UPDATE channel_members SET last_read_message_id = IFNULL((SELECT MAX(id) FROM messages WHERE channel_id = ?), 0) "
				+ "WHERE channel_id = ? AND user_id = ?", id, id, uid);
	}

	private void addMember(long channelId, long userId) {
		jdbc.update("INSERT OR IGNORE INTO channel_members (channel_id, user_id, last_read_message_id) "
				+ "VALUES (?, ?, IFNULL((SELECT MAX(id) FROM messages WHERE channel_id = ?), 0))", channelId, userId, channelId);
	}
}
