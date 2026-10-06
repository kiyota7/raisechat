package com.raisechat;

import java.util.ArrayList;
import java.util.List;

/**
 * テストが使うDBの設定。既定は、テストごとに別のSQLiteファイル。
 * 環境変数 TEST_DB_URL(と TEST_DB_DRIVER / TEST_DB_POOL / TEST_DB_USER / TEST_DB_PASSWORD)を指定すると、
 * そのDB(PostgreSQLなど)で同じテストを実行する。PostgreSQLでは、全テストが1つのDBを共有するので、
 * テストは、ユーザー名などを、テストごとに別の値にしている。
 */
final class TestDb {
	static final String URL = "spring.datasource.url=${TEST_DB_URL:jdbc:sqlite:target/test-${random.uuid}.db?foreign_keys=true}";
	static final String DRIVER = "spring.datasource.driver-class-name=${TEST_DB_DRIVER:org.sqlite.JDBC}";
	static final String POOL = "spring.datasource.hikari.maximum-pool-size=${TEST_DB_POOL:1}";
	static final String USER = "spring.datasource.username=${TEST_DB_USER:}";
	static final String PASSWORD = "spring.datasource.password=${TEST_DB_PASSWORD:}";

	private TestDb() {
	}

	/** SpringApplicationBuilder に、コマンドライン引数の形で渡す設定(sqliteFile は、環境変数がないときに使うSQLiteのファイル) */
	static List<String> settings(String sqliteFile) {
		String url = System.getenv("TEST_DB_URL");
		List<String> s = new ArrayList<>();
		if (url == null || url.isBlank()) {
			s.add("spring.datasource.url=jdbc:sqlite:" + sqliteFile + "?journal_mode=WAL&busy_timeout=5000&foreign_keys=true");
			return s;
		}
		s.add("spring.datasource.url=" + url);
		s.add("spring.datasource.driver-class-name=" + System.getenv().getOrDefault("TEST_DB_DRIVER", "org.postgresql.Driver"));
		s.add("spring.datasource.hikari.maximum-pool-size=" + System.getenv().getOrDefault("TEST_DB_POOL", "10"));
		s.add("spring.datasource.username=" + System.getenv().getOrDefault("TEST_DB_USER", ""));
		s.add("spring.datasource.password=" + System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
		return s;
	}
}
