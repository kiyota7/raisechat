package com.raisechat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** 登録時のパスワードの検査(長さは別途 @Size で検査する)。問題があればメッセージを返す */
final class PasswordPolicy {
	/** BCryptが扱える最大のバイト数。超えると例外になる */
	static final int MAX_BYTES = 72;

	/** よくある弱いパスワード(小文字で比較する) */
	private static final Set<String> COMMON = Set.of("password", "password1", "password12", "password123", "passw0rd", "p@ssw0rd",
			"12345678", "123456789", "1234567890", "qwertyui", "qwerty123", "qwertyuiop", "abcd1234", "abc12345", "11111111",
			"00000000", "iloveyou", "admin123", "letmein1", "welcome1", "monkey12", "dragon12", "football", "baseball", "trustno1",
			"raisechat", "raisechat1");

	private PasswordPolicy() {
	}

	static Optional<String> check(String username, String password) {
		if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
			return Optional.of("パスワードが長すぎます(" + MAX_BYTES + "バイトまで。日本語などは1文字が複数バイトになります)");
		}
		String lower = password.toLowerCase(Locale.ROOT);
		String user = username.toLowerCase(Locale.ROOT);
		if (lower.equals(user) || (user.length() >= 3 && lower.contains(user))) {
			return Optional.of("パスワードにユーザーIDを含めないでください");
		}
		if (COMMON.contains(lower)) {
			return Optional.of("よくあるパスワードは使えません。別のパスワードにしてください");
		}
		if (password.codePoints().distinct().count() == 1) {
			return Optional.of("同じ文字の繰り返しは使えません");
		}
		if (isRun(password)) {
			return Optional.of("連続した文字の並び(12345678 や abcdefgh など)は使えません");
		}
		return Optional.empty();
	}

	/** 全体が、1つずつ増える(または減る)連続した文字の並びかどうか */
	private static boolean isRun(String s) {
		int[] cps = s.codePoints().toArray();
		if (cps.length < 2) {
			return false;
		}
		int step = cps[1] - cps[0];
		if (step != 1 && step != -1) {
			return false;
		}
		for (int i = 2; i < cps.length; i++) {
			if (cps[i] - cps[i - 1] != step) {
				return false;
			}
		}
		return true;
	}
}
