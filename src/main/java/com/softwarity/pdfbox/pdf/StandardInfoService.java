package com.softwarity.pdfbox.pdf;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Builds a human-readable, self-describing HTML page that lists the constraints a given
 * {@link PdfAStandard} enforces.
 *
 * <p>Rendered through the normal pipeline ({@link PdfGenerationService#render}) with that same
 * standard, the result is a PDF that simultaneously <em>documents</em> a standard and
 * <em>conforms</em> to it — a living spec and a convenient end-to-end smoke test (feed the output to
 * {@code verapdf} to confirm the file really validates while you read what it is meant to enforce).
 *
 * <p>The constraint text is composed from three orthogonal sources so it stays complete without
 * exploding into a rule database: the rules common to every PDF/A part, the rules specific to the
 * {@linkplain PdfAStandard#part() part} (the base PDF feature set) and the guarantees of the
 * {@linkplain PdfAStandard#level() conformance level}.
 */
@Component
public class StandardInfoService {

    /** A titled list of constraint bullet points. */
    public record Section(String heading, List<String> items) {}

    /** Canonical display name, e.g. {@code "PDF/A-1A"} (or a label for {@link PdfAStandard#NONE}). */
    public String displayName(PdfAStandard s) {
        return s == PdfAStandard.NONE ? "Plain PDF (no PDF/A)" : "PDF/A-" + s.part() + s.level();
    }

    private String isoReference(PdfAStandard s) {
        return switch (s.part()) {
            case 1 -> "ISO 19005-1:2005";
            case 2 -> "ISO 19005-2:2011";
            case 3 -> "ISO 19005-3:2012";
            default -> "ISO 32000 (plain PDF)";
        };
    }

    private String basePdf(PdfAStandard s) {
        return switch (s.part()) {
            case 1 -> "PDF 1.4";
            case 2, 3 -> "PDF 1.7 (ISO 32000-1)";
            default -> "PDF (unconstrained)";
        };
    }

    /** The ordered constraint sections describing {@code s}. */
    public List<Section> sections(PdfAStandard s) {
        List<Section> out = new ArrayList<>();
        if (s == PdfAStandard.NONE) {
            out.add(new Section("No archival constraints", List.of(
                    "This is a plain PDF: none of the PDF/A archiving rules are enforced.",
                    "Fonts may be left un-embedded, colour may be device-dependent, and features such "
                            + "as JavaScript, encryption, attachments or external links are all permitted.",
                    "Pick a PDF/A standard (PDF_A_1B, PDF_A_2U, PDF_A_3A, …) to enforce the long-term "
                            + "archiving guarantees described by the other variants of this page.")));
            return out;
        }

        out.add(new Section("Conformance level — what level \"" + s.level() + "\" guarantees",
                levelGuarantees(s)));
        out.add(new Section("Always enforced (every PDF/A part)", commonRules()));
        out.add(new Section("Specific to PDF/A-" + s.part() + " (based on " + basePdf(s) + ")",
                partRules(s)));
        out.add(new Section("How this very file satisfies " + displayName(s), proof(s)));
        return out;
    }

    private List<String> levelGuarantees(PdfAStandard s) {
        List<String> b = new ArrayList<>();
        b.add("Level B (Basic): guarantees a faithful, reproducible visual appearance over the long "
                + "term — what you see stays what you get.");
        boolean unicode = "U".equals(s.level()) || "A".equals(s.level());
        if (unicode) {
            b.add("Level U (Unicode): every character additionally maps to Unicode (ToUnicode CMaps), "
                    + "so the text stays reliably searchable, copyable and extractable.");
        }
        if ("A".equals(s.level())) {
            b.add("Level A (Accessible): adds a tagged logical structure (Tagged PDF / PDF/UA) — "
                    + "defined reading order, heading hierarchy, alternate text for images and an "
                    + "explicit document language — usable by assistive technology such as screen "
                    + "readers.");
        }
        if ("B".equals(s.level())) {
            b.add("This level does NOT require Unicode mapping nor a tagged structure; choose a U or A "
                    + "level if you need searchable text or accessibility.");
        }
        return b;
    }

    private List<String> commonRules() {
        return List.of(
                "All fonts are fully embedded (subsetted) — no reliance on fonts installed on the "
                        + "reader's machine.",
                "No encryption and no password protection.",
                "No JavaScript and no launch / executable actions.",
                "No audio, video or 3D (embedded multimedia) content.",
                "Self-contained: no references to external files or remote content.",
                "Device-independent colour: an ICC output intent (the sRGB profile here) is embedded so "
                        + "colours are reproducible across devices.",
                "Mandatory XMP metadata, including the PDF/A identifier (pdfaid:part and "
                        + "pdfaid:conformance) and metadata kept consistent with the document info.",
                "Every annotation carries a printable appearance stream; hidden or non-printable "
                        + "annotations are not allowed.",
                "Transparency-group, transfer-function and other rendering features are restricted to "
                        + "what guarantees deterministic output.");
    }

    private List<String> partRules(PdfAStandard s) {
        return switch (s.part()) {
            case 1 -> List.of(
                    "Header and catalog version is PDF 1.4.",
                    "No transparency and no blend modes.",
                    "No optional-content layers.",
                    "No embedded files or attachments of any kind.",
                    "LZW compression is forbidden (Flate is the allowed stream filter).",
                    "No object streams and no cross-reference streams — a classic cross-reference "
                            + "table is required (this is why pdfbox re-saves PDF/A output uncompressed).");
            case 2 -> List.of(
                    "Header and catalog version is PDF 1.7 (ISO 32000-1).",
                    "Transparency and blend modes are allowed.",
                    "Optional-content layers are allowed.",
                    "JPEG 2000 (JPXDecode) image compression is allowed.",
                    "Object streams and compressed cross-reference streams are allowed.",
                    "Other PDF/A files (part 1 or part 2) may be embedded as attachments.",
                    "Supports PDF/A collections (portfolios) and digital signatures.");
            case 3 -> List.of(
                    "Header and catalog version is PDF 1.7 (ISO 32000-1).",
                    "Everything PDF/A-2 allows (transparency, layers, JPEG 2000, object streams, …).",
                    "Attachments may be files of ANY format — not only PDF/A.",
                    "This is what enables hybrid invoices such as Factur-X / ZUGFeRD: a human-readable "
                            + "PDF with its machine-readable source (XML, CSV, spreadsheet) embedded.");
            default -> List.of();
        };
    }

    private List<String> proof(PdfAStandard s) {
        List<String> p = new ArrayList<>();
        p.add("Declared conformance: " + displayName(s) + " (" + isoReference(s) + ").");
        p.add("PDF version written in the header and catalog: " + s.pdfVersion() + ".");
        p.add("openhtmltopdf conformance flag used to generate it: " + s.conformance() + ".");
        p.add("Accessibility / PDF-UA tagging: "
                + (s.isAccessible() ? "enabled (this is an \"A\" level)" : "not required at this level")
                + ".");
        p.add("Produced fully offline by softwarity/pdfbox with bundled fonts and the sRGB profile — "
                + "no external services or runtime configuration.");
        return p;
    }

    /** Renders the constraints of {@code s} as a standalone, self-contained HTML document. */
    public String toHtml(PdfAStandard s) {
        String name = displayName(s);
        StringBuilder main = new StringBuilder();
        for (Section section : sections(s)) {
            main.append("<section><h2>").append(escape(section.heading())).append("</h2><ul>");
            for (String item : section.items()) {
                main.append("<li>").append(escape(item)).append("</li>");
            }
            main.append("</ul></section>");
        }

        return "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>"
                + escape(name) + " — applied constraints</title><style>" + css() + "</style></head>"
                + "<body><header><p class=\"eyebrow\">PDF output standard</p><h1>" + escape(name)
                + "</h1><p class=\"lead\">Constraints enforced by <strong>" + escape(name)
                + "</strong>. This page is itself generated as " + escape(name) + ", so it both "
                + "describes the standard and demonstrates conformance to it.</p>"
                + summaryTable(s) + "</header><main>" + main + "</main><footer>Generated offline by "
                + "softwarity/pdfbox · " + escape(isoReference(s)) + "</footer></body></html>";
    }

    private String summaryTable(PdfAStandard s) {
        return "<table class=\"summary\"><tbody>"
                + row("Standard", escape(displayName(s)))
                + row("Reference", escape(isoReference(s)))
                + row("Base format", escape(basePdf(s)))
                + row("Conformance level", s == PdfAStandard.NONE ? "—" : escape(s.level()))
                + row("Searchable text", "A".equals(s.level()) || "U".equals(s.level())
                        ? "Guaranteed (Unicode mapping)" : "Not guaranteed at this level")
                + row("Accessible (tagged)", s.isAccessible() ? "Yes (PDF/UA structure)" : "No")
                + "</tbody></table>";
    }

    private String row(String key, String value) {
        return "<tr><th>" + escape(key) + "</th><td>" + value + "</td></tr>";
    }

    /**
     * Deliberately transparency-free CSS (no {@code box-shadow}, {@code rgba} or {@code opacity}):
     * the very same markup is rendered to PDF/A-1, which forbids transparency.
     */
    private String css() {
        return "body{color:#1a1a1a;margin:0;padding:32px 40px;line-height:1.5;}"
                + "header{border-bottom:3px solid #0b5fff;padding-bottom:16px;margin-bottom:8px;}"
                + ".eyebrow{text-transform:uppercase;letter-spacing:.08em;font-size:11px;color:#0b5fff;"
                + "margin:0;font-weight:700;}"
                + "h1{font-size:28px;margin:4px 0 8px;}"
                + ".lead{color:#444;margin:0 0 16px;font-size:14px;}"
                + "table.summary{border-collapse:collapse;width:100%;font-size:13px;}"
                + "table.summary th{text-align:left;color:#555;font-weight:600;padding:3px 12px 3px 0;"
                + "width:150px;vertical-align:top;}"
                + "table.summary td{padding:3px 0;vertical-align:top;}"
                + "h2{font-size:16px;color:#0b3a8c;border-left:4px solid #0b5fff;padding-left:10px;"
                + "margin:24px 0 10px;}"
                + "ul{margin:0;padding-left:20px;}"
                + "li{margin:5px 0;font-size:13.5px;}"
                + "footer{margin-top:32px;border-top:1px solid #dddddd;padding-top:10px;color:#888888;"
                + "font-size:11px;}";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
