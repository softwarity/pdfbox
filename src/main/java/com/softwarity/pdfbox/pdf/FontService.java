package com.softwarity.pdfbox.pdf;

import java.awt.Font;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.openhtmltopdf.extend.FSSupplier;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FSFontUseCase;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

/**
 * Discovers font files once at startup and registers, per render, only the faces a document actually
 * needs, so generation stays fully offline and PDF/A compliant (all glyphs embedded) without parsing
 * every bundled face on every request.
 *
 * <p><b>Why per-render selection.</b> openhtmltopdf eagerly parses every font it is told about (both
 * the fallback store and every family named in the CSS stack). Registering the whole Noto set on each
 * render therefore re-parses tens of MB — including huge CJK faces — for a plain-Latin document. So we
 * scan the source for the scripts it contains (by Unicode block) and only register the matching
 * families. Latin/Cyrillic/Greek are always covered by the always-on {@code Noto Sans}/{@code Noto
 * Serif}/{@code Noto Sans Mono}; other scripts opt in by content. Shared CJK Han ideographs are
 * disambiguated to JP/KR/SC/TC via the document {@code lang} (defaulting to Japanese).
 *
 * <p>Only TrueType ({@code .ttf}) fonts are registered: the PDFBox renderer cannot embed OpenType/CFF
 * ({@code .otf}) outlines and throws while building the document, so such files are skipped. A broad
 * Noto set is bundled on the classpath; heavy CJK faces are expected as filesystem drop-ins (Docker
 * image) so they never bloat the jar.
 */
@Service
public class FontService {

    private static final Logger log = LoggerFactory.getLogger(FontService.class);

    /** Bundled fonts live under {@code src/main/resources/fonts} and ship inside the jar. */
    private static final String CLASSPATH_FONTS = "classpath*:fonts/**/*.ttf";

    /**
     * Extra family names registered as aliases for a discovered family, so HTML can reference a font
     * under the name people commonly type. The bundled {@code Noto Sans JP} also answers to the
     * canonical {@code Noto Sans CJK *} names (their Han glyphs are shared) instead of rendering tofu.
     *
     * <p>Note: CSS generic families ({@code serif}/{@code sans-serif}/{@code monospace}) are
     * <em>not</em> handled here — openhtmltopdf treats those keywords specially and never consults a
     * font registered under that name. They are covered instead by the fallback registration.
     */
    private static final Map<String, List<String>> FAMILY_ALIASES = Map.of(
            "Noto Sans JP", List.of("Noto Sans CJK JP"),
            "Noto Sans SC", List.of("Noto Sans CJK SC"),
            "Noto Sans TC", List.of("Noto Sans CJK TC"),
            "Noto Sans KR", List.of("Noto Sans CJK KR"));

    /**
     * Always-on families: parsed on every render (light Latin faces). {@code Noto Sans}/{@code Noto
     * Serif}/{@code Noto Sans Mono} also join the fallback store ({@link FSFontUseCase#FALLBACK_PRE}),
     * which is what openhtmltopdf consults when a {@code font-family} resolves to nothing (CSS generics
     * {@code serif}/{@code sans-serif}/{@code monospace}, or an unbundled system face) and for glyphs
     * missing from the resolved face. A non-empty fallback silences the per-run "Font list is empty"
     * warning, and these three map the generics. The fallback store is loaded eagerly, so it is kept
     * to exactly these three.
     */
    private static final Set<String> FALLBACK_FAMILIES =
            Set.of("Noto Sans", "Noto Serif", "Noto Sans Mono");

    /**
     * Non-Latin scripts, each mapped to the family that covers it and the Unicode block(s) that signal
     * its presence. A family here is registered for a render only if the source contains a matching
     * codepoint. Families not yet bundled are simply never matched (no face), so this list can describe
     * more scripts than are currently shipped. Shared CJK Han is handled separately (see {@link
     * #hanFamily}); {@code Noto Sans KR} appears here for Hangul syllables/jamo.
     */
    private record ScriptFamily(String family, IntPredicate covers) {}

    private static final List<ScriptFamily> SCRIPT_FAMILIES = List.of(
            new ScriptFamily("Noto Sans Armenian",   cp -> in(cp, 0x0530, 0x058F)),
            new ScriptFamily("Noto Sans Hebrew",     cp -> in(cp, 0x0590, 0x05FF) || in(cp, 0xFB1D, 0xFB4F)),
            new ScriptFamily("Noto Sans Arabic",     cp -> in(cp, 0x0600, 0x06FF) || in(cp, 0x0750, 0x077F)
                    || in(cp, 0x08A0, 0x08FF) || in(cp, 0xFB50, 0xFDFF) || in(cp, 0xFE70, 0xFEFF)),
            new ScriptFamily("Noto Sans Thaana",     cp -> in(cp, 0x0780, 0x07BF)),
            new ScriptFamily("Noto Sans Devanagari", cp -> in(cp, 0x0900, 0x097F)),
            new ScriptFamily("Noto Sans Bengali",    cp -> in(cp, 0x0980, 0x09FF)),
            new ScriptFamily("Noto Sans Gurmukhi",   cp -> in(cp, 0x0A00, 0x0A7F)),
            new ScriptFamily("Noto Sans Gujarati",   cp -> in(cp, 0x0A80, 0x0AFF)),
            new ScriptFamily("Noto Sans Oriya",      cp -> in(cp, 0x0B00, 0x0B7F)),
            new ScriptFamily("Noto Sans Tamil",      cp -> in(cp, 0x0B80, 0x0BFF)),
            new ScriptFamily("Noto Sans Telugu",     cp -> in(cp, 0x0C00, 0x0C7F)),
            new ScriptFamily("Noto Sans Kannada",    cp -> in(cp, 0x0C80, 0x0CFF)),
            new ScriptFamily("Noto Sans Malayalam",  cp -> in(cp, 0x0D00, 0x0D7F)),
            new ScriptFamily("Noto Sans Sinhala",    cp -> in(cp, 0x0D80, 0x0DFF)),
            new ScriptFamily("Noto Sans Thai",       cp -> in(cp, 0x0E00, 0x0E7F)),
            new ScriptFamily("Noto Sans Lao",        cp -> in(cp, 0x0E80, 0x0EFF)),
            new ScriptFamily("Noto Sans Myanmar",    cp -> in(cp, 0x1000, 0x109F)),
            new ScriptFamily("Noto Sans Georgian",   cp -> in(cp, 0x10A0, 0x10FF) || in(cp, 0x1C90, 0x1CBF)),
            new ScriptFamily("Noto Sans Ethiopic",   cp -> in(cp, 0x1200, 0x137F)),
            new ScriptFamily("Noto Sans Khmer",      cp -> in(cp, 0x1780, 0x17FF)),
            new ScriptFamily("Noto Sans JP",         cp -> in(cp, 0x3040, 0x30FF) || in(cp, 0x31F0, 0x31FF)),
            new ScriptFamily("Noto Sans KR",         cp -> in(cp, 0x1100, 0x11FF) || in(cp, 0xAC00, 0xD7A3)),
            new ScriptFamily("Noto Sans Symbols",    cp -> in(cp, 0x2190, 0x21FF) || in(cp, 0x2300, 0x23FF)),
            new ScriptFamily("Noto Sans Symbols2",   cp -> in(cp, 0x2600, 0x27BF) || in(cp, 0x2B00, 0x2BFF)),
            new ScriptFamily("Noto Sans Math",       cp -> in(cp, 0x2200, 0x22FF) || in(cp, 0x2A00, 0x2AFF)),
            new ScriptFamily("Noto Emoji",           cp -> in(cp, 0x1F300, 0x1FAFF) || in(cp, 0x1F000, 0x1F0FF)));

    /** Every family that is content-gated (registered only when its script is present). */
    private static final Set<String> SCRIPT_FAMILY_NAMES = Stream.concat(
                    SCRIPT_FAMILIES.stream().map(ScriptFamily::family),
                    Stream.of("Noto Sans SC", "Noto Sans TC"))
            .collect(Collectors.toUnmodifiableSet());

    private static final Pattern LANG_ATTR =
            Pattern.compile("lang\\s*=\\s*[\"']?([A-Za-z][A-Za-z-]*)");

    private static final Set<FSFontUseCase> DOCUMENT_AND_FALLBACK =
            EnumSet.of(FSFontUseCase.DOCUMENT, FSFontUseCase.FALLBACK_PRE);
    private static final Set<FSFontUseCase> DOCUMENT_ONLY = EnumSet.of(FSFontUseCase.DOCUMENT);

    private final List<String> directories;
    private final List<FontFace> faces = new ArrayList<>();

    public FontService(@Value("${pdfbox.fonts.directories}") List<String> directories) {
        this.directories = directories;
    }

    /** A registrable face; backed either by a filesystem {@code file} or by in-memory {@code data}. */
    private record FontFace(File file, byte[] data, String family, int weight, FontStyle style) {}

    @PostConstruct
    void discover() {
        indexClasspathFonts();
        indexFilesystemFonts();
        log.info("Indexed {} embeddable font face(s) ({} bundled on classpath, plus {})",
                faces.size(), faces.stream().filter(f -> f.file() == null).count(), directories);
    }

    private void indexClasspathFonts() {
        try {
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources(CLASSPATH_FONTS)) {
                String name = resource.getFilename();
                if (name == null) {
                    continue;
                }
                try (InputStream in = resource.getInputStream()) {
                    byte[] data = in.readAllBytes();
                    String family = readFamily(new ByteArrayInputStream(data));
                    String lower = name.toLowerCase(Locale.ROOT);
                    faces.add(new FontFace(null, data, family, weightFromName(lower), styleFromName(lower)));
                } catch (Exception e) {
                    log.debug("Skipping unreadable bundled font {}: {}", name, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Could not scan bundled fonts: {}", e.getMessage());
        }
    }

    private void indexFilesystemFonts() {
        for (String dir : directories) {
            Path root = Paths.get(dir);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile).forEach(this::indexFile);
            } catch (IOException e) {
                log.warn("Could not scan font directory {}: {}", root, e.getMessage());
            }
        }
    }

    private void indexFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".otf")) {
            log.debug("Skipping OpenType/CFF font (unsupported by the PDF renderer): {}", path);
            return;
        }
        if (!name.endsWith(".ttf")) {
            return;
        }
        File file = path.toFile();
        try {
            String family = Font.createFont(Font.TRUETYPE_FONT, file).getFamily(Locale.ENGLISH);
            faces.add(new FontFace(file, null, family, weightFromName(name), styleFromName(name)));
        } catch (Exception e) {
            log.debug("Skipping unreadable font {}: {}", path, e.getMessage());
        }
    }

    private static String readFamily(InputStream in) throws Exception {
        return Font.createFont(Font.TRUETYPE_FONT, in).getFamily(Locale.ENGLISH);
    }

    private static int weightFromName(String name) {
        if (name.contains("thin")) return 100;
        if (name.contains("extralight") || name.contains("ultralight")) return 200;
        if (name.contains("semibold") || name.contains("demibold")) return 600;
        if (name.contains("extrabold") || name.contains("ultrabold")) return 800;
        if (name.contains("black") || name.contains("heavy")) return 900;
        if (name.contains("light")) return 300;
        if (name.contains("medium")) return 500;
        if (name.contains("bold")) return 700;
        return 400;
    }

    private static FontStyle styleFromName(String name) {
        if (name.contains("italic") || name.contains("oblique")) {
            return FontStyle.ITALIC;
        }
        return FontStyle.NORMAL;
    }

    /**
     * Registers the faces this document needs: the always-on Latin families (plus any non-script
     * filesystem drop-in, kept available by name), and the script families whose Unicode block appears
     * in {@code html}. Embeds only the glyphs actually used.
     */
    public void registerFonts(PdfRendererBuilder builder, String html) {
        Set<String> activeScripts = detectScriptFamilies(html == null ? "" : html);
        faces.forEach(f -> {
            if (SCRIPT_FAMILY_NAMES.contains(f.family()) && !activeScripts.contains(f.family())) {
                return; // a script font this document does not need — don't make openhtmltopdf parse it
            }
            boolean fallback = f.file() == null && FALLBACK_FAMILIES.contains(f.family());
            register(builder, f, f.family(), fallback ? DOCUMENT_AND_FALLBACK : DOCUMENT_ONLY);
            FAMILY_ALIASES.getOrDefault(f.family(), List.of())
                    .forEach(alias -> register(builder, f, alias, DOCUMENT_ONLY));
        });
    }

    /**
     * Scans the source for non-Latin scripts and returns the script families to enable. Latin /
     * Cyrillic / Greek are always covered by the always-on families, so only codepoints {@code >=
     * U+0530} are inspected. Shared CJK Han ideographs pick their JP/KR/SC/TC variant from {@code lang}.
     */
    private Set<String> detectScriptFamilies(String html) {
        Set<String> active = new HashSet<>();
        boolean han = false;
        for (int i = 0, n = html.length(); i < n; ) {
            int cp = html.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 0x0530) {
                continue;
            }
            if (isHan(cp)) {
                han = true;
            } else {
                SCRIPT_FAMILIES.stream().filter(sf -> sf.covers().test(cp)).findFirst()
                        .ifPresent(sf -> active.add(sf.family()));
            }
        }
        if (han) {
            active.add(hanFamily(html));
        }
        return active;
    }

    private static boolean in(int cp, int lo, int hi) {
        return cp >= lo && cp <= hi;
    }

    /** Unified CJK ideographs (incl. extensions), shared across JP/KR/SC/TC. */
    private static boolean isHan(int cp) {
        return in(cp, 0x3400, 0x4DBF) || in(cp, 0x4E00, 0x9FFF)
                || in(cp, 0xF900, 0xFAFF) || in(cp, 0x20000, 0x2FA1F);
    }

    /** Picks the CJK family for shared Han ideographs from the declared lang(s); defaults to Japanese. */
    private static String hanFamily(String html) {
        List<String> langs = LANG_ATTR.matcher(html).results()
                .map(m -> m.group(1).toLowerCase(Locale.ROOT)).toList();
        if (langs.stream().anyMatch(l -> l.startsWith("ko"))) return "Noto Sans KR";
        if (langs.stream().anyMatch(l -> l.startsWith("ja"))) return "Noto Sans JP";
        if (langs.stream().anyMatch(FontService::isTraditionalChinese)) return "Noto Sans TC";
        if (langs.stream().anyMatch(l -> l.startsWith("zh"))) return "Noto Sans SC";
        return "Noto Sans JP";
    }

    private static boolean isTraditionalChinese(String lang) {
        return lang.startsWith("zh-hant") || lang.startsWith("zh-tw")
                || lang.startsWith("zh-hk") || lang.startsWith("zh-mo");
    }

    private static void register(PdfRendererBuilder builder, FontFace f, String family,
                                 Set<FSFontUseCase> useCases) {
        if (f.file() != null) {
            builder.useFont(f.file(), family, f.weight(), f.style(), true, useCases);
        } else {
            FSSupplier<InputStream> supplier = () -> new ByteArrayInputStream(f.data());
            builder.useFont(supplier, family, f.weight(), f.style(), true, useCases);
        }
    }

    public int registeredFontCount() {
        return faces.size();
    }
}
