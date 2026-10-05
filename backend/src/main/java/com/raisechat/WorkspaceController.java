package com.raisechat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class WorkspaceController {
	private final JdbcTemplate jdbc;
	private final Access access;
	private final Realtime realtime;
	private final PresenceService presence;

	public WorkspaceController(JdbcTemplate jdbc, Access access, Realtime realtime, PresenceService presence) {
		this.jdbc = jdbc;
		this.access = access;
		this.realtime = realtime;
		this.presence = presence;
	}

	public record NameRequest(@NotBlank(message = "名前を入力してください") @Size(max = 30, message = "名前は30文字以内で入力してください") String name) {
	}

	public record UsernameRequest(@NotBlank(message = "ユーザーIDを入力してください") String username) {
	}

	public record DmRequest(long userId) {
	}

	@GetMapping("/workspaces")
	public List<Map<String, Object>> list(@RequestAttribute("userId") long uid) {
		return jdbc.queryForList(
				"SELECT w.id, w.name, w.owner_id AS ownerId FROM workspaces w "
						+ "JOIN workspace_members m ON m.workspace_id = w.id WHERE m.user_id = ? ORDER BY w.id", uid);
	}

	@PostMapping("/workspaces")
	@Transactional
	public Map<String, Object> create(@RequestAttribute("userId") long uid, @Valid @RequestBody NameRequest req) {
		long wsId = insert("INSERT INTO workspaces (name, owner_id) VALUES (?, ?)", req.name().trim(), uid);
		jdbc.update("INSERT INTO workspace_members (workspace_id, user_id) VALUES (?, ?)", wsId, uid);
		long chId = insert("INSERT INTO channels (workspace_id, name, is_private, is_dm) VALUES (?, 'general', 0, 0)", wsId);
		jdbc.update("INSERT INTO channel_members (channel_id, user_id) VALUES (?, ?)", chId, uid);
		return access.workspace(wsId);
	}

	/** サイドバー表示用の概要(メンバー・チャンネル・DM・未読数) */
	@GetMapping("/workspaces/{id}")
	public Map<String, Object> overview(@RequestAttribute("userId") long uid, @PathVariable long id) {
		access.requireWorkspaceMember(id, uid);
		Map<String, Object> res = new LinkedHashMap<>();
		res.put("workspace", access.workspace(id));
		res.put("members", jdbc.queryForList(
				"SELECT u.id, u.username, u.display_name AS displayName, u.status, u.avatar_url AS avatarUrl "
						+ "FROM users u JOIN workspace_members m ON m.user_id = u.id WHERE m.workspace_id = ? ORDER BY u.id", id));
		res.put("onlineUserIds", presence.onlineAmong(realtime.workspaceMembers(id)));
		String counts = "(SELECT COUNT(*) FROM messages x WHERE x.channel_id = c.id AND x.deleted = 0 AND x.user_id <> ? "
				+ "AND x.id > IFNULL(cm.last_read_message_id, 0)) AS unread, "
				+ "(SELECT COUNT(*) FROM messages x JOIN mentions mt ON mt.message_id = x.id AND mt.user_id = ? "
				+ "WHERE x.channel_id = c.id AND x.deleted = 0 AND x.user_id <> ? AND x.id > IFNULL(cm.last_read_message_id, 0)) AS mentions";
		res.put("channels", jdbc.queryForList(
				"SELECT c.id, c.name, c.is_private AS isPrivate, cm.user_id IS NOT NULL AS joined, " + counts
						+ " FROM channels c LEFT JOIN channel_members cm ON cm.channel_id = c.id AND cm.user_id = ? "
						+ "WHERE c.workspace_id = ? AND c.is_dm = 0 AND (c.is_private = 0 OR cm.user_id IS NOT NULL) ORDER BY c.name",
				uid, uid, uid, uid, id));
		res.put("dms", jdbc.queryForList(
				"SELECT c.id, other.user_id AS userId, " + counts
						+ " FROM channels c JOIN channel_members cm ON cm.channel_id = c.id AND cm.user_id = ? "
						+ "JOIN channel_members other ON other.channel_id = c.id AND other.user_id <> ? "
						+ "WHERE c.workspace_id = ? AND c.is_dm = 1 ORDER BY c.id",
				uid, uid, uid, uid, uid, id));
		return res;
	}

	@PostMapping("/workspaces/{id}/invite")
	@Transactional
	public Map<String, Object> invite(@RequestAttribute("userId") long uid, @PathVariable long id,
			@Valid @RequestBody UsernameRequest req) {
		access.requireOwner(id, uid);
		List<Long> ids = jdbc.queryForList("SELECT id FROM users WHERE username = ?", Long.class, req.username().trim());
		if (ids.isEmpty()) {
			throw new ApiException(HttpStatus.NOT_FOUND, "そのユーザーIDのユーザーは存在しません");
		}
		long target = ids.get(0);
		Integer exists = jdbc.queryForObject(
				"SELECT COUNT(*) FROM workspace_members WHERE workspace_id = ? AND user_id = ?", Integer.class, id, target);
		if (exists != null && exists > 0) {
			throw new ApiException(HttpStatus.CONFLICT, "既にメンバーです");
		}
		jdbc.update("INSERT INTO workspace_members (workspace_id, user_id) VALUES (?, ?)", id, target);
		jdbc.update("INSERT OR IGNORE INTO channel_members (channel_id, user_id, last_read_message_id) "
				+ "SELECT id, ?, IFNULL((SELECT MAX(id) FROM messages WHERE channel_id = channels.id), 0) "
				+ "FROM channels WHERE workspace_id = ? AND name = 'general' AND is_dm = 0", target, id);
		realtime.overviewToWorkspace(id);
		return Map.of("userId", target);
	}

	@DeleteMapping("/workspaces/{id}/members/{userId}")
	@Transactional
	public void kick(@RequestAttribute("userId") long uid, @PathVariable long id, @PathVariable long userId) {
		access.requireOwner(id, uid);
		if (userId == uid) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "オーナー自身はキックできません");
		}
		jdbc.update("DELETE FROM channel_members WHERE user_id = ? AND channel_id IN (SELECT id FROM channels WHERE workspace_id = ?)",
				userId, id);
		jdbc.update("DELETE FROM workspace_members WHERE workspace_id = ? AND user_id = ?", id, userId);
		List<Long> notify = new java.util.ArrayList<>(realtime.workspaceMembers(id));
		notify.add(userId);
		realtime.overviewToUsers(id, notify);
	}

	@PostMapping("/workspaces/{id}/channels")
	@Transactional
	public Map<String, Object> createChannel(@RequestAttribute("userId") long uid, @PathVariable long id,
			@Valid @RequestBody ChannelRequest req) {
		access.requireWorkspaceMember(id, uid);
		String name = req.name().trim().toLowerCase().replaceAll("\\s+", "-");
		Integer dup = jdbc.queryForObject(
				"SELECT COUNT(*) FROM channels WHERE workspace_id = ? AND is_dm = 0 AND name = ?", Integer.class, id, name);
		if (dup != null && dup > 0) {
			throw new ApiException(HttpStatus.CONFLICT, "同じ名前のチャンネルが既にあります");
		}
		long chId = insert("INSERT INTO channels (workspace_id, name, is_private, is_dm) VALUES (?, ?, ?, 0)",
				id, name, req.isPrivate() ? 1 : 0);
		jdbc.update("INSERT INTO channel_members (channel_id, user_id) VALUES (?, ?)", chId, uid);
		if (!req.isPrivate()) {
			realtime.overviewToWorkspace(id);
		}
		return access.channel(chId);
	}

	public record ChannelRequest(
			@NotBlank(message = "チャンネル名を入力してください") @Size(max = 30, message = "チャンネル名は30文字以内で入力してください") String name,
			boolean isPrivate) {
	}

	@PostMapping("/workspaces/{id}/dms")
	@Transactional
	public Map<String, Object> openDm(@RequestAttribute("userId") long uid, @PathVariable long id,
			@RequestBody DmRequest req) {
		access.requireWorkspaceMember(id, uid);
		if (req.userId() == uid) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "自分自身にはDMを送れません");
		}
		access.requireWorkspaceMember(id, req.userId());
		List<Long> existing = jdbc.queryForList(
				"SELECT c.id FROM channels c WHERE c.workspace_id = ? AND c.is_dm = 1 "
						+ "AND EXISTS (SELECT 1 FROM channel_members a WHERE a.channel_id = c.id AND a.user_id = ?) "
						+ "AND EXISTS (SELECT 1 FROM channel_members b WHERE b.channel_id = c.id AND b.user_id = ?)",
				Long.class, id, uid, req.userId());
		long chId;
		if (existing.isEmpty()) {
			chId = insert("INSERT INTO channels (workspace_id, name, is_private, is_dm) VALUES (?, ?, 1, 1)", id,
					"dm-" + Math.min(uid, req.userId()) + "-" + Math.max(uid, req.userId()));
			jdbc.update("INSERT INTO channel_members (channel_id, user_id) VALUES (?, ?), (?, ?)", chId, uid, chId, req.userId());
			realtime.overviewToUsers(id, List.of(req.userId()));
		} else {
			chId = existing.get(0);
		}
		return Map.of("id", chId, "userId", req.userId());
	}

	@GetMapping("/workspaces/{id}/search")
	public List<Map<String, Object>> search(@RequestAttribute("userId") long uid, @PathVariable long id,
			@RequestParam("q") String q) {
		access.requireWorkspaceMember(id, uid);
		if (q.isBlank()) {
			return List.of();
		}
		String like = "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
		return jdbc.queryForList(
				"SELECT m.id, m.channel_id AS channelId, m.parent_id AS parentId, c.name AS channelName, c.is_dm AS isDm, "
						+ "m.content, m.created_at AS createdAt, u.display_name AS displayName, u.avatar_url AS avatarUrl "
						+ "FROM messages m JOIN channels c ON c.id = m.channel_id JOIN users u ON u.id = m.user_id "
						+ "JOIN channel_members cm ON cm.channel_id = c.id AND cm.user_id = ? "
						+ "WHERE c.workspace_id = ? AND m.deleted = 0 AND m.content LIKE ? ESCAPE '\\' "
						+ "ORDER BY m.id DESC LIMIT 50",
				uid, id, like);
	}

	private long insert(String sql, Object... args) {
		GeneratedKeyHolder kh = new GeneratedKeyHolder();
		jdbc.update(con -> {
			PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
			for (int i = 0; i < args.length; i++) {
				ps.setObject(i + 1, args[i]);
			}
			return ps;
		}, kh);
		return kh.getKey().longValue();
	}
}
