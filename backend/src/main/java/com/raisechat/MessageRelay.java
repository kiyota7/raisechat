package com.raisechat;

/**
 * STOMPのメッセージの配信先。サーバー1台のときは、自分に繋がっている人にだけ送る(LocalMessageRelay)。
 * 複数台のときは、Redisを通して、全サーバーの、それぞれに繋がっている人へ届ける(RedisMessageRelay)。
 */
public interface MessageRelay {
	/** トピック(/topic/...)の購読者へ送る */
	void toTopic(String destination, Object payload);

	/** 特定のユーザー(/user/{userId}/...)へ送る。destination は /queue/... の形 */
	void toUser(String userId, String destination, Object payload);
}
