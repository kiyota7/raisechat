package com.raisechat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * JSON本文の大きさに上限をかける(巨大なリクエストでメモリを使わせない攻撃への対策)。
 * 長さが分かるもの(Content-Length)は、読む前に413で断る。長さが分からないもの(chunked)は、
 * 読んだ量が上限を超えた時点で止める。ファイルのアップロード(multipart)は別の上限(50MB)があるので対象外。
 */
@Component
public class JsonBodyLimitFilter extends OncePerRequestFilter {
	private final long maxBytes;

	public JsonBodyLimitFilter(@Value("${app.max-json-body-bytes:1048576}") long maxBytes) {
		this.maxBytes = maxBytes;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest req) {
		String type = req.getContentType();
		return type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("application/json");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
			throws ServletException, IOException {
		if (req.getContentLengthLong() > maxBytes) {
			res.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
			res.setContentType("application/json;charset=UTF-8");
			res.getOutputStream().write("{\"message\":\"リクエストが大きすぎます\"}".getBytes(StandardCharsets.UTF_8));
			return;
		}
		chain.doFilter(new LimitedRequest(req, maxBytes), res);
	}

	/** 読んだ量を数え、上限を超えたら例外にする */
	private static final class LimitedRequest extends HttpServletRequestWrapper {
		private final long max;

		LimitedRequest(HttpServletRequest req, long max) {
			super(req);
			this.max = max;
		}

		@Override
		public ServletInputStream getInputStream() throws IOException {
			ServletInputStream in = super.getInputStream();
			return new ServletInputStream() {
				private long count;

				private int counted(int n) throws IOException {
					if (n > 0 && (count += n) > max) {
						throw new IOException("リクエストが大きすぎます");
					}
					return n;
				}

				@Override
				public int read() throws IOException {
					int b = in.read();
					if (b >= 0) {
						counted(1);
					}
					return b;
				}

				@Override
				public int read(byte[] b, int off, int len) throws IOException {
					return counted(in.read(b, off, len));
				}

				@Override
				public boolean isFinished() {
					return in.isFinished();
				}

				@Override
				public boolean isReady() {
					return in.isReady();
				}

				@Override
				public void setReadListener(ReadListener l) {
					in.setReadListener(l);
				}
			};
		}
	}
}
