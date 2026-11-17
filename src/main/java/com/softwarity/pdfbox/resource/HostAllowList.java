package com.softwarity.pdfbox.resource;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Exact host names (optionally {@code host:port}) that remote resources may be fetched from.
 *
 * <p>An entry without a port only admits the scheme's default port (80 for {@code http}, 443 for
 * {@code https}); an entry with a port admits that port only. Host names compare case-insensitively.
 * No wildcard and no IP range: what is not listed is refused.
 */
public final class HostAllowList {

    private static final Pattern ENTRY =
            Pattern.compile("^(\\[[0-9a-fA-F:.]+]|[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?)(:([0-9]{1,5}))?$");

    private record Entry(String host, int port) {}

    private final List<Entry> entries = new ArrayList<>();

    public HostAllowList(List<String> raw) {
        for (String value : raw == null ? List.<String>of() : raw) {
            String v = value == null ? "" : value.trim();
            if (v.isEmpty()) {
                continue;
            }
            var m = ENTRY.matcher(v);
            if (!m.matches()) {
                throw new IllegalArgumentException("Invalid PDFBOX_FETCH_ALLOWED_HOSTS entry '" + v
                        + "': expected an exact host name, optionally with a port (e.g. "
                        + "geoserver.example.svc or maps.example.org:8443); no scheme, path or wildcard.");
            }
            int port = m.group(4) == null ? -1 : Integer.parseInt(m.group(4));
            entries.add(new Entry(m.group(1).toLowerCase(Locale.ROOT), port));
        }
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** True when {@code uri} is {@code http(s)} and its host (and port) is listed. */
    public boolean permits(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
            return false;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int defaultPort = scheme.equals("https") ? 443 : 80;
        int port = uri.getPort() == -1 ? defaultPort : uri.getPort();
        return entries.stream().anyMatch(e -> e.host().equals(host)
                && (e.port() == -1 ? port == defaultPort : port == e.port()));
    }

    /** {@code host} or {@code host:port} as it would be written in the allow-list. */
    public static String authority(URI uri) {
        return uri.getPort() == -1 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
    }
}
