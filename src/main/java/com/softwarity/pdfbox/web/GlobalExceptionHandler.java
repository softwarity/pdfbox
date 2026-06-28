package com.softwarity.pdfbox.web;

import java.util.Arrays;
import java.util.stream.Collectors;

import com.softwarity.pdfbox.pdf.PdfGenerationException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler({PdfGenerationException.class, MethodArgumentNotValidException.class,
            IllegalArgumentException.class})
    ResponseEntity<String> badRequest(Exception ex) {
        return plainBadRequest(ex.getMessage());
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
                .contentType(MediaType.TEXT_PLAIN)
                .body(body);
    }
}
