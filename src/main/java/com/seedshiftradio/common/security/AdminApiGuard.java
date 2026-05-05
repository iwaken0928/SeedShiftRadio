package com.seedshiftradio.common.security;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.seedshiftradio.common.api.ApiException;

@Component
public class AdminApiGuard {

	public static final String HEADER_NAME = "X-Admin-Token";

	private final AdminTokenProperties properties;

	public AdminApiGuard(AdminTokenProperties properties) {
		this.properties = properties;
	}

	public void require(String suppliedToken) {
		if (!properties.isConfigured()) {
			throw new ApiException(
					HttpStatus.UNAUTHORIZED,
					"ADMIN_AUTH_REQUIRED",
					"管理 API を利用するには管理トークンの設定が必要です。",
					Map.of("header", HEADER_NAME));
		}
		if (suppliedToken == null || !properties.adminToken().equals(suppliedToken)) {
			throw new ApiException(
					HttpStatus.UNAUTHORIZED,
					"ADMIN_AUTH_REQUIRED",
					"管理トークンが不足しているか正しくありません。",
					Map.of("header", HEADER_NAME));
		}
	}
}
