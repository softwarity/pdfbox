package com.softwarity.pdfbox.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bounds the number of renders running at once. Rendering is CPU-bound and every request holds its
 * attached files in memory, so without a bound a burst of requests oversubscribes the CPU and the
 * heap (up to the container's thread count × the upload limit). Whatever the servlet container,
 * this is what keeps memory predictable: {@code maxConcurrent × PDFBOX_UPLOAD_MAX_TOTAL_SIZE}.
 *
 * <p>It runs as a filter, before the multipart body is read, so a waiting or refused request has not
 * buffered its upload yet. A request that cannot get a slot within the queue timeout gets a
 * {@code 503} with {@code Retry-After}. Only the rendering routes are concerned; health, OpenAPI and
 * the standards list are not.
 */
@Component
class RenderLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RenderLimitFilter.class);

    private final Semaphore slots;
    private final int maxConcurrent;
    private final long queueTimeoutMillis;

    RenderLimitFilter(RenderLimitProperties properties) {
        this.maxConcurrent = properties.effectiveMaxConcurrent();
        this.slots = new Semaphore(maxConcurrent, true);
        this.queueTimeoutMillis = properties.queueTimeout().toMillis();
        log.info("Rendering: at most {} concurrent render(s), queue timeout {}", maxConcurrent,
                properties.queueTimeout());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return switch (request.getMethod()) {
            case "POST" -> !path.equals("/api/v1/pdf") && !path.equals("/api/v1/pdf/upload");
            case "GET" -> !path.equals("/api/v1/pdf-info");
            default -> true;
        };
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean acquired;
        try {
            acquired = slots.tryAcquire(queueTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            long retryAfter = Math.max(1, TimeUnit.MILLISECONDS.toSeconds(queueTimeoutMillis));
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
            response.setContentType("text/plain;charset=UTF-8");
            response.getOutputStream().write(("Busy: " + maxConcurrent + " render(s) already in progress "
                    + "(PDFBOX_RENDER_MAX_CONCURRENT). Retry in " + retryAfter + " s.")
                    .getBytes(StandardCharsets.UTF_8));
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            slots.release();
        }
    }
}
