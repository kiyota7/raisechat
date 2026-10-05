package com.raisechat;

import java.sql.PreparedStatement;
import java.sql.Statement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

/** JdbcTemplateの補助 */
@Component
public class Sql {
	private final JdbcTemplate jdbc;

	public Sql(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** INSERTを実行し、生成されたIDを返す。nullの引数はNULLとして入る */
	public long insert(String sql, Object... args) {
		GeneratedKeyHolder kh = new GeneratedKeyHolder();
		jdbc.update(con -> {
			PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
			for (int i = 0; i < args.length; i++) {
				ps.setObject(i + 1, args[i]);
			}
			return ps;
		}, kh);
		return kh.getKey().longValue();
	}
}
