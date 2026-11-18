package com.softwarity.pdfbox.web;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Multipart limits, set at startup. They drive the servlet container's multipart settings (see
 * {@link MultipartConfig}), so the container enforces them before the controller runs; the controller
 * checks the sizes again so the behaviour is the same whatever the container.
 *
 * @param maxPartSize     {@code PDFBOX_UPLOAD_MAX_PART_SIZE}: maximum size of one part
 * @param maxTotalSize    {@code PDFBOX_UPLOAD_MAX_TOTAL_SIZE}: maximum size of the whole request
 * @param memoryThreshold {@code PDFBOX_UPLOAD_MEMORY_THRESHOLD}: parts up to this size stay in memory,
 *                        larger ones go to a temporary file (deleted at the end of the request).
 *                        Unset (the default) = {@code maxPartSize}: nothing is ever written to disk.
 */
@ConfigurationProperties("pdfbox.upload")
public record UploadProperties(
        @DefaultValue("20MB") DataSize maxPartSize,
        @DefaultValue("50MB") DataSize maxTotalSize,
        @Nullable DataSize memoryThreshold) {

    /** The effective threshold: never above the per-part maximum. */
    long effectiveMemoryThreshold() {
        long max = maxPartSize.toBytes();
        return memoryThreshold == null ? max : Math.min(memoryThreshold.toBytes(), max);
    }

    /** True when some parts may be written to a temporary file. */
    boolean spillsToDisk() {
        return effectiveMemoryThreshold() < maxPartSize.toBytes();
    }
}
