package com.softwarity.pdfbox.resource;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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

    /** The text of every text attachment (CSS / SVG), to scan for the scripts it needs fonts for. */
    public String textContent() {
        return byName.values().stream()
                .filter(Attachment::isText)
                .map(a -> new String(a.data(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("\n"));
    }
}
