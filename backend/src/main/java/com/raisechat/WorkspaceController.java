package com.raisechat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class WorkspaceController {
	private final JdbcTemplate jdbc;
	private final Access access;
	private final Realtime realtime;
	private final Sql sql;
	private final WorkspaceOverview workspaceOverview;
	private final MessageQueries queries;

	public WorkspaceController(JdbcTemplate jdbc, Access access, Realtime realtime, Sql sql, WorkspaceOverview workspaceOverview,
			MessageQueries queries) {
		this.jdbc = jdbc;
		this.access = access;
		this.realtime = realtime;
		this.sql = sql;
		this.workspaceOverview = workspaceOverview;
		this.queries = queries;
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
				"SELECT w.id, w.name, w.owner_id AS \"ownerId\" FROM workspaces w "
						+ "JOIN workspace_members m ON m.workspace_id = w.id WHERE m.user_id = ? ORDER BY w.id", uid);
	}

	@PostMapping("/workspaces")
	@Transactional
	public Map<String, Object> create(@RequestAttribute("userId") long uid, @Valid @RequestBody NameRequest req) {
		long wsId = sql.insert("INSERT INTO workspaces (name, owner_id) VALUES (?, ?)", req.name().trim(), uid);
		jdbc.update("INSERT INTO workspace_members (workspace_id, user_id) VALUES (?, ?)", wsId, uid);
		long chId = sql.insert("INSERT INTO channels (workspace_id, name, is_private, is_dm) VALUES (?, 'general', 0, 0)", wsId);
		jdbc.update("INSERT INTO channel_members (channel_id, user_id) VALUES (?, ?)", chId, uid);
		return access.workspace(wsId);
	}

	/** サイドバー表示用の概要(メンバー・チャンネル・DM・未読数) */
	@GetMapping("/workspaces/{id}")
	public Map<String, Object> overview(@RequestAttribute("userId") long uid, @PathVariable long id) {
		access.requireWorkspaceMember(id, uid);
		return workspaceOverview.build(uid, id);
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
		jdbc.update("INSERT INTO channel_members (channel_id, user_id, last_read_message_id) "
				+ "SELECT id, CAST(? AS BIGINT), COALESCE((SELECT MAX(id) FROM messages WHERE channel_id = channels.id), 0) "
				+ "FROM channels WHERE workspace_id = ? AND name = 'general' AND is_dm = 0 ON CONFLICT DO NOTHING", target, id);
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
		long chId = sql.insert("INSERT INTO channels (workspace_id, name, is_private, is_dm) VALUES (?, ?, ?, 0)",
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
			chId = sql.insert("INSERT INTO channels (workspace_id, name, is_private, is_dm) VALUES (?, ?, 1, 1)", id,
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
		return queries.search(uid, id, q);
	}
}
