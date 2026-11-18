package com.softwarity.pdfbox.pdf;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Factur-X / ZUGFeRD 2.x / XRechnung hybrid invoices: a PDF/A-3 whose embedded {@code factur-x.xml}
 * (or {@code xrechnung.xml}) carries the structured CII invoice. Besides the embedded file itself
 * (handled by {@link EmbeddedFiles}), the specification requires the XMP metadata to declare the
 * invoice — document type, file name, version and conformance level, in the {@code fx} namespace — and,
 * PDF/A oblige, that namespace to be described by a PDF/A extension schema.
 *
 * <p>The conformance level is read from the invoice itself
 * ({@code GuidelineSpecifiedDocumentContextParameter/ID}), so the HTML only has to embed the file.
 */
final class FacturX {

    static final String NAMESPACE = "urn:factur-x:pdfa:CrossIndustryDocument:invoice:1p0#";
    private static final String EXTENSION_NS = "http://www.aiim.org/pdfa/ns/extension/";

    /** The embedded invoice: its file name and the Factur-X conformance level it declares. */
    record Invoice(String fileName, String conformanceLevel) {
        /** {@code Data} for the non-EN 16931 profiles (MINIMUM, BASIC WL), {@code Alternative} otherwise. */
        String defaultRelationship() {
            return conformanceLevel.equals("MINIMUM") || conformanceLevel.equals("BASIC WL") ? "Data" : "Alternative";
        }
    }

    private FacturX() {
    }

    /**
     * The invoice among the embedded files ({@code factur-x.xml} or {@code xrechnung.xml}), or
     * {@code null} if there is none.
     *
     * @throws PdfGenerationException if such a file is embedded but declares no known profile
     */
    static Invoice detect(List<PDComplexFileSpecification> specs) throws IOException {
        for (PDComplexFileSpecification spec : specs) {
            String name = spec.getFilename();
            if (!"factur-x.xml".equals(name) && !"xrechnung.xml".equals(name)) {
                continue;
            }
            String guideline = guideline(spec.getEmbeddedFile().toByteArray());
            String level = level(guideline);
            if (level == null) {
                throw new PdfGenerationException("'" + name + "' is embedded in a PDF/A-3, so it must be a "
                        + "Factur-X / ZUGFeRD / XRechnung CII invoice, but its "
                        + "GuidelineSpecifiedDocumentContextParameter/ID is "
                        + (guideline == null ? "missing" : "'" + guideline + "', which is not a known profile")
                        + ". Rename the file to embed it as a plain attachment.");
            }
            return new Invoice(name, level);
        }
        return null;
    }

    /** Factur-X conformance level of a CII guideline identifier, or {@code null} if unknown. */
    static String level(String guideline) {
        if (guideline == null) {
            return null;
        }
        String g = guideline.trim();
        if (g.endsWith("urn:factur-x.eu:1p0:minimum")) {
            return "MINIMUM";
        }
        if (g.endsWith("urn:factur-x.eu:1p0:basicwl")) {
            return "BASIC WL";
        }
        if (g.endsWith("urn:factur-x.eu:1p0:basic")) {
            return "BASIC";
        }
        if (g.endsWith("urn:factur-x.eu:1p0:extended")) {
            return "EXTENDED";
        }
        if (g.contains("xrechnung")) {
            return "XRECHNUNG";
        }
        if (g.equals("urn:cen.eu:en16931:2017")) {
            return "EN 16931";
        }
        return null;
    }

    /** The text of {@code GuidelineSpecifiedDocumentContextParameter/ID}, or {@code null}. */
    private static String guideline(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            NodeList parameters = document.getElementsByTagNameNS("*", "GuidelineSpecifiedDocumentContextParameter");
            for (int i = 0; i < parameters.getLength(); i++) {
                for (Node child = parameters.item(i).getFirstChild(); child != null; child = child.getNextSibling()) {
                    if (child instanceof Element e && "ID".equals(e.getLocalName())) {
                        return e.getTextContent().trim();
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return null; // not well-formed XML: no profile
        }
    }

    /** Adds the {@code fx} properties and their PDF/A extension schema to the document XMP. */
    static void declare(PDDocument doc, Invoice invoice) throws IOException {
        PDMetadata metadata = doc.getDocumentCatalog().getMetadata();
        if (metadata == null) {
            throw new PdfGenerationException("PDF/A document without XMP metadata: cannot declare the invoice");
        }
        String xmp = new String(metadata.toByteArray(), StandardCharsets.UTF_8);
        if (xmp.contains(NAMESPACE)) {
            return;
        }
        String schema = "<rdf:li rdf:parseType=\"Resource\" xmlns:pdfaSchema=\"http://www.aiim.org/pdfa/ns/schema#\""
                + " xmlns:pdfaProperty=\"http://www.aiim.org/pdfa/ns/property#\">"
                + "<pdfaSchema:schema>Factur-X PDFA Extension Schema</pdfaSchema:schema>"
                + "<pdfaSchema:namespaceURI>" + NAMESPACE + "</pdfaSchema:namespaceURI>"
                + "<pdfaSchema:prefix>fx</pdfaSchema:prefix>"
                + "<pdfaSchema:property><rdf:Seq>"
                + property("DocumentFileName", "The name of the embedded XML document")
                + property("DocumentType", "The type of the hybrid document in capital letters, e.g. INVOICE or ORDER")
                + property("Version", "The actual version of the standard applying to the embedded XML document")
                + property("ConformanceLevel", "The conformance level of the embedded XML document")
                + "</rdf:Seq></pdfaSchema:property></rdf:li>";
        String fx = "<rdf:Description rdf:about=\"\" xmlns:fx=\"" + NAMESPACE + "\">"
                + "<fx:DocumentType>INVOICE</fx:DocumentType>"
                + "<fx:DocumentFileName>" + invoice.fileName() + "</fx:DocumentFileName>"
                + "<fx:Version>1.0</fx:Version>"
                + "<fx:ConformanceLevel>" + invoice.conformanceLevel() + "</fx:ConformanceLevel>"
                + "</rdf:Description>";

        // An extension-schema bag may already exist (the pdfuaid schema of the accessible "A" levels):
        // the schema joins it, as a second pdfaExtension:schemas property would be invalid RDF.
        Matcher bag = existingSchemaBag(xmp);
        String updated;
        if (bag != null) {
            updated = xmp.substring(0, bag.end()) + schema + xmp.substring(bag.end());
        } else {
            fx = fx + "<rdf:Description rdf:about=\"\" xmlns:pdfaExtension=\"" + EXTENSION_NS + "\">"
                    + "<pdfaExtension:schemas><rdf:Bag>" + schema + "</rdf:Bag></pdfaExtension:schemas>"
                    + "</rdf:Description>";
            updated = xmp;
        }
        int end = updated.lastIndexOf("</rdf:RDF>");
        if (end < 0) {
            throw new PdfGenerationException("Unexpected XMP metadata layout: cannot declare the invoice");
        }
        updated = updated.substring(0, end) + fx + updated.substring(end);
        metadata.importXMPMetadata(updated.getBytes(StandardCharsets.UTF_8));
    }

    private static String property(String name, String description) {
        return "<rdf:li rdf:parseType=\"Resource\">"
                + "<pdfaProperty:name>" + name + "</pdfaProperty:name>"
                + "<pdfaProperty:valueType>Text</pdfaProperty:valueType>"
                + "<pdfaProperty:category>external</pdfaProperty:category>"
                + "<pdfaProperty:description>" + description + "</pdfaProperty:description>"
                + "</rdf:li>";
    }

    /** The opening {@code <prefix:schemas><rdf:Bag>} of an existing PDF/A extension schema list. */
    private static Matcher existingSchemaBag(String xmp) {
        Matcher ns = Pattern.compile("xmlns:(\\w+)=\"" + Pattern.quote(EXTENSION_NS) + "\"").matcher(xmp);
        if (!ns.find()) {
            return null;
        }
        Matcher bag = Pattern.compile("<" + ns.group(1) + ":schemas>\\s*<rdf:Bag>").matcher(xmp);
        return bag.find() ? bag : null;
    }
}
