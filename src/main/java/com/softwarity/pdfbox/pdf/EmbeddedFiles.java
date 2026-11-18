package com.softwarity.pdfbox.pdf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment;

/**
 * Completes the files openhtmltopdf embeds for {@code <a href="…" download="name">}: it writes each
 * one as a file-attachment annotation where the link is, but leaves out what PDF/A-3 and invoice
 * readers rely on. This pass, run on the finished document:
 *
 * <ul>
 *   <li>associates every embedded file with the document — the catalog {@code /AF} array, required by
 *       PDF/A-3 (ISO 19005-3 §6.8) and by Factur-X / ZUGFeRD;</li>
 *   <li>lists them in the {@code /Names /EmbeddedFiles} tree, where viewers and invoice tools look;</li>
 *   <li>fills what the HTML did not give: {@code /AFRelationship} ({@code relationship} attribute,
 *       default {@code Unspecified}, or the Factur-X default), the MIME subtype
 *       ({@code data-content-type} attribute, else from the file extension) and the modification
 *       date;</li>
 *   <li>declares a Factur-X / ZUGFeRD / XRechnung invoice in the XMP when the document is PDF/A-3
 *       ({@link FacturX});</li>
 *   <li>in tagged output, re-tags the annotation as {@code Annot} (openhtmltopdf tags it as the
 *       {@code Link} it was written as; PDF/UA-1 §7.18.1 wants non-link annotations in an
 *       {@code Annot} element) and gives it {@code /Contents}.</li>
 * </ul>
 */
final class EmbeddedFiles {

    private static final COSName AF = COSName.getPDFName("AF");
    private static final COSName AF_RELATIONSHIP = COSName.getPDFName("AFRelationship");
    private static final COSName ANNOT = COSName.getPDFName("Annot");
    private static final COSName LINK = COSName.getPDFName("Link");
    private static final COSName NUMS = COSName.getPDFName("Nums");
    private static final String OCTET_STREAM = "application/octet-stream";

    /** Subtype by extension, used when the HTML did not set {@code data-content-type}. */
    private static final Map<String, String> MEDIA_TYPES = Map.of(
            "xml", "text/xml",
            "json", "application/json",
            "csv", "text/csv",
            "txt", "text/plain",
            "pdf", "application/pdf");

    private EmbeddedFiles() {
    }

    static void register(PDDocument doc, PdfAStandard standard) throws IOException {
        List<PDComplexFileSpecification> specs = fileSpecifications(doc);
        if (specs.isEmpty()) {
            return;
        }
        FacturX.Invoice invoice = standard.part() == 3 ? FacturX.detect(specs) : null;

        Map<String, PDComplexFileSpecification> byName = new LinkedHashMap<>();
        COSArray associated = new COSArray();
        for (PDComplexFileSpecification spec : specs) {
            String name = spec.getFilename();
            complete(spec, name, invoice);
            String key = name;
            for (int i = 2; byName.containsKey(key); i++) {
                key = name + " (" + i + ")";
            }
            byName.put(key, spec);
            associated.add(spec.getCOSObject());
        }

        PDDocumentCatalog catalog = doc.getDocumentCatalog();
        catalog.getCOSObject().setItem(AF, associated);
        PDDocumentNameDictionary names = catalog.getNames();
        if (names == null) {
            names = new PDDocumentNameDictionary(catalog);
            catalog.setNames(names);
        }
        PDEmbeddedFilesNameTreeNode tree = new PDEmbeddedFilesNameTreeNode();
        tree.setNames(byName);
        names.setEmbeddedFiles(tree);

        if (invoice != null) {
            FacturX.declare(doc, invoice);
        }
        if (standard.isAccessible()) {
            tagAnnotations(doc);
        }
    }

    /** Tagged output: each file-attachment annotation in an {@code Annot} element, with a text alternative. */
    private static void tagAnnotations(PDDocument doc) throws IOException {
        var root = doc.getDocumentCatalog().getStructureTreeRoot();
        COSDictionary parentTree = root == null ? null : root.getCOSObject().getCOSDictionary(COSName.PARENT_TREE);
        for (PDPage page : doc.getPages()) {
            for (PDAnnotation annotation : page.getAnnotations()) {
                if (!(annotation instanceof PDAnnotationFileAttachment attachment)) {
                    continue;
                }
                if (attachment.getContents() == null
                        && attachment.getFile() instanceof PDComplexFileSpecification spec) {
                    String description = spec.getFileDescription();
                    attachment.setContents("Embedded file " + spec.getFilename()
                            + (description == null || description.isBlank() ? "" : ": " + description));
                }
                int key = attachment.getStructParent();
                if (parentTree != null && key >= 0
                        && parentTree(parentTree, key) instanceof COSDictionary element
                        && LINK.equals(element.getCOSName(COSName.S))) {
                    element.setItem(COSName.S, ANNOT);
                }
            }
        }
    }

    /** The value of {@code key} in a number tree (flat {@code /Nums} or nested {@code /Kids}). */
    private static org.apache.pdfbox.cos.COSBase parentTree(COSDictionary node, int key) {
        if (node.getDictionaryObject(NUMS) instanceof COSArray nums) {
            for (int i = 0; i + 1 < nums.size(); i += 2) {
                if (nums.getObject(i) instanceof org.apache.pdfbox.cos.COSInteger k && k.intValue() == key) {
                    return nums.getObject(i + 1);
                }
            }
        }
        if (node.getDictionaryObject(COSName.KIDS) instanceof COSArray kids) {
            for (int i = 0; i < kids.size(); i++) {
                if (kids.getObject(i) instanceof COSDictionary kid) {
                    var found = parentTree(kid, key);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return null;
    }

    /** The file specifications of every file-attachment annotation, each once. */
    private static List<PDComplexFileSpecification> fileSpecifications(PDDocument doc) throws IOException {
        Map<COSDictionary, PDComplexFileSpecification> found = new IdentityHashMap<>();
        List<PDComplexFileSpecification> ordered = new ArrayList<>();
        for (PDPage page : doc.getPages()) {
            for (PDAnnotation annotation : page.getAnnotations()) {
                if (annotation instanceof PDAnnotationFileAttachment attachment
                        && attachment.getFile() instanceof PDComplexFileSpecification spec
                        && spec.getEmbeddedFile() != null
                        && found.putIfAbsent(spec.getCOSObject(), spec) == null) {
                    ordered.add(spec);
                }
            }
        }
        return ordered;
    }

    private static void complete(PDComplexFileSpecification spec, String name, FacturX.Invoice invoice)
            throws IOException {
        COSDictionary dict = spec.getCOSObject();
        if (spec.getFileUnicode() == null) {
            spec.setFileUnicode(name);
        }
        if (!dict.containsKey(AF_RELATIONSHIP)) {
            String relationship = invoice != null && invoice.fileName().equals(name)
                    ? invoice.defaultRelationship() : "Unspecified";
            dict.setName(AF_RELATIONSHIP, relationship);
        }
        PDEmbeddedFile file = spec.getEmbeddedFile();
        String subtype = file.getSubtype();
        if (subtype == null || subtype.isBlank() || OCTET_STREAM.equals(subtype)) {
            file.setSubtype(MEDIA_TYPES.getOrDefault(extension(name), OCTET_STREAM));
        }
        if (file.getModDate() == null) {
            file.setModDate(new GregorianCalendar());
        }
        if (file.getCreationDate() == null) {
            Calendar created = file.getModDate();
            file.setCreationDate(created);
        }
    }

    private static String extension(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
