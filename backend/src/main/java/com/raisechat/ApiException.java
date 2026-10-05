package com.raisechat;

import java.util.Map;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
	private final HttpStatus status;
	private final Map<String, String> headers;

	public ApiException(HttpStatus status, String message) {
		this(status, message, Map.of());
	}

	/** 応答に付けるヘッダー(例: 429 の Retry-After)つき */
	public ApiException(HttpStatus status, String message, Map<String, String> headers) {
		super(message);
		this.status = status;
		this.headers = headers;
	}

	public Map<String, String> getHeaders() {
		return headers;
	}

	public HttpStatus getStatus() {
		return status;
	}
}
