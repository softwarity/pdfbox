package com.softwarity.pdfbox.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import com.softwarity.pdfbox.pdf.PdfAStandard;
import com.softwarity.pdfbox.pdf.PdfGenerationService;
import com.softwarity.pdfbox.pdf.StandardInfoService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "PDF", description = "HTML to PDF/A conversion")
public class PdfController {

    private final PdfGenerationService pdfService;
    private final StandardInfoService standardInfoService;
    private final PdfAStandard defaultStandard;

    public PdfController(PdfGenerationService pdfService,
                         StandardInfoService standardInfoService,
                         @Value("${pdfbox.default-standard}") PdfAStandard defaultStandard) {
        this.pdfService = pdfService;
        this.standardInfoService = standardInfoService;
        this.defaultStandard = defaultStandard;
    }

    /**
     * Converts an HTML document to PDF. Send the HTML as the request body and pick the output
     * standard with {@code ?standard=...} (defaults to the configured standard).
     */
    @Operation(summary = "Convert raw HTML to PDF",
            description = "HTML in the request body, standard as a query parameter. Returns application/pdf.")
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
        return pdfResponse(pdf, filename);
    }

    /**
     * Uploads a standalone HTML file (no JavaScript — openhtmltopdf does not execute it) and returns
     * the rendered PDF in the requested standard. This is the multipart form Swagger UI presents as a
     * file picker.
     */
    @Operation(summary = "Convert an uploaded HTML file to PDF",
            description = "Select a standalone HTML file (JavaScript is not executed) and a target standard; "
                    + "the response is the rendered application/pdf.")
    @PostMapping(
            value = "/pdf/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> generateFromFile(
            @RequestPart("file")
            @Schema(type = "string", format = "binary", description = "Standalone HTML file to convert")
            MultipartFile file,
            @RequestParam(name = "standard", required = false) @Nullable PdfAStandard standard,
            @RequestParam(name = "filename", required = false) @Nullable String filename)
            throws IOException {

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No HTML file was uploaded.");
        }
        String html = new String(file.getBytes(), StandardCharsets.UTF_8);
        PdfAStandard target = standard != null ? standard : defaultStandard;
        byte[] pdf = pdfService.render(html, target, null);
        return pdfResponse(pdf, StringUtils.hasText(filename) ? filename : outputName(file.getOriginalFilename()));
    }

    /** Lists the supported output standards. */
    @Operation(summary = "List supported output standards")
    @GetMapping(value = "/standards", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<PdfAStandard> standards() {
        return Arrays.asList(PdfAStandard.values());
    }

    /**
     * Returns a PDF, conforming to the requested standard, that documents the constraints that
     * standard enforces. Open {@code /api/v1/pdf-info?standard=PDF_A_1A} in a browser to both read the
     * rules and inspect a real conforming file — a living spec and end-to-end smoke test.
     */
    @Operation(summary = "Self-describing PDF of a standard's constraints",
            description = "Returns a PDF, conforming to the requested standard, that lists the "
                    + "constraints that standard enforces. Defaults to the configured standard.")
    @GetMapping(value = "/pdf-info", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> standardInfo(
            @RequestParam(name = "standard", required = false) @Nullable PdfAStandard standard) {

        PdfAStandard target = standard != null ? standard : defaultStandard;
        String html = standardInfoService.toHtml(target);
        byte[] pdf = pdfService.render(html, target, null);
        return pdfResponse(pdf, "pdf-info-" + target.name() + ".pdf", false);
    }

    private static ResponseEntity<byte[]> pdfResponse(byte[] pdf, String filename) {
        return pdfResponse(pdf, filename, true);
    }

    private static ResponseEntity<byte[]> pdfResponse(byte[] pdf, String filename, boolean attachment) {
        ContentDisposition disposition = (attachment
                ? ContentDisposition.attachment()
                : ContentDisposition.inline())
                .filename(filename)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    /** Derives {@code name.pdf} from the uploaded file name, falling back to {@code document.pdf}. */
    private static String outputName(@Nullable String original) {
        if (!StringUtils.hasText(original)) {
            return "document.pdf";
        }
        String base = StringUtils.stripFilenameExtension(StringUtils.getFilename(original));
        return StringUtils.hasText(base) ? base + ".pdf" : "document.pdf";
    }
}
