package com.raisechat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class MessageController {
	private static final Pattern MENTION = Pattern.compile("(?<![A-Za-z0-9_])@([A-Za-z0-9_]{3,20})");
	private static final String BASE_SQL =
			"SELECT m.id, m.channel_id AS channelId, m.parent_id AS parentId, m.content, m.attachment_url AS attachmentUrl, "
					+ "m.attachment_type AS attachmentType, m.created_at AS createdAt, m.edited_at AS editedAt, m.deleted, "
					+ "u.id AS userId, u.username, u.display_name AS displayName, u.avatar_url AS avatarUrl, "
					+ "(SELECT COUNT(*) FROM messages r WHERE r.parent_id = m.id AND r.deleted = 0) AS replyCount "
					+ "FROM messages m JOIN users u ON u.id = m.user_id ";

	private final JdbcTemplate jdbc;
	private final Access access;

	public MessageController(JdbcTemplate jdbc, Access access) {
		this.jdbc = jdbc;
		this.access = access;
	}

	public record PostRequest(@Size(max = 5000, message = "メッセージは5000文字以内で入力してください") String content, Long parentId,
			String attachmentUrl, String attachmentType) {
	}

	public record EditRequest(@NotBlank(message = "メッセージを入力してください") @Size(max = 5000, message = "メッセージは5000文字以内で入力してください") String content) {
	}

	public record ReactionRequest(@NotBlank @Size(max = 16) String emoji) {
	}

	@GetMapping("/channels/{id}/messages")
	public List<Map<String, Object>> list(@RequestAttribute("userId") long uid, @PathVariable long id) {
		access.requireChannelMember(id, uid);
		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT * FROM (" + BASE_SQL + "WHERE m.channel_id = ? AND m.parent_id IS NULL ORDER BY m.id DESC LIMIT 200) ORDER BY id", id);
		rows.removeIf(r -> isDeleted(r) && ((Number) r.get("replyCount")).intValue() == 0);
		return enrich(rows);
	}

	@GetMapping("/messages/{id}/thread")
	public Map<String, Object> thread(@RequestAttribute("userId") long uid, @PathVariable long id) {
		Map<String, Object> m = access.message(id);
		access.requireChannelMember(((Number) m.get("channelId")).longValue(), uid);
		long rootId = m.get("parentId") == null ? id : ((Number) m.get("parentId")).longValue();
		List<Map<String, Object>> parent = enrich(new ArrayList<>(jdbc.queryForList(BASE_SQL + "WHERE m.id = ?", rootId)));
		List<Map<String, Object>> replies = jdbc.queryForList(BASE_SQL + "WHERE m.parent_id = ? AND m.deleted = 0 ORDER BY m.id", rootId);
		Map<String, Object> res = new LinkedHashMap<>();
		res.put("parent", parent.get(0));
		res.put("replies", enrich(replies));
		return res;
	}

	@PostMapping("/channels/{id}/messages")
	@Transactional
	public Map<String, Object> post(@RequestAttribute("userId") long uid, @PathVariable long id,
			@Valid @RequestBody PostRequest req) {
		Map<String, Object> ch = access.requireChannelMember(id, uid);
		String content = req.content() == null ? "" : req.content().trim();
		if (content.isEmpty() && req.attachmentUrl() == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "メッセージを入力してください");
		}
		if (req.attachmentUrl() != null && !req.attachmentUrl().startsWith("/uploads/")) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "添付ファイルが不正です");
		}
		Long parentId = req.parentId();
		if (parentId != null) {
			Map<String, Object> parent = access.message(parentId);
			if (((Number) parent.get("channelId")).longValue() != id || parent.get("parentId") != null) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "返信先が不正です");
			}
		}
		GeneratedKeyHolder kh = new GeneratedKeyHolder();
		final String c = content;
		jdbc.update(con -> {
			PreparedStatement ps = con.prepareStatement(
					"INSERT INTO messages (channel_id, user_id, parent_id, content, attachment_url, attachment_type) VALUES (?, ?, ?, ?, ?, ?)",
					Statement.RETURN_GENERATED_KEYS);
			ps.setLong(1, id);
			ps.setLong(2, uid);
			ps.setObject(3, parentId);
			ps.setString(4, c);
			ps.setString(5, req.attachmentUrl());
			ps.setString(6, req.attachmentType());
			return ps;
		}, kh);
		long msgId = kh.getKey().longValue();
		saveMentions(msgId, content, ((Number) ch.get("workspaceId")).longValue());
		// 自分の投稿は既読扱いにする
		jdbc.update("UPDATE channel_members SET last_read_message_id = ? WHERE channel_id = ? AND user_id = ? AND last_read_message_id < ?",
				msgId, id, uid, msgId);
		return enrich(new ArrayList<>(jdbc.queryForList(BASE_SQL + "WHERE m.id = ?", msgId))).get(0);
	}

	@PutMapping("/messages/{id}")
	@Transactional
	public Map<String, Object> edit(@RequestAttribute("userId") long uid, @PathVariable long id,
			@Valid @RequestBody EditRequest req) {
		Map<String, Object> m = ownMessage(uid, id);
		String content = req.content().trim();
		jdbc.update("UPDATE messages SET content = ?, edited_at = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id = ?", content, id);
		jdbc.update("DELETE FROM mentions WHERE message_id = ?", id);
		Map<String, Object> ch = access.channel(((Number) m.get("channelId")).longValue());
		saveMentions(id, content, ((Number) ch.get("workspaceId")).longValue());
		return enrich(new ArrayList<>(jdbc.queryForList(BASE_SQL + "WHERE m.id = ?", id))).get(0);
	}

	@DeleteMapping("/messages/{id}")
	public void delete(@RequestAttribute("userId") long uid, @PathVariable long id) {
		ownMessage(uid, id);
		jdbc.update("UPDATE messages SET deleted = 1, content = '', attachment_url = NULL, attachment_type = NULL WHERE id = ?", id);
		jdbc.update("DELETE FROM mentions WHERE message_id = ?", id);
	}

	/** 同じ絵文字を再度押すと取り消し */
	@PostMapping("/messages/{id}/reactions")
	public void react(@RequestAttribute("userId") long uid, @PathVariable long id, @Valid @RequestBody ReactionRequest req) {
		Map<String, Object> m = access.message(id);
		access.requireChannelMember(((Number) m.get("channelId")).longValue(), uid);
		int removed = jdbc.update("DELETE FROM reactions WHERE message_id = ? AND user_id = ? AND emoji = ?", id, uid, req.emoji());
		if (removed == 0) {
			jdbc.update("INSERT INTO reactions (message_id, user_id, emoji) VALUES (?, ?, ?)", id, uid, req.emoji());
		}
	}

	private Map<String, Object> ownMessage(long uid, long id) {
		Map<String, Object> m = access.message(id);
		if (((Number) m.get("userId")).longValue() != uid) {
			throw new ApiException(HttpStatus.FORBIDDEN, "自分のメッセージのみ操作できます");
		}
		if (isDeleted(m)) {
			throw new ApiException(HttpStatus.NOT_FOUND, "メッセージは削除されています");
		}
		return m;
	}

	private static boolean isDeleted(Map<String, Object> m) {
		return ((Number) m.get("deleted")).intValue() == 1;
	}

	private void saveMentions(long msgId, String content, long workspaceId) {
		Set<String> names = new HashSet<>();
		Matcher mt = MENTION.matcher(content);
		while (mt.find()) {
			names.add(mt.group(1));
		}
		for (String name : names) {
			jdbc.update("INSERT OR IGNORE INTO mentions (message_id, user_id) "
					+ "SELECT ?, u.id FROM users u JOIN workspace_members m ON m.user_id = u.id AND m.workspace_id = ? WHERE u.username = ?",
					msgId, workspaceId, name);
		}
	}

	/** リアクション一覧を付与する */
	private List<Map<String, Object>> enrich(List<Map<String, Object>> rows) {
		if (rows.isEmpty()) {
			return rows;
		}
		String in = String.join(",", rows.stream().map(r -> String.valueOf(r.get("id"))).toList());
		List<Map<String, Object>> rs = jdbc.queryForList(
				"SELECT message_id AS messageId, emoji, user_id AS userId FROM reactions WHERE message_id IN (" + in + ") ORDER BY rowid");
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
