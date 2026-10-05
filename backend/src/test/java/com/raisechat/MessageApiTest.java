package com.raisechat;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/** メッセージAPI(投稿・一覧・スレッド・編集・削除・リアクション・メンション)の挙動 */
class MessageApiTest extends ApiTestBase {
	@Test
	void postTrimsContentAndMarksTheAuthorsOwnMessageAsRead() throws Exception {
		TestUser owner = register("own"), guest = register("gst");
		long ws = createWorkspace(owner);
		invite(ws, owner, guest);
		long general = channelId(overview(ws, owner), "general");

		JsonNode m = ok("POST", "/channels/" + general + "/messages", guest.token(), "{\"content\":\"  hi there  \"}");
		assertEquals("hi there", m.get("content").asText());
		assertEquals(guest.id(), m.get("userId").asLong());
		assertFalse(m.get("deleted").asBoolean());
		assertEquals(0, m.get("replyCount").asInt());
		assertEquals(0, m.get("reactions").size());

		assertEquals(0, channel(overview(ws, guest), "general").get("unread").asInt()); // 自分の投稿は未読にならない
		assertEquals(1, channel(overview(ws, owner), "general").get("unread").asInt());
	}

	@Test
	void rejectsInvalidPosts() throws Exception {
		TestUser owner = register("own");
		long ws = createWorkspace(owner);
		long general = channelId(overview(ws, owner), "general");
		long other = ok("POST", "/workspaces/" + ws + "/channels", owner.token(), "{\"name\":\"other\",\"isPrivate\":false}").get("id").asLong();
		long m = post(general, owner, "root");
		long r = reply(general, owner, "reply", m);
		long inOther = post(other, owner, "elsewhere");
		String path = "/channels/" + general + "/messages";

		assertEquals(400, status("POST", path, owner.token(), "{\"content\":\"   \"}")); // 空
		assertEquals(400, status("POST", path, owner.token(), "{\"content\":\"" + "x".repeat(5001) + "\"}")); // 長すぎる
		assertEquals(400, status("POST", path, owner.token(), "{\"content\":\"a\",\"attachmentUrl\":\"http://evil.example/x.png\"}")); // 添付が不正
		assertEquals(400, status("POST", path, owner.token(), "{\"content\":\"a\",\"parentId\":" + r + "}")); // 返信への返信
		assertEquals(400, status("POST", path, owner.token(), "{\"content\":\"a\",\"parentId\":" + inOther + "}")); // 別チャンネルのメッセージへの返信
		assertEquals(404, status("POST", path, owner.token(), "{\"content\":\"a\",\"parentId\":999999}")); // 返信先がない
		assertEquals(401, status("POST", path, null, "{\"content\":\"a\"}"));
	}

	@Test
	void attachmentOnlyMessageIsAllowed() throws Exception {
		TestUser owner = register("own");
		long ws = createWorkspace(owner);
		long general = channelId(overview(ws, owner), "general");
		JsonNode m = ok("POST", "/channels/" + general + "/messages", owner.token(),
				"{\"attachmentUrl\":\"/uploads/a.png\",\"attachmentType\":\"image\"}");
		assertEquals("", m.get("content").asText());
		assertEquals("/uploads/a.png", m.get("attachmentUrl").asText());
		assertEquals("image", m.get("attachmentType").asText());
	}

	@Test
	void listIsInOrderAndThreadWorksFromEitherTheParentOrAReply() throws Exception {
		TestUser owner = register("own"), guest = register("gst"), stranger = register("str");
		long ws = createWorkspace(owner);
		invite(ws, owner, guest);
		long general = channelId(overview(ws, owner), "general");
		long m1 = post(general, owner, "first");
		long m2 = post(general, guest, "second");
		long r1 = reply(general, guest, "r1", m1);
		reply(general, owner, "r2", m1);

		JsonNode list = ok("GET", "/channels/" + general + "/messages", owner.token(), null);
		assertEquals(2, list.size()); // 返信は一覧に出ない
		assertEquals(m1, list.get(0).get("id").asLong());
		assertEquals(m2, list.get(1).get("id").asLong());
		assertEquals(2, list.get(0).get("replyCount").asInt());

		for (long id : new long[] { m1, r1 }) {
			JsonNode t = ok("GET", "/messages/" + id + "/thread", guest.token(), null);
			assertEquals(m1, t.get("parent").get("id").asLong());
			assertEquals(2, t.get("replies").size());
			assertEquals("r1", t.get("replies").get(0).get("content").asText());
		}
		assertEquals(403, status("GET", "/messages/" + m1 + "/thread", stranger.token(), null));
		assertEquals(404, status("GET", "/messages/999999/thread", owner.token(), null));
		assertEquals(403, status("GET", "/channels/" + general + "/messages", stranger.token(), null));
	}

	@Test
	void editUpdatesContentMentionsAndMarksAsEdited() throws Exception {
		TestUser owner = register("own"), guest = register("gst");
		long ws = createWorkspace(owner);
		invite(ws, owner, guest);
		long general = channelId(overview(ws, owner), "general");
		long m = post(general, owner, "hello @" + guest.name());
		assertEquals(1, channel(overview(ws, guest), "general").get("mentions").asInt());

		JsonNode edited = ok("PUT", "/messages/" + m, owner.token(), "{\"content\":\"  no mention now  \"}");
		assertEquals("no mention now", edited.get("content").asText());
		assertFalse(edited.get("editedAt").isNull());
		assertEquals(0, channel(overview(ws, guest), "general").get("mentions").asInt()); // メンションが付け替えられる

		assertEquals(403, status("PUT", "/messages/" + m, guest.token(), "{\"content\":\"x\"}")); // 他人のメッセージ
		assertEquals(400, status("PUT", "/messages/" + m, owner.token(), "{\"content\":\" \"}"));
		assertEquals(404, status("PUT", "/messages/999999", owner.token(), "{\"content\":\"x\"}"));
	}

	@Test
	void deleteKeepsAPlaceholderOnlyWhenTheMessageHasReplies() throws Exception {
		TestUser owner = register("own"), guest = register("gst");
		long ws = createWorkspace(owner);
		invite(ws, owner, guest);
		long general = channelId(overview(ws, owner), "general");
		long withReply = post(general, owner, "parent");
		long reply = reply(general, guest, "child", withReply);
		long alone = post(general, owner, "alone");

		assertEquals(403, status("DELETE", "/messages/" + alone, guest.token(), null)); // 他人のメッセージ
		ok("DELETE", "/messages/" + alone, owner.token(), null);
		assertEquals(404, status("DELETE", "/messages/" + alone, owner.token(), null)); // 二重の削除
		assertEquals(404, status("PUT", "/messages/" + alone, owner.token(), "{\"content\":\"x\"}"));

		ok("DELETE", "/messages/" + withReply, owner.token(), null);
		JsonNode list = ok("GET", "/channels/" + general + "/messages", guest.token(), null);
		assertEquals(1, list.size()); // 返信のない削除済みは消え、返信つきは残る
		assertEquals(withReply, list.get(0).get("id").asLong());
		assertTrue(list.get(0).get("deleted").asBoolean());
		assertEquals("", list.get(0).get("content").asText());
		assertEquals(1, list.get(0).get("replyCount").asInt());

		ok("DELETE", "/messages/" + reply, guest.token(), null); // 返信を削除すると、親の返信数が減る
		assertEquals(0, ok("GET", "/messages/" + withReply + "/thread", owner.token(), null).get("replies").size());
		assertEquals(0, ok("GET", "/channels/" + general + "/messages", guest.token(), null).size()); // 返信がなくなった削除済みの親は消える
	}

	@Test
	void reactionsToggleAndGroupByEmojiInOrder() throws Exception {
		TestUser owner = register("own"), guest = register("gst"), stranger = register("str");
		long ws = createWorkspace(owner);
		invite(ws, owner, guest);
		long general = channelId(overview(ws, owner), "general");
		long m = post(general, owner, "react to me");
		String path = "/messages/" + m + "/reactions";

		ok("POST", path, owner.token(), "{\"emoji\":\"👍\"}");
		ok("POST", path, guest.token(), "{\"emoji\":\"👍\"}");
		ok("POST", path, owner.token(), "{\"emoji\":\"🎉\"}");
		JsonNode reactions = ok("GET", "/channels/" + general + "/messages", owner.token(), null).get(0).get("reactions");
		assertEquals(2, reactions.size());
		assertEquals("👍", reactions.get(0).get("emoji").asText());
		assertEquals(2, reactions.get(0).get("userIds").size());
		assertEquals("🎉", reactions.get(1).get("emoji").asText());
		assertEquals(1, reactions.get(1).get("userIds").size());

		ok("POST", path, owner.token(), "{\"emoji\":\"👍\"}"); // もう一度押すと取り消し
		reactions = ok("GET", "/channels/" + general + "/messages", owner.token(), null).get(0).get("reactions");
		assertEquals(1, reactions.get(0).get("userIds").size());
		assertEquals(guest.id(), reactions.get(0).get("userIds").get(0).asLong());

		assertEquals(403, status("POST", path, stranger.token(), "{\"emoji\":\"👍\"}"));
		assertEquals(400, status("POST", path, owner.token(), "{\"emoji\":\"\"}"));
		assertEquals(400, status("POST", path, owner.token(), "{\"emoji\":\"" + "x".repeat(17) + "\"}"));
		assertEquals(404, status("POST", "/messages/999999/reactions", owner.token(), "{\"emoji\":\"👍\"}"));
	}

	@Test
	void mentionsCountOncePerMessageAndOnlyForWorkspaceMembers() throws Exception {
		TestUser owner = register("own"), guest = register("gst"), outsider = register("out");
		long ws = createWorkspace(owner);
		invite(ws, owner, guest);
		long general = channelId(overview(ws, owner), "general");

		post(general, owner, "@" + guest.name() + " @" + guest.name() + " @" + outsider.name() + " @nobody_here");
		assertEquals(1, channel(overview(ws, guest), "general").get("mentions").asInt()); // 同じ人への重複は1回
		assertEquals(0, ok("GET", "/workspaces", outsider.token(), null).size()); // ワークスペース外の人には何も起きない
	}
}
