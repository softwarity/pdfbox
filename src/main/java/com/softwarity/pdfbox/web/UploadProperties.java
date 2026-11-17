package com.softwarity.pdfbox.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Multipart limits, set at startup. They also drive Spring's multipart settings (see
 * {@code application.yaml}), so the servlet container enforces them before the controller runs; the
 * controller checks them again so the behaviour is the same whatever the container.
 *
 * @param maxPartSize  {@code PDFBOX_UPLOAD_MAX_PART_SIZE}: maximum size of one part (HTML or attached file)
 * @param maxTotalSize {@code PDFBOX_UPLOAD_MAX_TOTAL_SIZE}: maximum size of the whole request
 */
@ConfigurationProperties("pdfbox.upload")
public record UploadProperties(
        @DefaultValue("20MB") DataSize maxPartSize,
        @DefaultValue("50MB") DataSize maxTotalSize) {
}
