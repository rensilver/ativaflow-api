package com.rensilver.ativaflow_api.config;

import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;

@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(info = @Info(
		title = "AtivaFlow API",
		version = "v1",
		description = "API para gerenciamento de solicitações de ativação."
))
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfiguration {
}
