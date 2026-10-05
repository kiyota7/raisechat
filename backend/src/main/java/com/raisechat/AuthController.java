package com.raisechat;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class AuthController {
	private static final String USERNAME_TAKEN = "このユーザーIDは既に使われています";
	private static final String USER_SQL =
			"SELECT id, username, display_name AS displayName, status, avatar_url AS avatarUrl FROM users WHERE id = ?";

	private final JdbcTemplate jdbc;
	private final JwtService jwt;
	private final FileStorage storage;
	private final Realtime realtime;
	private final LoginAttemptLimiter limiter;
	private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
	/** 存在しないユーザーIDのときも、パスワードの照合にかかる時間を揃えるためのダミー */
	private final String dummyHash = encoder.encode("raisechat-dummy-password");

	public AuthController(JdbcTemplate jdbc, JwtService jwt, FileStorage storage, Realtime realtime, LoginAttemptLimiter limiter) {
		this.jdbc = jdbc;
		this.jwt = jwt;
		this.storage = storage;
		this.realtime = realtime;
		this.limiter = limiter;
	}

	public record RegisterRequest(
			@NotBlank(message = "ユーザーIDを入力してください")
			@Pattern(regexp = "[A-Za-z0-9_]{3,20}", message = "ユーザーIDは半角英数字とアンダースコアの3〜20文字で入力してください") String username,
			@NotBlank(message = "パスワードを入力してください")
			@Size(min = 8, max = 64, message = "パスワードは8〜64文字で入力してください") String password,
			@Size(max = 30, message = "表示名は30文字以内で入力してください") String displayName) {
	}

	public record LoginRequest(@NotBlank(message = "ユーザーIDを入力してください") String username,
			@NotBlank(message = "パスワードを入力してください") String password) {
	}

	public record ProfileRequest(
			@NotBlank(message = "表示名を入力してください") @Size(max = 30, message = "表示名は30文字以内で入力してください") String displayName,
			@Size(max = 100, message = "ステータスは100文字以内で入力してください") String status) {
	}

	@PostMapping("/auth/register")
	public Map<String, Object> register(@Valid @RequestBody RegisterRequest req) {
		PasswordPolicy.check(req.username(), req.password()).ifPresent(msg -> {
			throw new ApiException(HttpStatus.BAD_REQUEST, msg);
		});
		String display = req.displayName() == null || req.displayName().isBlank() ? req.username() : req.displayName().trim();
		// 先に確認する(使われていれば、パスワードのハッシュ化という重い計算をせずに済む)
		Integer taken = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, req.username());
		if (taken != null && taken > 0) {
			throw new ApiException(HttpStatus.CONFLICT, USERNAME_TAKEN);
		}
		try {
			jdbc.update("INSERT INTO users (username, password_hash, display_name) VALUES (?, ?, ?)",
					req.username(), encoder.encode(req.password()), display);
		} catch (DataAccessException e) {
			// 同時に同じIDで登録が重なり、上の確認をすり抜けた場合
			if (e instanceof DuplicateKeyException || e instanceof DataIntegrityViolationException || Sql.isUniqueViolation(e)) {
				throw new ApiException(HttpStatus.CONFLICT, USERNAME_TAKEN);
			}
			throw e;
		}
		Long id = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, req.username());
		return session(id);
	}

	@PostMapping("/auth/login")
	public Map<String, Object> login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
		String ip = http.getRemoteAddr();
		limiter.check(req.username(), ip);
		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT id, password_hash FROM users WHERE username = ?", req.username());
		// 存在しないユーザーIDでも照合を行い、応答時間の差からIDの有無が分からないようにする
		String hash = rows.isEmpty() ? dummyHash : (String) rows.get(0).get("password_hash");
		boolean ok = encoder.matches(req.password(), hash) && !rows.isEmpty();
		if (!ok) {
			limiter.recordFailure(req.username(), ip);
			throw new ApiException(HttpStatus.UNAUTHORIZED, "ユーザーIDまたはパスワードが正しくありません");
		}
		limiter.recordSuccess(req.username(), ip);
		return session(((Number) rows.get(0).get("id")).longValue());
	}

	@GetMapping("/me")
	public Map<String, Object> me(@RequestAttribute("userId") long uid) {
		return user(uid);
	}

	@PutMapping("/me")
	public Map<String, Object> updateMe(@RequestAttribute("userId") long uid, @Valid @RequestBody ProfileRequest req) {
		jdbc.update("UPDATE users SET display_name = ?, status = ? WHERE id = ?",
				req.displayName().trim(), req.status() == null ? "" : req.status().trim(), uid);
		realtime.profileChanged(uid);
		return user(uid);
	}

	@PostMapping("/me/avatar")
	public Map<String, Object> avatar(@RequestAttribute("userId") long uid, @RequestParam("file") MultipartFile file) {
		String url = storage.save(file, true).get("url");
		jdbc.update("UPDATE users SET avatar_url = ? WHERE id = ?", url, uid);
		realtime.profileChanged(uid);
		return user(uid);
	}

	@PostMapping("/files")
	public Map<String, String> upload(@RequestParam("file") MultipartFile file) {
		return storage.save(file, false);
	}

	/** ユーザーの情報。トークンは有効でも、ユーザーが存在しない(DBを作り直した後や削除後の古いログイン状態)ときは401 */
	private Map<String, Object> user(long uid) {
		List<Map<String, Object>> rows = jdbc.queryForList(USER_SQL, uid);
		if (rows.isEmpty()) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "セッションが無効です。再度ログインしてください");
		}
		return rows.get(0);
	}

	private Map<String, Object> session(long uid) {
		Map<String, Object> res = new LinkedHashMap<>();
		res.put("token", jwt.issue(uid));
		res.put("user", user(uid));
		return res;
	}
}
