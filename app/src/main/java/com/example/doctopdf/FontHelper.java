package com.example.doctopdf;

import android.content.Context;
import android.graphics.Typeface;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads Liberation open-source fonts from assets and maps MS Office font names
 * to metrically equivalent Liberation font faces.
 *
 * Liberation fonts are GPL-licensed and metrically identical to:
 *   Liberation Serif  → Times New Roman
 *   Liberation Sans   → Arial / Calibri / Helvetica
 *   Liberation Mono   → Courier New
 */
public class FontHelper {

    private static Context sContext;
    private static final ConcurrentHashMap<String, Typeface> sCache = new ConcurrentHashMap<>();

    /** Must be called once at app start (e.g. MainActivity.onCreate). */
    public static void init(Context context) {
        sContext = context.getApplicationContext();
    }

    /**
     * Return a Typeface for the given MS Office font name and style flags,
     * backed by the closest Liberation font bundled in assets.
     */
    public static Typeface getTypeface(String fontFamily, boolean bold, boolean italic) {
        String base = mapToLiberation(fontFamily);
        String style;
        if (bold && italic)      style = "BoldItalic";
        else if (bold)           style = "Bold";
        else if (italic)         style = "Italic";
        else                     style = "Regular";
        String key = base + "-" + style;

        Typeface cached = sCache.get(key);
        if (cached != null) return cached;

        if (sContext != null) {
            try {
                Typeface tf = Typeface.createFromAsset(sContext.getAssets(),
                        "fonts/" + key + ".ttf");
                sCache.put(key, tf);
                return tf;
            } catch (Exception ignored) {
                // fall through to system default
            }
        }

        // Fallback: let Android synthesize from the regular face
        Typeface fallback = Typeface.create(mapToSystem(base),
                bold && italic ? Typeface.BOLD_ITALIC :
                bold ? Typeface.BOLD :
                italic ? Typeface.ITALIC : Typeface.NORMAL);
        sCache.put(key, fallback);
        return fallback;
    }

    /** Map a document font name → Liberation base name. */
    private static String mapToLiberation(String fontFamily) {
        if (fontFamily == null) return "LiberationSans";
        String lc = fontFamily.toLowerCase();
        if (lc.contains("times") || lc.contains("serif") && !lc.contains("sans"))
            return "LiberationSerif";
        if (lc.contains("courier") || lc.contains("mono"))
            return "LiberationMono";
        // Everything else → Liberation Sans (Arial-like)
        return "LiberationSans";
    }

    /** System font family as last resort. */
    private static String mapToSystem(String liberation) {
        if ("LiberationSerif".equals(liberation)) return "serif";
        if ("LiberationMono".equals(liberation))  return "monospace";
        return "sans-serif";
    }
}