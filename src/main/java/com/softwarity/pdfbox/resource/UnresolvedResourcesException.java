package com.softwarity.pdfbox.resource;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Rendering was refused because the document references resources that could not be resolved
 * ({@code onMissingResource=fail}, the default). Mapped to a 422 whose plain-text body lists them.
 */
public class UnresolvedResourcesException extends RuntimeException {

    private final List<UnresolvedResource> unresolved;

    public UnresolvedResourcesException(List<UnresolvedResource> unresolved) {
        super(describe(unresolved));
        this.unresolved = List.copyOf(unresolved);
    }

    public List<UnresolvedResource> unresolved() {
        return unresolved;
    }

    static String describe(List<UnresolvedResource> unresolved) {
        return unresolved.size() + " resource(s) could not be resolved:\n"
                + unresolved.stream()
                        .map(u -> "- " + u.reference() + " : " + u.reason().code() + " — " + u.detail())
                        .collect(Collectors.joining("\n"))
                + "\nAttach the missing files, allow their hosts (PDFBOX_FETCH_ALLOWED_HOSTS), or retry with "
                + "onMissingResource=report to get the PDF anyway.";
    }
}
