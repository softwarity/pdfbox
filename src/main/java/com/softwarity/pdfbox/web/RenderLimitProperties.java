package com.softwarity.pdfbox.web;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Admission control for rendering, set at startup.
 *
 * @param maxConcurrent {@code PDFBOX_RENDER_MAX_CONCURRENT}: renders allowed at the same time;
 *                      {@code 0} (default) = the number of available processors
 * @param queueTimeout  {@code PDFBOX_RENDER_QUEUE_TIMEOUT}: how long a request may wait for a slot
 *                      before being answered 503
 */
@ConfigurationProperties("pdfbox.render")
public record RenderLimitProperties(
        @DefaultValue("0") int maxConcurrent,
        @DefaultValue("10s") Duration queueTimeout) {

    int effectiveMaxConcurrent() {
        return maxConcurrent > 0 ? maxConcurrent : Runtime.getRuntime().availableProcessors();
    }
}
