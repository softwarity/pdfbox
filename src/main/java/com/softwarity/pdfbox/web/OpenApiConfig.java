package com.softwarity.pdfbox.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpenApiConfig {

    @Bean
    OpenAPI pdfboxOpenApi() {
        return new OpenAPI().info(new Info()
                .title("pdfbox")
                .description("HTML to PDF/A generation service")
                .version("v1")
                .license(new License().name("Apache-2.0")));
    }
}
