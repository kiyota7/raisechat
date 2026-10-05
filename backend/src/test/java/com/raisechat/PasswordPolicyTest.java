package com.raisechat;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {
	private static String check(String user, String pw) {
		return PasswordPolicy.check(user, pw).orElse(null);
	}

	@Test
	void acceptsOrdinaryPasswords() {
		assertNull(check("alice", "Chat-Test-1x"));
		assertNull(check("alice", "correct horse battery"));
		assertNull(check("alice", "abcdefgz")); // 並びに近いが、連続ではない
	}

	@Test
	void limitsToBcryptBytesNotCharacters() {
		assertNull(check("alice", "Xy7!".repeat(18))); // ちょうど72バイト
		assertNotNull(check("alice", "Xy7!".repeat(18) + "z")); // 73バイト
		// 日本語30文字は90バイト。文字数(64以内)では通るが、BCryptでは例外になる長さ
		assertTrue(check("alice", "あ".repeat(30)).contains("長すぎ"));
		assertNull(check("alice", "春夏秋冬東西南北上下左右前後内外大小高低長短新旧")); // 24文字=72バイト
	}

	@Test
	void rejectsPasswordsContainingTheUsername() {
		assertNotNull(check("alice", "alice"));
		assertNotNull(check("alice", "ALICE-Pass-9"));
		assertNotNull(check("alice", "my-Alice-2026"));
		// 2文字以下のユーザーIDは、含むかどうかの検査をしない(誤検知が多いため)。同一かどうかだけを見る
		assertNull(check("ab", "xxabxx-Pass-9"));
		assertNotNull(check("ab", "AB"));
	}

	@Test
	void rejectsCommonPasswordsCaseInsensitively() {
		assertNotNull(check("alice", "password1"));
		assertNotNull(check("alice", "Password1"));
		assertNotNull(check("alice", "12345678"));
		assertNotNull(check("alice", "QWERTYUI"));
	}

	@Test
	void rejectsRepeatsAndRuns() {
		assertNotNull(check("alice", "aaaaaaaa"));
		assertNotNull(check("alice", "abcdefgh"));
		assertNotNull(check("alice", "87654321"));
		assertNotNull(check("alice", "ＡＢＣＤＥＦＧＨ")); // 全角でも連続した並び
	}
}
