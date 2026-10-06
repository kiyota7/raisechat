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
public class MessageController {
	private final JdbcTemplate jdbc;
	private final Access access;
	private final Realtime realtime;
	private final Sql sql;
	private final MessageQueries queries;
	private final MentionService mentions;

	public MessageController(JdbcTemplate jdbc, Access access, Realtime realtime, Sql sql, MessageQueries queries,
			MentionService mentions) {
		this.jdbc = jdbc;
		this.access = access;
		this.realtime = realtime;
		this.sql = sql;
		this.queries = queries;
		this.mentions = mentions;
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
		return queries.channelMessages(id);
	}

	@GetMapping("/messages/{id}/thread")
	public Map<String, Object> thread(@RequestAttribute("userId") long uid, @PathVariable long id) {
		Map<String, Object> m = access.message(id);
		access.requireChannelMember(channelIdOf(m), uid);
		long rootId = m.get("parentId") == null ? id : ((Number) m.get("parentId")).longValue();
		return queries.thread(rootId);
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
			if (channelIdOf(parent) != id || parent.get("parentId") != null) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "返信先が不正です");
			}
		}
		long msgId = sql.insert(
				"INSERT INTO messages (channel_id, user_id, parent_id, content, attachment_url, attachment_type) VALUES (?, ?, ?, ?, ?, ?)",
				id, uid, parentId, content, req.attachmentUrl(), req.attachmentType());
		mentions.save(msgId, content, ((Number) ch.get("workspaceId")).longValue());
		// 自分の投稿は既読扱いにする
		jdbc.update("UPDATE channel_members SET last_read_message_id = ? WHERE channel_id = ? AND user_id = ? AND last_read_message_id < ?",
				msgId, id, uid, msgId);
		realtime.channelEvent(id, "created", msgId, parentId, true);
		return queries.find(msgId);
	}

	@PutMapping("/messages/{id}")
	@Transactional
	public Map<String, Object> edit(@RequestAttribute("userId") long uid, @PathVariable long id,
			@Valid @RequestBody EditRequest req) {
		Map<String, Object> m = ownMessage(uid, id);
		String content = req.content().trim();
		jdbc.update("UPDATE messages SET content = ?, edited_at = ? WHERE id = ?", content, Timestamps.now(), id);
		mentions.clear(id);
		Map<String, Object> ch = access.channel(channelIdOf(m));
		mentions.save(id, content, ((Number) ch.get("workspaceId")).longValue());
		realtime.channelEvent(channelIdOf(m), "edited", id, parentOf(m), true);
		return queries.find(id);
	}

	@DeleteMapping("/messages/{id}")
	@Transactional
	public void delete(@RequestAttribute("userId") long uid, @PathVariable long id) {
		Map<String, Object> m = ownMessage(uid, id);
		jdbc.update("UPDATE messages SET deleted = 1, content = '', attachment_url = NULL, attachment_type = NULL WHERE id = ?", id);
		mentions.clear(id);
		realtime.channelEvent(channelIdOf(m), "deleted", id, parentOf(m), true);
	}

	/** 同じ絵文字を再度押すと取り消し */
	@PostMapping("/messages/{id}/reactions")
	@Transactional
	public void react(@RequestAttribute("userId") long uid, @PathVariable long id, @Valid @RequestBody ReactionRequest req) {
		Map<String, Object> m = access.message(id);
		access.requireChannelMember(channelIdOf(m), uid);
		int removed = jdbc.update("DELETE FROM reactions WHERE message_id = ? AND user_id = ? AND emoji = ?", id, uid, req.emoji());
		if (removed == 0) {
			jdbc.update("INSERT INTO reactions (message_id, user_id, emoji) VALUES (?, ?, ?)", id, uid, req.emoji());
		}
		realtime.channelEvent(channelIdOf(m), "reacted", id, parentOf(m), false);
	}

	/** 自分の、削除されていないメッセージを返す。そうでなければ例外 */
	private Map<String, Object> ownMessage(long uid, long id) {
		Map<String, Object> m = access.message(id);
		if (((Number) m.get("userId")).longValue() != uid) {
			throw new ApiException(HttpStatus.FORBIDDEN, "自分のメッセージのみ操作できます");
		}
		if (MessageQueries.isDeleted(m)) {
			throw new ApiException(HttpStatus.NOT_FOUND, "メッセージは削除されています");
		}
		return m;
	}

	private static long channelIdOf(Map<String, Object> m) {
		return ((Number) m.get("channelId")).longValue();
	}

	private static Long parentOf(Map<String, Object> m) {
		return m.get("parentId") == null ? null : ((Number) m.get("parentId")).longValue();
	}
}
