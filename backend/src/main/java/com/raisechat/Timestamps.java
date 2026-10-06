package com.raisechat;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** DBに保存する時刻の文字列。形式は、created_at の既定値と同じ(例: 2026-10-05T10:30:00.123Z) */
final class Timestamps {
	private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

	private Timestamps() {
	}

	static String now() {
		return FORMAT.format(Instant.now());
	}
}
