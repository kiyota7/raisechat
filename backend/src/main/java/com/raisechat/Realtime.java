package com.raisechat;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 再取得の合図(ID/種別のみ)をSTOMPで配信する。データ本体はクライアントがRESTで取り直す。
 * 送信はトランザクションのコミット後に行う。
 */
@Component
public class Realtime {
	private final SimpMessagingTemplate template;
	private final JdbcTemplate jdbc;

	public Realtime(SimpMessagingTemplate template, JdbcTemplate jdbc) {
		this.template = template;
		this.jdbc = jdbc;
	}

	/** チャンネル購読者への通知。overviewがtrueならチャンネルメンバーのサイドバーも更新させる */
	public void channelEvent(long channelId, String type, Long messageId, Long parentId, boolean overview) {
		Map<String, Object> ev = new LinkedHashMap<>();
		ev.put("type", type);
		ev.put("channelId", channelId);
		ev.put("messageId", messageId);
		ev.put("parentId", parentId);
		List<Long> members = overview ? channelMembers(channelId) : List.of();
		long wsId = overview ? workspaceOf(channelId) : 0;
		afterCommit(() -> {
			template.convertAndSend("/topic/channels/" + channelId, ev);
			sendOverview(wsId, members);
		});
	}

	public void overviewToUsers(long workspaceId, Collection<Long> userIds) {
		overviewToUsers(workspaceId, userIds, "overview");
	}

	private void overviewToUsers(long workspaceId, Collection<Long> userIds, String type) {
		List<Long> users = List.copyOf(userIds);
		afterCommit(() -> sendOverview(workspaceId, users, type));
	}

	public void overviewToWorkspace(long workspaceId) {
		overviewToUsers(workspaceId, workspaceMembers(workspaceId));
	}

	public void overviewToChannelMembers(long channelId) {
		overviewToUsers(workspaceOf(channelId), channelMembers(channelId));
	}

	/** プロフィール変更を、所属ワークスペースの全メンバーに伝える。投稿者名・アバターはメッセージ本体に含まれるため、種別profileで一覧の取り直しも促す */
	public void profileChanged(long userId) {
		List<Long> wsIds = jdbc.queryForList("SELECT workspace_id FROM workspace_members WHERE user_id = ?", Long.class, userId);
		for (long wsId : wsIds) {
			overviewToUsers(wsId, workspaceMembers(wsId), "profile");
		}
	}

	public List<Long> workspaceMembers(long workspaceId) {
		return jdbc.queryForList("SELECT user_id FROM workspace_members WHERE workspace_id = ?", Long.class, workspaceId);
	}

	private List<Long> channelMembers(long channelId) {
		return jdbc.queryForList("SELECT user_id FROM channel_members WHERE channel_id = ?", Long.class, channelId);
	}

	private long workspaceOf(long channelId) {
		List<Long> ids = jdbc.queryForList("SELECT workspace_id FROM channels WHERE id = ?", Long.class, channelId);
		return ids.isEmpty() ? 0 : ids.get(0);
	}

	private void sendOverview(long workspaceId, Collection<Long> userIds) {
		sendOverview(workspaceId, userIds, "overview");
	}

	private void sendOverview(long workspaceId, Collection<Long> userIds, String type) {
		Map<String, Object> payload = Map.of("type", type, "workspaceId", workspaceId);
		for (long uid : userIds) {
			template.convertAndSendToUser(String.valueOf(uid), "/queue/overview", payload);
		}
	}

	private static void afterCommit(Runnable r) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					r.run();
				}
			});
		} else {
			r.run();
		}
	}
}
