package com.softwarity.pdfbox.resource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import com.openhtmltopdf.extend.FSStream;
import com.openhtmltopdf.extend.FSStreamFactory;
import com.openhtmltopdf.extend.FSUriResolver;
import com.openhtmltopdf.outputdevice.helper.ExternalResourceType;
import com.softwarity.pdfbox.pdf.SvgSanitizer;
import com.softwarity.pdfbox.resource.UnresolvedResource.Reason;

/**
 * The single resource policy of one render. Every resource the document needs — stylesheets,
 * {@code @font-face} fonts, images, SVG files and the references inside SVGs — goes through here,
 * and is resolved in this order:
 *
 * <ol>
 *   <li>a file attached to the request, cited by its plain name ({@code sigwx.svg},
 *       {@code ./page.css});</li>
 *   <li>a {@code data:} URI;</li>
 *   <li>an {@code http(s)} URL, <em>only</em> if its host is in {@code PDFBOX_FETCH_ALLOWED_HOSTS}
 *       (re-checked on every redirect hop), within the configured timeout and size limit.</li>
 * </ol>
 *
 * Everything else is refused: {@code file:}, {@code jar:}, {@code classpath:}, any other scheme,
 * relative paths that do not name an attached file, and unlisted hosts. Files embedded in the PDF
 * ({@code <a href download>}) follow the same resolution, unless the standard forbids embedded files
 * altogether, and are embedded byte for byte (no SVG sanitising, no flattening). Nothing is ever dropped
 * silently: each refusal or failure is recorded with its reason ({@link #unresolved()}), and the
 * caller decides whether that fails the render. Bundled fonts never come through here.
 *
 * <p>Wiring into openhtmltopdf: this object is the {@link FSUriResolver} (maps references to an
 * internal URI), the access controller run after resolution ({@link #allow}: only loads reach it, not
 * links) and the {@link FSStreamFactory} for every scheme it lets through. A remote resource cited
 * several times is fetched once per render.
 */
public final class RenderResources implements FSUriResolver, FSStreamFactory {

    /** Internal scheme of an attached file: {@code pdfbox-attachment:<encoded name>}. */
    public static final String ATTACHMENT = "pdfbox-attachment";
    /** Internal scheme of a relative reference that names no attached file. */
    public static final String MISSING = "pdfbox-missing";
    /** Schemes this object serves as stream factory (any other scheme is refused before loading). */
    public static final Set<String> SCHEMES = Set.of("data", "http", "https", ATTACHMENT, MISSING);

    /** Guard against SVGs citing each other in a loop. */
    private static final int MAX_SVG_DEPTH = 8;

    private record Loaded(byte[] data, String mediaType) {}

    private final Attachments attachments;
    private final ResourceFetcher fetcher;
    private final SvgSanitizer svgSanitizer;
    private final boolean flattenTransparency;
    private final String embedRefusal;
    private final Set<String> embeds = new HashSet<>();
    private final Map<String, Loaded> cache = new HashMap<>();
    private final Map<String, UnresolvedResource> unresolved = new LinkedHashMap<>();

    public RenderResources(Attachments attachments, ResourceFetcher fetcher, SvgSanitizer svgSanitizer) {
        this(attachments, fetcher, svgSanitizer, false);
    }

    /**
     * @param flattenTransparency PDF/A-1: raster images are composited over white (no alpha channel)
     *                            and SVGs have their opacity folded into solid colours
     */
    public RenderResources(Attachments attachments, ResourceFetcher fetcher, SvgSanitizer svgSanitizer,
                           boolean flattenTransparency) {
        this(attachments, fetcher, svgSanitizer, flattenTransparency, null);
    }

    /**
     * @param flattenTransparency PDF/A-1: raster images are composited over white (no alpha channel)
     *                            and SVGs have their opacity folded into solid colours
     * @param embedRefusal        why files may not be embedded in the PDF (recorded for each attempt),
     *                            or {@code null} when the standard allows embedded files
     */
    public RenderResources(Attachments attachments, ResourceFetcher fetcher, SvgSanitizer svgSanitizer,
                           boolean flattenTransparency, String embedRefusal) {
        this.attachments = attachments == null ? Attachments.none() : attachments;
        this.fetcher = fetcher;
        this.svgSanitizer = svgSanitizer;
        this.flattenTransparency = flattenTransparency;
        this.embedRefusal = embedRefusal;
    }

    /** Every resource that could not be used, in the order first met, without duplicates. */
    public List<UnresolvedResource> unresolved() {
        return new ArrayList<>(unresolved.values());
    }

    // ------------------------------------------------------------------ resolution

    @Override
    public String resolveURI(String baseUri, String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        String ref = uri.trim();
        if (isData(ref)) {
            return ref;
        }
        String name = plainName(ref);
        if (name != null && attachments.find(name).isPresent()) {
            return ATTACHMENT + ":" + encode(name);
        }
        URI parsed = parse(ref);
        if (parsed == null && name == null) {
            // Has a scheme but is not a valid URI (typically a raw space): encode what browsers
            // would, so it is judged as the URL it is meant to be.
            parsed = parse(ref.replace(" ", "%20"));
        }
        if (parsed != null && parsed.isAbsolute()) {
            return parsed.toString();
        }
        // A relative reference inside a stylesheet or SVG fetched from an allowed host resolves
        // against that host (and is then checked like any other URL).
        URI base = baseUri == null || baseUri.isBlank() ? null : parse(baseUri);
        if (parsed != null && base != null && isHttp(base.getScheme())) {
            return base.resolve(parsed).toString();
        }
        return MISSING + ":" + encode(ref);
    }

    /**
     * Access control run by openhtmltopdf for every load, after {@link #resolveURI}. Refusals are
     * recorded here; failures of allowed loads are recorded by {@link #getUrl}.
     *
     * <p>File embeds only reach this method if openhtmltopdf's own "before resolving" controller lets
     * them through: its default refuses them silently, so the renderer must replace it (see
     * {@code PdfGenerationService}).
     */
    public boolean allow(String resolved, ExternalResourceType type) {
        if (resolved == null) {
            return false;
        }
        if (type == ExternalResourceType.FILE_EMBED) {
            if (embedRefusal != null) {
                record(displayName(resolved), Reason.NOT_ALLOWED, embedRefusal);
                return false;
            }
            if (!allowLoad(resolved)) {
                return false;
            }
            embeds.add(resolved);
            return true;
        }
        return allowLoad(resolved);
    }

    private boolean allowLoad(String resolved) {
        String scheme = schemeOf(resolved);
        switch (scheme) {
            case "data", ATTACHMENT -> {
                return true;
            }
            case MISSING -> {
                String ref = decode(resolved.substring(MISSING.length() + 1));
                record(ref, Reason.NOT_FOUND, ref.contains("/") || ref.contains("\\")
                        ? "relative paths are not resolved: attach the file and cite it by its plain name"
                        : "no attached file named '" + ref + "'");
                return false;
            }
            case "http", "https" -> {
                URI uri = parse(resolved);
                if (uri != null && uri.getHost() != null && fetcher.allowList().permits(uri)) {
                    return true;
                }
                record(resolved, Reason.NOT_ALLOWED, uri == null || uri.getHost() == null
                        ? "malformed URL"
                        : "host '" + HostAllowList.authority(uri) + "' is not in PDFBOX_FETCH_ALLOWED_HOSTS"
                                + (fetcher.allowList().isEmpty() ? " (empty: nothing is fetched)" : ""));
                return false;
            }
            default -> {
                record(resolved, Reason.NOT_ALLOWED, "the '" + scheme + ":' scheme is never read");
                return false;
            }
        }
    }

    // ------------------------------------------------------------------ loading

    @Override
    public FSStream getUrl(String resolved) {
        Loaded loaded = load(resolved, 0);
        return new FSStream() {
            @Override
            public InputStream getStream() {
                return loaded == null ? null : new ByteArrayInputStream(loaded.data());
            }

            @Override
            public Reader getReader() {
                return loaded == null ? null
                        : new InputStreamReader(new ByteArrayInputStream(loaded.data()), StandardCharsets.UTF_8);
            }
        };
    }

    /**
     * Turns references found inside an SVG into {@code data:} URIs, under this same policy, so Batik
     * never has to load anything. Returns {@code null} (and records why) for what cannot be resolved.
     *
     * @param baseUri the resolved URI of the SVG document, or {@code null} for inline SVG
     */
    public UnaryOperator<String> svgInliner(String baseUri) {
        return svgInliner(baseUri, 0);
    }

    private UnaryOperator<String> svgInliner(String baseUri, int depth) {
        return ref -> {
            if (ref.regionMatches(true, 0, "data:", 0, 5) && !flattenTransparency) {
                return ref; // already self-contained, nothing to change
            }
            String fragment = "";
            String target = ref;
            int hash = ref.indexOf('#');
            if (hash > 0) {
                fragment = ref.substring(hash);
                target = ref.substring(0, hash);
            }
            String resolved = resolveURI(baseUri, target);
            if (resolved == null || !allow(resolved, ExternalResourceType.IMAGE_RASTER)) {
                return null;
            }
            Loaded loaded = load(resolved, depth + 1);
            if (loaded == null) {
                return null;
            }
            return "data:" + loaded.mediaType() + ";base64,"
                    + Base64.getEncoder().encodeToString(loaded.data()) + fragment;
        };
    }

    /**
     * Loads a resource. Only remote fetches are cached (one request per URL and render): attached
     * files are re-read from the upload when needed again rather than kept, so a render never holds
     * every attachment's bytes at once; openhtmltopdf keeps its own cache of decoded images.
     */
    private Loaded load(String resolved, int depth) {
        if (cache.containsKey(resolved)) {
            return cache.get(resolved);
        }
        if (depth > MAX_SVG_DEPTH) {
            record(displayName(resolved), Reason.FETCH_ERROR, "SVG references nested more than "
                    + MAX_SVG_DEPTH + " levels deep (a reference loop?)");
            return null;
        }
        Loaded raw = switch (schemeOf(resolved)) {
            case "data" -> loadData(resolved);
            case ATTACHMENT -> attachments.find(decode(resolved.substring(ATTACHMENT.length() + 1)))
                    .map(a -> new Loaded(a.read(), a.mediaType()))
                    .orElse(null);
            case "http", "https" -> loadRemote(resolved);
            default -> null; // refused by allow() before any load
        };
        if (embeds.contains(resolved)) {
            return raw; // embedded in the PDF as is: never rendered, so never sanitised nor flattened
        }
        Loaded result = raw;
        if (raw != null && MediaTypes.isSvg(raw.mediaType(), resolved, raw.data())) {
            result = new Loaded(svgSanitizer.sanitize(raw.data(), svgInliner(resolved, depth), flattenTransparency),
                    "image/svg+xml");
        } else if (raw != null && MediaTypes.isWebp(raw.data())) {
            try {
                result = new Loaded(MediaTypes.webpToPng(raw.data()), "image/png");
            } catch (IllegalArgumentException e) {
                record(displayName(resolved), Reason.FETCH_ERROR, e.getMessage());
                result = null;
            }
        }
        if (result != null && flattenTransparency && MediaTypes.mayHaveAlpha(result.mediaType())) {
            try {
                result = new Loaded(MediaTypes.flattenOnWhite(result.data()), result.mediaType().equals("image/png")
                        || result.mediaType().equals("image/gif") ? "image/png" : result.mediaType());
            } catch (IllegalArgumentException e) {
                record(displayName(resolved), Reason.FETCH_ERROR, e.getMessage());
                result = null;
            }
        }
        if (isHttp(schemeOf(resolved))) {
            cache.put(resolved, result);
        }
        return result;
    }

    private Loaded loadRemote(String resolved) {
        URI uri = parse(resolved);
        if (uri == null) {
            record(resolved, Reason.FETCH_ERROR, "malformed URL");
            return null;
        }
        ResourceFetcher.Outcome outcome = fetcher.fetch(uri);
        if (outcome.failure() != null) {
            record(outcome.failure());
            return null;
        }
        return new Loaded(outcome.data(),
                MediaTypes.of(outcome.contentType(), outcome.finalUri().getPath(), outcome.data()));
    }

    private Loaded loadData(String uri) {
        int comma = uri.indexOf(',');
        if (comma < 0) {
            record(abbreviate(uri), Reason.FETCH_ERROR, "malformed data: URI (no ',')");
            return null;
        }
        String header = uri.substring(5, comma);
        String payload = uri.substring(comma + 1);
        boolean base64 = header.toLowerCase(Locale.ROOT).endsWith(";base64");
        String mediaType = header.split(";", 2)[0].trim();
        try {
            byte[] data = base64
                    ? Base64.getMimeDecoder().decode(payload)
                    : URLDecoder.decode(payload.replace("+", "%2B"), StandardCharsets.UTF_8)
                            .getBytes(StandardCharsets.UTF_8);
            return new Loaded(data, MediaTypes.of(mediaType.isEmpty() ? null : mediaType, null, data));
        } catch (IllegalArgumentException e) {
            record(abbreviate(uri), Reason.FETCH_ERROR, "malformed data: URI (" + e.getMessage() + ")");
            return null;
        }
    }

    // ------------------------------------------------------------------ helpers

    private void record(String reference, Reason reason, String detail) {
        record(new UnresolvedResource(reference, reason, detail));
    }

    private void record(UnresolvedResource u) {
        unresolved.putIfAbsent(u.reference() + "\u0000" + u.reason(), u);
    }

    /** The reference as the author wrote it, for messages. */
    private static String displayName(String resolved) {
        String scheme = schemeOf(resolved);
        if (scheme.equals(ATTACHMENT) || scheme.equals(MISSING)) {
            return decode(resolved.substring(scheme.length() + 1));
        }
        return isData(resolved) ? abbreviate(resolved) : resolved;
    }

    /**
     * The attachment name a reference designates: the reference itself, minus a leading {@code ./},
     * percent-decoded; {@code null} when it carries a scheme.
     */
    private static String plainName(String ref) {
        String r = ref.startsWith("./") ? ref.substring(2) : ref;
        int colon = r.indexOf(':');
        int slash = r.indexOf('/');
        if (colon >= 0 && (slash < 0 || colon < slash)) {
            return null;
        }
        return decode(r);
    }

    private static String schemeOf(String uri) {
        int colon = uri.indexOf(':');
        return colon <= 0 ? "" : uri.substring(0, colon).toLowerCase(Locale.ROOT);
    }

    private static boolean isData(String ref) {
        return ref.regionMatches(true, 0, "data:", 0, 5);
    }

    private static boolean isHttp(String scheme) {
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private static URI parse(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static String encode(String name) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static String abbreviate(String dataUri) {
        return dataUri.length() <= 48 ? dataUri : dataUri.substring(0, 45) + "...";
    }
}
