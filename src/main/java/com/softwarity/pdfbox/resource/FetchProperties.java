package com.softwarity.pdfbox.resource;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Remote ({@code http(s)}) resource fetching, set at startup.
 *
 * @param allowedHosts {@code PDFBOX_FETCH_ALLOWED_HOSTS}: exact host names, optionally with a port
 *                     ({@code geoserver.example.svc}, {@code maps.example.org:8443}). Empty (the
 *                     default) means nothing is ever fetched over the network.
 * @param timeout      {@code PDFBOX_FETCH_TIMEOUT}: overall time allowed per resource (connection,
 *                     redirects and body).
 * @param maxBytes     {@code PDFBOX_FETCH_MAX_BYTES}: maximum size of one fetched resource.
 */
@ConfigurationProperties("pdfbox.fetch")
public record FetchProperties(
        @DefaultValue List<String> allowedHosts,
        @DefaultValue("10s") Duration timeout,
        @DefaultValue("10MB") DataSize maxBytes) {
}
