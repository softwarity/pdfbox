package com.softwarity.pdfbox.pdf;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Element;

/**
 * Removes transparency from an SVG for PDF/A-1, which forbids it (ISO 19005-1 §6.4), by flattening
 * it over a white page: a colour painted at opacity {@code a} becomes {@code 255 - a × (255 - c)}
 * per channel, the colour it would show on white paper.
 *
 * <p>{@code opacity} (multiplied down the tree), {@code fill-opacity}, {@code stroke-opacity} and
 * {@code stop-opacity} — as attributes or in {@code style} — are folded into solid {@code fill},
 * {@code stroke} and {@code stop-color} values, then removed. Deliberately simple: what cannot be
 * folded (a gradient fill under a semi-transparent group, a named colour outside the common set,
 * {@code currentColor}, rules in a {@code <style>} element) simply loses its opacity and is drawn
 * opaque. Content drawn over something other than white is approximated as if over white.
 */
final class SvgTransparencyFlattener {

    private static final Pattern STYLE_DECL = Pattern.compile("\\s*([a-zA-Z-]+)\\s*:\\s*([^;]+)\\s*;?");
    private static final Pattern RGB = Pattern.compile(
            "rgba?\\(\\s*([\\d.]+%?)\\s*[, ]\\s*([\\d.]+%?)\\s*[, ]\\s*([\\d.]+%?)\\s*(?:[,/]\\s*([\\d.]+%?)\\s*)?\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_RULE_OPACITY = Pattern.compile(
            "(?i)(?<![a-z-])(fill-|stroke-|stop-)?opacity\\s*:\\s*[^;}]+;?");

    /** CSS colour keywords commonly found in charts; others are left as they are (drawn opaque). */
    private static final Map<String, Integer> NAMED = Map.ofEntries(
            Map.entry("black", 0x000000), Map.entry("white", 0xFFFFFF), Map.entry("red", 0xFF0000),
            Map.entry("green", 0x008000), Map.entry("lime", 0x00FF00), Map.entry("blue", 0x0000FF),
            Map.entry("yellow", 0xFFFF00), Map.entry("cyan", 0x00FFFF), Map.entry("aqua", 0x00FFFF),
            Map.entry("magenta", 0xFF00FF), Map.entry("fuchsia", 0xFF00FF), Map.entry("gray", 0x808080),
            Map.entry("grey", 0x808080), Map.entry("silver", 0xC0C0C0), Map.entry("maroon", 0x800000),
            Map.entry("olive", 0x808000), Map.entry("purple", 0x800080), Map.entry("teal", 0x008080),
            Map.entry("navy", 0x000080), Map.entry("orange", 0xFFA500), Map.entry("brown", 0xA52A2A),
            Map.entry("pink", 0xFFC0CB), Map.entry("gold", 0xFFD700), Map.entry("darkgray", 0xA9A9A9),
            Map.entry("darkgrey", 0xA9A9A9), Map.entry("lightgray", 0xD3D3D3), Map.entry("lightgrey", 0xD3D3D3),
            Map.entry("darkblue", 0x00008B), Map.entry("darkred", 0x8B0000), Map.entry("darkgreen", 0x006400),
            Map.entry("lightblue", 0xADD8E6), Map.entry("skyblue", 0x87CEEB), Map.entry("steelblue", 0x4682B4),
            Map.entry("orangered", 0xFF4500), Map.entry("violet", 0xEE82EE), Map.entry("indigo", 0x4B0082));

    private SvgTransparencyFlattener() {
    }

    /** Flattens the subtree rooted at {@code svgRoot} in place. */
    static void flatten(Element svgRoot) {
        walk(svgRoot, 1.0, 1.0, 1.0, "black", "none");
    }

    /**
     * @param group  product of the ancestors' {@code opacity}
     * @param fillA  inherited {@code fill-opacity}
     * @param strokeA inherited {@code stroke-opacity}
     * @param fill   inherited fill paint (as written)
     * @param stroke inherited stroke paint (as written)
     */
    private static void walk(Element el, double group, double fillA, double strokeA, String fill, String stroke) {
        String name = localName(el.tagName());
        if (name.equals("style")) {
            for (var node : el.dataNodes()) {
                node.setWholeData(STYLE_RULE_OPACITY.matcher(node.getWholeData()).replaceAll(""));
            }
            return;
        }

        double own = take(el, "opacity", 1.0);
        double g = group * own;
        double fa = take(el, "fill-opacity", fillA);
        double sa = take(el, "stroke-opacity", strokeA);
        String f = value(el, "fill", fill);
        String s = value(el, "stroke", stroke);

        if (name.equals("stop")) {
            double so = take(el, "stop-opacity", 1.0);
            String color = value(el, "stop-color", "black");
            set(el, "stop-color", blend(color, so));
            return;
        }

        // Shapes and text paint here: fold the effective alpha into their own fill/stroke.
        if (isPainted(name)) {
            double fillAlpha = g * fa;
            double strokeAlpha = g * sa;
            if (fillAlpha < 1.0 || el.hasAttr("fill") || styleHas(el, "fill")) {
                set(el, "fill", blend(f, fillAlpha));
            }
            if (strokeAlpha < 1.0 || el.hasAttr("stroke") || styleHas(el, "stroke")) {
                set(el, "stroke", blend(s, strokeAlpha));
            }
        }
        for (Element child : el.children()) {
            walk(child, g, fa, sa, f, s);
        }
    }

    private static boolean isPainted(String name) {
        return switch (name) {
            case "rect", "circle", "ellipse", "line", "polyline", "polygon", "path", "text", "tspan", "textpath" -> true;
            default -> false;
        };
    }

    /** A plain colour: channels 0–255 and its own alpha (1 unless written {@code rgba(…)}). */
    private record Rgba(int r, int g, int b, double a) {}

    /** The colour {@code paint} shows at {@code alpha} on white, as {@code #rrggbb}; unchanged if not a plain colour. */
    static String blend(String paint, double alpha) {
        String p = paint == null ? "" : paint.trim();
        Rgba c = parse(p);
        if (c == null) {
            return p; // none, url(#gradient), currentColor, unknown keyword: drawn opaque
        }
        double a = Math.max(0, Math.min(1, alpha * c.a()));
        return String.format("#%02x%02x%02x", over(c.r(), a), over(c.g(), a), over(c.b(), a));
    }

    private static int over(int c, double a) {
        return (int) Math.round(255 - a * (255 - c));
    }

    private static Rgba parse(String paint) {
        String p = paint.toLowerCase(Locale.ROOT);
        if (p.startsWith("#")) {
            String hex = p.substring(1);
            if (hex.length() == 3) {
                hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
            }
            if (hex.length() == 6 && hex.matches("[0-9a-f]{6}")) {
                int v = Integer.parseInt(hex, 16);
                return new Rgba((v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF, 1.0);
            }
            return null;
        }
        Matcher m = RGB.matcher(p);
        if (m.matches()) {
            double a = 1.0;
            if (m.group(4) != null) {
                a = m.group(4).endsWith("%")
                        ? Double.parseDouble(m.group(4).replace("%", "")) / 100
                        : Double.parseDouble(m.group(4));
            }
            return new Rgba(channel(m.group(1)), channel(m.group(2)), channel(m.group(3)), a);
        }
        Integer v = NAMED.get(p);
        return v == null ? null : new Rgba((v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF, 1.0);
    }

    private static int channel(String v) {
        double d = v.endsWith("%") ? Double.parseDouble(v.replace("%", "")) * 2.55 : Double.parseDouble(v);
        return (int) Math.round(Math.max(0, Math.min(255, d)));
    }

    // ------------------------------------------------------------- attribute / style access

    /** Reads (and removes) a numeric opacity-like property from the attribute or the style. */
    private static double take(Element el, String property, double inherited) {
        String raw = null;
        if (el.hasAttr(property)) {
            raw = el.attr(property);
            el.removeAttr(property);
        }
        String fromStyle = styleValue(el, property);
        if (fromStyle != null) {
            raw = fromStyle;
            removeStyle(el, property);
        }
        if (raw == null || raw.isBlank()) {
            return inherited;
        }
        try {
            String v = raw.trim();
            double d = v.endsWith("%") ? Double.parseDouble(v.replace("%", "")) / 100 : Double.parseDouble(v);
            return Math.max(0, Math.min(1, d));
        } catch (NumberFormatException e) {
            return inherited;
        }
    }

    private static String value(Element el, String property, String inherited) {
        String fromStyle = styleValue(el, property);
        if (fromStyle != null) {
            return fromStyle.trim();
        }
        return el.hasAttr(property) ? el.attr(property).trim() : inherited;
    }

    /** Sets a presentation value, as an attribute, dropping any copy from the style (which would win). */
    private static void set(Element el, String property, String value) {
        removeStyle(el, property);
        el.attr(property, value);
    }

    private static boolean styleHas(Element el, String property) {
        return styleValue(el, property) != null;
    }

    private static String styleValue(Element el, String property) {
        if (!el.hasAttr("style")) {
            return null;
        }
        Matcher m = STYLE_DECL.matcher(el.attr("style"));
        String found = null;
        while (m.find()) {
            if (m.group(1).equalsIgnoreCase(property)) {
                found = m.group(2);
            }
        }
        return found;
    }

    private static void removeStyle(Element el, String property) {
        if (!el.hasAttr("style")) {
            return;
        }
        Matcher m = STYLE_DECL.matcher(el.attr("style"));
        StringBuilder kept = new StringBuilder();
        while (m.find()) {
            if (!m.group(1).equalsIgnoreCase(property)) {
                kept.append(m.group(1)).append(':').append(m.group(2).trim()).append(';');
            }
        }
        if (kept.isEmpty()) {
            el.removeAttr("style");
        } else {
            el.attr("style", kept.toString());
        }
    }

    private static String localName(String qualified) {
        int colon = qualified.indexOf(':');
        return (colon >= 0 ? qualified.substring(colon + 1) : qualified).toLowerCase(Locale.ROOT);
    }
}
