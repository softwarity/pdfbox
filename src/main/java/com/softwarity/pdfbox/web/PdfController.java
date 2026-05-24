package com.softwarity.pdfbox.web;

import java.util.Arrays;
import java.util.List;

import com.softwarity.pdfbox.pdf.PdfAStandard;
import com.softwarity.pdfbox.pdf.PdfGenerationService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class PdfController {

    private final PdfGenerationService pdfService;
    private final PdfAStandard defaultStandard;

    public PdfController(PdfGenerationService pdfService,
                         @Value("${pdfbox.default-standard}") PdfAStandard defaultStandard) {
        this.pdfService = pdfService;
        this.defaultStandard = defaultStandard;
    }

    /**
     * Converts an HTML document to PDF. Send the HTML as the request body and pick the output
     * standard with {@code ?standard=...} (defaults to the configured standard).
     */
    @PostMapping(
            value = "/pdf",
            consumes = {MediaType.TEXT_HTML_VALUE, MediaType.APPLICATION_XHTML_XML_VALUE,
                    MediaType.TEXT_PLAIN_VALUE, MediaType.APPLICATION_OCTET_STREAM_VALUE},
            produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> generate(
            @RequestBody String html,
            @RequestParam(name = "standard", required = false) @Nullable PdfAStandard standard,
            @RequestParam(name = "filename", defaultValue = "document.pdf") String filename) {

        PdfAStandard target = standard != null ? standard : defaultStandard;
        byte[] pdf = pdfService.render(html, target, null);

        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(filename)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    /** Lists the supported output standards. */
    @GetMapping("/standards")
    public List<PdfAStandard> standards() {
        return Arrays.asList(PdfAStandard.values());
    }
}
