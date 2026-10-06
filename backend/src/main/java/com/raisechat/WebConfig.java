package com.raisechat;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
	private final JwtService jwt;
	private final String uploadDir;

	public WebConfig(JwtService jwt, @Value("${app.upload-dir}") String uploadDir) {
		this.jwt = jwt;
		this.uploadDir = uploadDir;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new HandlerInterceptor() {
			@Override
			public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
				String h = req.getHeader("Authorization");
				if (h == null || !h.startsWith("Bearer ")) {
					throw new ApiException(HttpStatus.UNAUTHORIZED, "ログインが必要です");
				}
				try {
					req.setAttribute("userId", jwt.parse(h.substring(7)));
				} catch (Exception e) {
					throw new ApiException(HttpStatus.UNAUTHORIZED, "セッションが無効です。再度ログインしてください");
				}
				return true;
			}
		}).addPathPatterns("/api/**").excludePathPatterns("/api/auth/register", "/api/auth/login");
		// アップロードされたファイルは、ブラウザに種類を推測させず、ページとして実行もさせない
		registry.addInterceptor(new HandlerInterceptor() {
			@Override
			public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
				res.setHeader("X-Content-Type-Options", "nosniff");
				res.setHeader("Content-Security-Policy", "default-src 'none'; sandbox");
				return true;
			}
		}).addPathPatterns("/uploads/**");
	}

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		String loc = Path.of(uploadDir).toAbsolutePath().toUri().toString();
		registry.addResourceHandler("/uploads/**").addResourceLocations(loc);
	}
}
