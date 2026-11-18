package com.softwarity.pdfbox.web;

import jakarta.servlet.MultipartConfigElement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Servlet multipart limits derived from {@link UploadProperties} — the single source of truth, so the
 * container enforces exactly what the controller checks (Boot's own {@code spring.servlet.multipart}
 * auto-configuration steps aside when this bean exists).
 *
 * <p>By default the in-memory threshold equals the per-part maximum: no part is ever larger than the
 * threshold, so the container never writes one to a temporary file. Lowering
 * {@code PDFBOX_UPLOAD_MEMORY_THRESHOLD} trades that for memory: larger parts are written to the
 * container's temporary directory (deleted at the end of the request) and read back only when the
 * renderer needs them — attachments are never copied as a whole either way (see {@code Attachment}).
 */
@Configuration(proxyBeanMethods = false)
class MultipartConfig {

    private static final Logger log = LoggerFactory.getLogger(MultipartConfig.class);

    @Bean
    MultipartConfigElement multipartConfigElement(UploadProperties upload) {
        long maxPart = upload.maxPartSize().toBytes();
        long maxTotal = upload.maxTotalSize().toBytes();
        long threshold = upload.effectiveMemoryThreshold();
        log.info("Uploads: max {} per part, {} in total; {}", upload.maxPartSize(), upload.maxTotalSize(),
                upload.spillsToDisk()
                        ? "parts above " + threshold + " bytes go to a temporary file for the request"
                        : "held in memory only, never written to disk");
        return new MultipartConfigElement("", maxPart, maxTotal, (int) Math.min(threshold, Integer.MAX_VALUE));
    }
}
