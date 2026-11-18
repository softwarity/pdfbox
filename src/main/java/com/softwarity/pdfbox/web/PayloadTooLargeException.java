package com.softwarity.pdfbox.web;

/** A multipart part or the whole upload exceeds the configured limits: mapped to a 413. */
class PayloadTooLargeException extends RuntimeException {

    PayloadTooLargeException(String message) {
        super(message);
    }
}
