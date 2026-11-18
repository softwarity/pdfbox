package com.softwarity.pdfbox.resource;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** The files attached to one render request, looked up by their plain name. */
public final class Attachments {

    private static final Attachments NONE = new Attachments(Map.of());

    private final Map<String, Attachment> byName;

    private Attachments(Map<String, Attachment> byName) {
        this.byName = byName;
    }

    public static Attachments none() {
        return NONE;
    }

    /** Wraps already-validated attachments (see {@code AttachmentValidator}). */
    public static Attachments of(Collection<Attachment> attachments) {
        Map<String, Attachment> map = new LinkedHashMap<>();
        attachments.forEach(a -> map.put(a.name(), a));
        return new Attachments(Collections.unmodifiableMap(map));
    }

    public Optional<Attachment> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public boolean isEmpty() {
        return byName.isEmpty();
    }

    public Collection<Attachment> all() {
        return byName.values();
    }

    /**
     * The text of each text attachment (CSS / SVG), read one at a time while iterating, to scan for
     * the scripts it needs fonts for — never all of them in memory at once.
     */
    public Iterable<String> texts() {
        return () -> byName.values().stream()
                .filter(Attachment::isText)
                .map(a -> new String(a.read(), StandardCharsets.UTF_8))
                .iterator();
    }

    /** {@code first} (typically the HTML), then {@link #texts()}. */
    public Iterable<String> textsAfter(String first) {
        return () -> java.util.stream.Stream.concat(Stream.of(first == null ? "" : first),
                java.util.stream.StreamSupport.stream(texts().spliterator(), false)).iterator();
    }
}
