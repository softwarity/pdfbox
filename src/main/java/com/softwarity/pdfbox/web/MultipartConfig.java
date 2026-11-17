package com.softwarity.pdfbox.web;

import jakarta.servlet.MultipartConfigElement;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Servlet multipart limits derived from {@link UploadProperties} — the single source of truth, so the
 * container enforces exactly what the controller checks (Boot's own {@code spring.servlet.multipart}
 * auto-configuration steps aside when this bean exists).
 *
 * <p>The in-memory threshold equals the per-part maximum: a part is never larger than the threshold,
 * so the container never writes one to a temporary file. Attached files only exist in memory, for
 * the duration of one request.
 */
@Configuration(proxyBeanMethods = false)
class MultipartConfig {

    @Bean
    MultipartConfigElement multipartConfigElement(UploadProperties upload) {
        long maxPart = upload.maxPartSize().toBytes();
        long maxTotal = upload.maxTotalSize().toBytes();
        return new MultipartConfigElement("", maxPart, maxTotal, (int) Math.min(maxPart, Integer.MAX_VALUE));
    }
}
