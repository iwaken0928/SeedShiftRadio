package com.seedshiftradio.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.seedshiftradio.common.security.AdminApiGuard;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration
public class OpenApiConfig {

	public static final String ADMIN_SECURITY_SCHEME = "adminToken";

	@Bean
	OpenAPI seedShiftRadioOpenApi() {
		return new OpenAPI()
				.info(new Info().title("SeedShiftRadio API").version("v1"))
				.components(new Components().addSecuritySchemes(
						ADMIN_SECURITY_SCHEME,
						new SecurityScheme()
								.type(SecurityScheme.Type.APIKEY)
								.in(SecurityScheme.In.HEADER)
								.name(AdminApiGuard.HEADER_NAME)));
	}
}
