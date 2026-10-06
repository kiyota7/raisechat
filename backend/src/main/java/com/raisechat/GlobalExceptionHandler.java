package com.raisechat;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<Map<String, String>> handleApi(ApiException e) {
		ResponseEntity.BodyBuilder res = ResponseEntity.status(e.getStatus());
		return res.body(Map.of("message", e.getMessage()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException e) {
		String msg = e.getBindingResult().getFieldErrors().stream()
				.findFirst().map(f -> f.getDefaultMessage()).orElse("入力内容が正しくありません");
		return ResponseEntity.badRequest().body(Map.of("message", msg));
	}
}
