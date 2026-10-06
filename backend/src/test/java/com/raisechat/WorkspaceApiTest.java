package com.raisechat;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/** ワークスペース・チャンネル・DM・検索・キックのAPIの挙動 */
class WorkspaceApiTest extends ApiTestBase {
	@Test
	void creatingAWorkspaceMakesTheOwnerAMemberOfGeneral() throws Exception {
		TestUser owner = register("own");
		JsonNode ws = ok("POST", "/workspaces", owner.token(), "{\"name\":\"  Team  \"}");
		assertEquals("Team", ws.get("name").asText());
		assertEquals(owner.id(), ws.get("ownerId").asLong());

		JsonNode list = ok("GET", "/workspaces", owner.token(), null);
		assertEquals(1, list.size());
		JsonNode ov = overview(ws.get("id").asLong(), owner);
		assertEquals(1, ov.get("members").size());
		assertEquals(1, ov.get("channels").size());
		assertEquals("general", ov.get("channels").get(0).get("name").asText());
		assertEquals(1, ov.get("channels").get(0).get("joined").asInt());
		assertEquals(0, ov.get("dms").size());

		assertEquals(400, status("POST", "/workspaces", owner.token(), "{\"name\":\"   \"}"));
		assertEquals(400, status("POST", "/workspaces", owner.token(), "{\"name\":\"" + "x".repeat(31) + "\"}"));
	}

	@Test
	void inviteRulesAndNewMembersStartWithoutUnreadHistory() throws Exception {
		TestUser owner = register("own"), guest = register("gst"), other = register("oth");
		long ws = createWorkspace(owner);
		long general = channelId(overview(ws, owner), "general");
		post(general, owner, "before the guest joined");

		String path = "/workspaces/" + ws + "/invite";
		assertEquals(403, status("POST", path, guest.token(), "{\"username\":\"" + other.name() + "\"}")); // オーナー以外
		assertEquals(404, status("POST", path, owner.token(), "{\"username\":\"nobody_here\"}"));
		assertEquals(400, status("POST", path, owner.token(), "{\"username\":\" \"}"));
		invite(ws, owner, guest);
		assertEquals(409, status("POST", path, owner.token(), "{\"username\":\"" + guest.name() + "\"}")); // 既にメンバー

		JsonNode ov = overview(ws, guest);
		assertEquals(2, ov.get("members").size());
		assertEquals(1, channel(ov, "general").get("joined").asInt()); // #general に自動で参加する
		assertEquals(0, channel(ov, "general").get("unread").asInt()); // 参加前の投稿は未読にならない
		assertEquals(403, status("GET", "/workspaces/" + ws, other.token(), null));
		// メンバーかどうかの確認が先に行われるため、存在しないワークスペースも403になる
		assertEquals(403, status("GET", "/workspaces/999999", owner.token(), null));
	}

	@Test
	void overviewCountsUnreadAndMentionsPerUserUntilMarkedRead() throws Exception {
		TestUser owner = register("own"), guest = register("gst");
		long ws = createWorkspace(owner);
		invite(ws, owner, guest);
		long general = channelId(overview(ws, owner), "general");

		post(general, owner, "one");
		post(general, owner, "two @" + guest.name());
		JsonNode g = channel(overview(ws, guest), "general");
		assertEquals(2, g.get("unread").asInt());
		assertEquals(1, g.get("mentions").asInt());
		assertEquals(0, channel(overview(ws, owner), "general").get("unread").asInt()); // 自分の投稿は未読にならない

		ok("POST", "/channels/" + general + "/read", guest.token(), null);
		g = channel(overview(ws, guest), "general");
		assertEquals(0, g.get("unread").asInt());
		assertEquals(0, g.get("mentions").asInt());

		post(general, owner, "three @" + guest.name());
		g = channel(overview(ws, guest), "general");
		assertEquals(1, g.get("unread").asInt());
		assertEquals(1, g.get("mentions").asInt());

		post(general, guest, "mine"); // 自分が投稿すると、それまでの分も既読になる
		assertEquals(0, channel(overview(ws, guest), "general").get("unread").asInt());
		assertEquals(1, channel(overview(ws, owner), "general").get("unread").asInt());
		ok("POST", "/channels/" + general + "/read", owner.token(), null);
		assertEquals(0, channel(overview(ws, owner), "general").get("unread").asInt());
	}

	@Test
	void channelNamesAreNormalizedAndDuplicatesRejected() throws Exception {
		TestUser owner = register("own"), guest = register("gst");
		long ws = createWorkspace(owner);
		invite(ws, owner, guest);
		String path = "/workspaces/" + ws + "/channels";

		JsonNode ch = ok("POST", path, owner.token(), "{\"name\":\"  My   Channel \",\"isPrivate\":false}");
		assertEquals("my-channel", ch.get("name").asText());
		assertEquals(409, status("POST", path, guest.token(), "{\"name\":\"MY-CHANNEL\",\"isPrivate\":false}"));
		assertEquals(400, status("POST", path, owner.token(), "{\"name\":\" \",\"isPrivate\":false}"));
		assertEquals(400, status("POST", path, owner.token(), "{\"name\":\"" + "y".repeat(31) + "\",\"isPrivate\":false}"));
		assertEquals(403, status("POST", "/workspaces/" + ws + "/channels", register("out").token(), "{\"name\":\"x\",\"isPrivate\":false}"));
	}

	@Test
	void publicChannelsCanBeJoinedButPrivateOnesNeedAnInvite() throws Exception {
		TestUser owner = register("own"), guest = register("gst"), third = register("thr");
		long ws = createWorkspace(owner);
		invite(ws, owner, guest);
		invite(ws, owner, third);
		long pub = ok("POST", "/workspaces/" + ws + "/channels", owner.token(), "{\"name\":\"pub\",\"isPrivate\":false}").get("id").asLong();
		long priv = ok("POST", "/workspaces/" + ws + "/channels", owner.token(), "{\"name\":\"secret\",\"isPrivate\":true}").get("id").asLong();

		JsonNode ov = overview(ws, guest);
		assertEquals(2, ov.get("channels").size()); // general と pub。プライベートは見えない
		assertEquals(0, channel(ov, "pub").get("joined").asInt());
		assertEquals(403, status("GET", "/channels/" + pub + "/messages", guest.token(), null)); // 参加するまで読めない
		ok("POST", "/channels/" + pub + "/join", guest.token(), null);
		assertEquals(0, ok("GET", "/channels/" + pub + "/messages", guest.token(), null).size());

		assertEquals(403, status("POST", "/channels/" + priv + "/join", guest.token(), null));
		assertEquals(403, status("POST", "/channels/" + priv + "/members", guest.token(), "{\"username\":\"" + third.name() + "\"}")); // メンバー以外は招待できない
		ok("POST", "/channels/" + priv + "/members", owner.token(), "{\"username\":\"" + third.name() + "\"}");
		assertEquals(404, status("POST", "/channels/" + priv + "/members", owner.token(), "{\"username\":\"nobody_here\"}"));
		assertEquals(2, ok("GET", "/channels/" + priv + "/members", third.token(), null).size());
		assertEquals(3, overview(ws, third).get("channels").size());
	}

	@Test
	void openingADmIsIdempotentAndValidated() throws Exception {
		TestUser owner = register("own"), guest = register("gst"), third = register("thr"), outsider = register("out");
		long ws = createWorkspace(owner);
		invite(ws, owner, guest);
		invite(ws, owner, third);
		String path = "/workspaces/" + ws + "/dms";

		JsonNode dm = ok("POST", path, owner.token(), "{\"userId\":" + guest.id() + "}");
		long dmId = dm.get("id").asLong();
		assertEquals(guest.id(), dm.get("userId").asLong());
		assertEquals(dmId, ok("POST", path, guest.token(), "{\"userId\":" + owner.id() + "}").get("id").asLong()); // 相手から開いても同じDM
		assertEquals(400, status("POST", path, owner.token(), "{\"userId\":" + owner.id() + "}")); // 自分自身
		assertEquals(403, status("POST", path, owner.token(), "{\"userId\":" + outsider.id() + "}")); // ワークスペース外の人

		assertEquals(1, overview(ws, guest).get("dms").size());
		assertEquals(owner.id(), overview(ws, guest).get("dms").get(0).get("userId").asLong());
		assertEquals(0, overview(ws, third).get("dms").size());
		post(dmId, owner, "private talk");
		assertEquals(403, status("GET", "/channels/" + dmId + "/messages", third.token(), null)); // 第三者は読めない
		assertEquals(400, status("POST", "/channels/" + dmId + "/members", owner.token(), "{\"username\":\"" + third.name() + "\"}")); // DMには招待できない
	}

	@Test
	void searchEscapesWildcardsAndOnlyCoversChannelsTheUserBelongsTo() throws Exception {
		TestUser owner = register("own"), guest = register("gst"), third = register("thr");
		long ws = createWorkspace(owner);
		invite(ws, owner, guest);
		invite(ws, owner, third);
		long general = channelId(overview(ws, owner), "general");
		post(general, owner, "hello world");
		post(general, owner, "100% sure");
		post(general, owner, "snake_case name");
		post(general, owner, "snakeXcase decoy");
		long dm = ok("POST", "/workspaces/" + ws + "/dms", owner.token(), "{\"userId\":" + guest.id() + "}").get("id").asLong();
		post(dm, owner, "secret between us");

		assertEquals(1, search(ws, guest, "hello").size());
		assertEquals(1, search(ws, guest, "HeLLo").size()); // 英字の大文字・小文字は区別しない(SQLiteでもPostgreSQLでも同じ)
		assertEquals(1, search(ws, guest, "100%").size()); // % はワイルドカードではない
		assertEquals(1, search(ws, guest, "snake_case").size()); // _ もワイルドカードではない(snakeXcase に当たらない)
		assertEquals(1, search(ws, guest, "%").size());
		assertEquals(0, search(ws, guest, " ").size());

		JsonNode hit = search(ws, guest, "secret");
		assertEquals(1, hit.size());
		assertEquals(1, hit.get(0).get("isDm").asInt());
		assertEquals(dm, hit.get(0).get("channelId").asLong());
		assertEquals(0, search(ws, third, "secret").size()); // 自分が入っていないDMは検索に出ない
		assertEquals(401, status("GET", "/workspaces/" + ws + "/search?q=hello", null, null));
		assertEquals(403, status("GET", "/workspaces/" + ws + "/search?q=hello", register("out").token(), null));
	}

	private JsonNode search(long ws, TestUser u, String q) throws Exception {
		// MockMvcが、パスを組み立てるときにエンコードする。ここでエンコードすると二重になる
		return ok("GET", "/workspaces/" + ws + "/search?q=" + q, u.token(), null);
	}

	@Test
	void channelDeletionRules() throws Exception {
		TestUser owner = register("own"), guest = register("gst");
		long ws = createWorkspace(owner);
		invite(ws, owner, guest);
		long general = channelId(overview(ws, owner), "general");
		long pub = ok("POST", "/workspaces/" + ws + "/channels", owner.token(), "{\"name\":\"pub\",\"isPrivate\":false}").get("id").asLong();
		long dm = ok("POST", "/workspaces/" + ws + "/dms", owner.token(), "{\"userId\":" + guest.id() + "}").get("id").asLong();
		post(pub, owner, "bye");

		assertEquals(400, status("DELETE", "/channels/" + general, owner.token(), null)); // #general は削除できない
		assertEquals(400, status("DELETE", "/channels/" + dm, owner.token(), null)); // DMも削除できない
		assertEquals(403, status("DELETE", "/channels/" + pub, guest.token(), null)); // オーナーのみ
		ok("DELETE", "/channels/" + pub, owner.token(), null);
		assertEquals(404, status("GET", "/channels/" + pub + "/messages", owner.token(), null)); // メッセージごと消える
		assertEquals(1, overview(ws, owner).get("channels").size());
	}

	@Test
	void kickRemovesMembershipsAndFollowsTheOwnerRules() throws Exception {
		TestUser owner = register("own"), guest = register("gst");
		long ws = createWorkspace(owner);
		invite(ws, owner, guest);
		long priv = ok("POST", "/workspaces/" + ws + "/channels", owner.token(), "{\"name\":\"secret\",\"isPrivate\":true}").get("id").asLong();
		ok("POST", "/channels/" + priv + "/members", owner.token(), "{\"username\":\"" + guest.name() + "\"}");

		assertEquals(400, status("DELETE", "/workspaces/" + ws + "/members/" + owner.id(), owner.token(), null)); // オーナー自身
		assertEquals(403, status("DELETE", "/workspaces/" + ws + "/members/" + owner.id(), guest.token(), null)); // オーナー以外
		ok("DELETE", "/workspaces/" + ws + "/members/" + guest.id(), owner.token(), null);

		assertEquals(403, status("GET", "/workspaces/" + ws, guest.token(), null));
		assertEquals(403, status("GET", "/channels/" + priv + "/messages", guest.token(), null));
		assertEquals(1, overview(ws, owner).get("members").size());
		assertEquals(1, ok("GET", "/channels/" + priv + "/members", owner.token(), null).size());

		invite(ws, owner, guest); // 再招待できる
		assertEquals(2, overview(ws, guest).get("members").size());
	}
}
