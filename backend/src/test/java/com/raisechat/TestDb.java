package com.raisechat;

/** テストが使うDBの設定。テストごとに別のSQLiteファイルを使う */
final class TestDb {
	static final String URL = "spring.datasource.url=jdbc:sqlite:target/test-${random.uuid}.db?foreign_keys=true";
	static final String DRIVER = "spring.datasource.driver-class-name=org.sqlite.JDBC";
	static final String POOL = "spring.datasource.hikari.maximum-pool-size=1";
	static final String USER = "spring.datasource.username=";
	static final String PASSWORD = "spring.datasource.password=";

	private TestDb() {
	}
}
