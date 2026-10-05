package com.raisechat;

import java.util.Collection;
import java.util.List;

/**
 * オンライン状態の保存先。サーバー1台のときはメモリ(InMemoryPresenceStore)、
 * 複数台のときはRedis(RedisPresenceStore)で、全サーバーの接続をまとめて判定する。
 */
public interface PresenceStore {
	/** 接続を記録する。このユーザーが「オフライン→オンライン」になったとき true */
	boolean connect(long userId, String sessionId);

	/** 接続を外す。そのセッションのユーザーIDを返す(このサーバーが知らないセッションなら null) */
	Long disconnect(String sessionId);

	/** ユーザーに有効な接続が1つもなければ、オンラインの記録を外す。外したのがこの呼び出しなら true */
	boolean markOfflineIfNoConnection(long userId);

	boolean isOnline(long userId);

	/** 渡されたユーザーのうち、オンラインのIDだけを返す */
	List<Long> onlineAmong(Collection<Long> userIds);

	/** 定期処理の間隔(ミリ秒)。0なら定期処理は不要(メモリ版) */
	default long maintenanceIntervalMs() {
		return 0;
	}

	/** 定期処理: このサーバーの接続の有効期限を延ばす。オンラインに戻ったユーザーを返す */
	default List<Long> heartbeat() {
		return List.of();
	}

	/** 定期処理: 有効期限が切れた接続(落ちたサーバーの分)しか残っていないユーザーをオフラインにする。オフラインにしたユーザーを返す */
	default List<Long> sweep() {
		return List.of();
	}
}
