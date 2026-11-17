package com.softwarity.pdfbox.web;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

import com.softwarity.pdfbox.pdf.PdfGenerationException;
import com.softwarity.pdfbox.resource.UnresolvedResourcesException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
class GlobalExceptionHandler {

    /** Error bodies quote references as written, in any script: always declare UTF-8. */
    static final MediaType TEXT_PLAIN_UTF8 = new MediaType("text", "plain", StandardCharsets.UTF_8);

    @ExceptionHandler({PdfGenerationException.class, MethodArgumentNotValidException.class,
            IllegalArgumentException.class})
    ResponseEntity<String> badRequest(Exception ex) {
        return plainBadRequest(ex.getMessage());
    }

    /** A part or the whole upload exceeds the configured multipart limits: 413. */
    @ExceptionHandler({PayloadTooLargeException.class, MaxUploadSizeExceededException.class})
    ResponseEntity<String> tooLarge(Exception ex) {
        String message = ex instanceof PayloadTooLargeException ? ex.getMessage()
                : "The upload exceeds the size limits (PDFBOX_UPLOAD_MAX_PART_SIZE per part, "
                        + "PDFBOX_UPLOAD_MAX_TOTAL_SIZE in total).";
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .contentType(TEXT_PLAIN_UTF8)
                .body(message);
    }

    /** Malformed multipart request, or a required part missing: 400. */
    @ExceptionHandler({MultipartException.class, MissingServletRequestPartException.class})
    ResponseEntity<String> badMultipart(Exception ex) {
        return plainBadRequest(ex instanceof MissingServletRequestPartException missing
                ? "Missing multipart part '" + missing.getRequestPartName() + "' (the HTML goes in 'file')."
                : "Invalid multipart request: " + ex.getMessage());
    }

    /**
     * The document references resources that could not be used and the caller asked to fail
     * ({@code onMissingResource=fail}, the default): 422, with each resource and its reason.
     */
    @ExceptionHandler(UnresolvedResourcesException.class)
    ResponseEntity<String> unresolved(UnresolvedResourcesException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .contentType(TEXT_PLAIN_UTF8)
                .body(ex.getMessage());
    }

    /**
     * Turns the framework's cryptic conversion error (e.g. an unknown {@code ?standard=...}) into an
     * actionable message that lists the accepted values, so callers know exactly what to send.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<String> typeMismatch(MethodArgumentTypeMismatchException ex) {
        StringBuilder message = new StringBuilder("Invalid value '")
                .append(ex.getValue())
                .append("' for parameter '")
                .append(ex.getName())
                .append("'.");

        Class<?> required = ex.getRequiredType();
        if (required != null && required.isEnum()) {
            String allowed = Arrays.stream(required.getEnumConstants())
                    .map(Object::toString)
                    .collect(Collectors.joining(", "));
            message.append(" Supported values: ").append(allowed).append('.');
        }
        return plainBadRequest(message.toString());
    }

    private static ResponseEntity<String> plainBadRequest(String body) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(TEXT_PLAIN_UTF8)
                .body(body);
    }
}
