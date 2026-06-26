package com.example.doctopdf;

import android.text.SpannableStringBuilder;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class DocxParser {

    public interface ProgressListener {
        void onProgress(int percent);
    }

    // ---- Document-level page margins (default 1 inch = 72pt) ----
    public static int marginLeft   = 72;
    public static int marginRight  = 72;
    public static int marginTop    = 72;
    public static int marginBottom = 72;

    /** An embedded image from the docx media folder. */
    public static class ImageBlock {
        public byte[] data;
        public float widthPt;
        public float heightPt;

        public ImageBlock(byte[] data, float widthPt, float heightPt) {
            this.data = data;
            this.widthPt = widthPt;
            this.heightPt = heightPt;
        }
    }

    /** A parsed paragraph with formatted (Spanned) text and optional images. */
    public static class ParagraphData {
        public CharSequence text = "";
        public int alignment = 0; // 0=left, 1=center, 2=right

        // Paragraph spacing (in points, from w:spacing)
        public int spacingBefore = 0;    // default 0pt
        public int spacingAfter  = 60;   // ~3pt common default
        // Line spacing (from w:spacing w:line / w:lineRule)
        public float lineSpacingMult = 1.15f; // Word default
        public float lineSpacingAdd  = 0f;

        public List<ImageBlock> images = new ArrayList<>();

        public boolean hasContent() {
            return (text != null && text.length() > 0) || !images.isEmpty();
        }
    }

    /**
     * Parse a .docx stream into a list of paragraphs with formatting and images.
     */
    public static List<ParagraphData> parse(InputStream inputStream, ProgressListener listener) throws Exception {
        // Reset page margins to defaults
        marginLeft = 72; marginRight = 72; marginTop = 72; marginBottom = 72;

        byte[] zipBytes = readFully(inputStream);
        ByteArrayInputStream bais = new ByteArrayInputStream(zipBytes);
        ZipInputStream zis = new ZipInputStream(bais);
        ZipEntry entry;

        byte[] documentXml = null;
        byte[] relsXml = null;
        Map<String, byte[]> mediaFiles = new HashMap<>();

        while ((entry = zis.getNextEntry()) != null) {
            String name = entry.getName();
            if ("word/document.xml".equals(name)) {
                documentXml = readFully(zis);
            } else if ("word/_rels/document.xml.rels".equals(name)) {
                relsXml = readFully(zis);
            } else if (name.startsWith("word/media/")) {
                mediaFiles.put(name, readFully(zis));
            }
            zis.closeEntry();
        }
        zis.close();
        bais.close();

        if (documentXml == null) {
            throw new IOException("无效的 docx 文件：未找到 document.xml");
        }

        // ---- Parse relationships (rId → media path) ----
        Map<String, String> relsMap = new HashMap<>();
        if (relsXml != null) {
            try {
                XmlPullParser rp = Xml.newPullParser();
                rp.setInput(new ByteArrayInputStream(relsXml), "UTF-8");
                int ev;
                while ((ev = rp.next()) != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG && "Relationship".equals(rp.getName())) {
                        String id = rp.getAttributeValue(null, "Id");
                        String target = rp.getAttributeValue(null, "Target");
                        String type = rp.getAttributeValue(null, "Type");
                        if (id != null && target != null && type != null && type.contains("image")) {
                            String path = target.startsWith("media/") ? "word/" + target : "word/" + target;
                            relsMap.put(id, path);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // ---- Parse document body into paragraphs ----
        List<ParagraphData> result = new ArrayList<>();
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new ByteArrayInputStream(documentXml), "UTF-8");

        int eventType = parser.getEventType();
        ParagraphData currentPara = null;
        SpannableStringBuilder ssb = null;
        boolean inR = false;
        boolean inRPr = false;
        boolean inPPr = false;
        boolean inT = false;
        boolean inDrawing = false;

        // current run properties
        boolean runBold = false;
        boolean runItalic = false;
        int runSz = 22; // half-points → 11pt default
        int runColor = 0xFF000000;
        String runFont = null;
        // current drawing / image
        String drawingRId = null;
        float imageW = 0;
        float imageH = 0;

        int pCount = 0;

        while (eventType != XmlPullParser.END_DOCUMENT) {
            String tag = parser.getName();
            if (tag == null) { eventType = parser.next(); continue; }

            switch (eventType) {
                case XmlPullParser.START_TAG: {
                    if ("p".equals(tag)) {
                        currentPara = new ParagraphData();
                        ssb = new SpannableStringBuilder();
                        currentPara.text = ssb;
                        runBold = false; runItalic = false;
                        runSz = 22; runColor = 0xFF000000; runFont = null;
                        inR = false; inRPr = false; inPPr = false;
                        inT = false; inDrawing = false;
                    } else if ("pPr".equals(tag)) {
                        inPPr = true;
                    } else if ("spacing".equals(tag) && inPPr && currentPara != null) {
                        String before = parser.getAttributeValue(null, "before");
                        String after  = parser.getAttributeValue(null, "after");
                        String line   = parser.getAttributeValue(null, "line");
                        String rule   = parser.getAttributeValue(null, "lineRule");
                        if (before != null) {
                            try { currentPara.spacingBefore = Integer.parseInt(before) / 20; } catch (Exception ignored) {}
                        }
                        if (after != null) {
                            try { currentPara.spacingAfter = Integer.parseInt(after) / 20; } catch (Exception e) { currentPara.spacingAfter = 0; }
                        }
                        if (line != null && rule != null && "auto".equals(rule)) {
                            try {
                                float mult = Integer.parseInt(line) / 240f;
                                if (mult > 0.1f) currentPara.lineSpacingMult = mult;
                            } catch (Exception ignored) {}
                        }
                    } else if ("jc".equals(tag) && currentPara != null) {
                        String val = parser.getAttributeValue(null, "val");
                        if (val != null) {
                            if ("center".equals(val))          currentPara.alignment = 1;
                            else if ("right".equals(val) || "end".equals(val)) currentPara.alignment = 2;
                            else                               currentPara.alignment = 0;
                        }
                    } else if ("r".equals(tag)) {
                        inR = true;
                        runBold = false; runItalic = false;
                        runSz = 22; runColor = 0xFF000000; runFont = null;
                    } else if ("rPr".equals(tag)) {
                        inRPr = true;
                    } else if ("b".equals(tag) && inRPr) {
                        runBold = true;
                    } else if ("i".equals(tag) && inRPr) {
                        runItalic = true;
                    } else if ("sz".equals(tag) && inRPr) {
                        String val = parser.getAttributeValue(null, "val");
                        if (val != null) {
                            try { runSz = Integer.parseInt(val); } catch (Exception ignored) {}
                        }
                    } else if ("color".equals(tag) && inRPr) {
                        String val = parser.getAttributeValue(null, "val");
                        if (val != null && val.length() == 6) {
                            try { runColor = 0xFF000000 | Integer.parseInt(val, 16); } catch (Exception ignored) {}
                        }
                    } else if ("rFonts".equals(tag) && inRPr) {
                        String ascii    = parser.getAttributeValue(null, "ascii");
                        String hAnsi    = parser.getAttributeValue(null, "hAnsi");
                        String eastAsia = parser.getAttributeValue(null, "eastAsia");
                        runFont = (ascii != null) ? ascii : (hAnsi != null ? hAnsi : eastAsia);
                    } else if ("t".equals(tag)) {
                        inT = true;
                    } else if ("drawing".equals(tag)) {
                        inDrawing = true;
                        drawingRId = null; imageW = 0; imageH = 0;
                    } else if ("blip".equals(tag) && inDrawing) {
                        String embed = parser.getAttributeValue(
                                "http://schemas.openxmlformats.org/officeDocument/2006/relationships", "embed");
                        if (embed == null) {
                            for (int i = 0; i < parser.getAttributeCount(); i++) {
                                String an = parser.getAttributeName(i);
                                if ("embed".equals(an) || an.endsWith(":embed")) {
                                    embed = parser.getAttributeValue(i);
                                    break;
                                }
                            }
                        }
                        if (embed == null) embed = parser.getAttributeValue(null, "embed");
                        drawingRId = embed;
                    } else if ("extent".equals(tag) && inDrawing) {
                        String cx = parser.getAttributeValue(null, "cx");
                        String cy = parser.getAttributeValue(null, "cy");
                        if (cx != null) { try { imageW = Long.parseLong(cx) / 12700f; } catch (Exception ignored) {} }
                        if (cy != null) { try { imageH = Long.parseLong(cy) / 12700f; } catch (Exception ignored) {} }
                    } else if ("tab".equals(tag) && inR) {
                        if (ssb != null) ssb.append('\t');
                    } else if ("br".equals(tag) && inR) {
                        if (ssb != null) ssb.append('\n');
                    } else if ("sectPr".equals(tag)) {
                        // Parse page margins inside section properties
                        // Note: we don't set a flag here; we handle pgMar directly below
                    } else if ("pgMar".equals(tag)) {
                        String top    = parser.getAttributeValue(null, "top");
                        String bottom = parser.getAttributeValue(null, "bottom");
                        String left   = parser.getAttributeValue(null, "left");
                        String right  = parser.getAttributeValue(null, "right");
                        if (top != null)    { try { marginTop    = Integer.parseInt(top) / 20; } catch (Exception ignored) {} }
                        if (bottom != null) { try { marginBottom = Integer.parseInt(bottom) / 20; } catch (Exception ignored) {} }
                        if (left != null)   { try { marginLeft   = Integer.parseInt(left) / 20; } catch (Exception ignored) {} }
                        if (right != null)  { try { marginRight  = Integer.parseInt(right) / 20; } catch (Exception ignored) {} }
                    }
                    break;
                }

                case XmlPullParser.TEXT: {
                    if (inT && inR && currentPara != null && ssb != null) {
                        String text = parser.getText();
                        int start = ssb.length();
                        ssb.append(text);

                        // Bold / Italic → StyleSpan (keeps StaticLayout happy for fallback)
                        if (runBold || runItalic) {
                            int style = 0;
                            if (runBold && runItalic) style = android.graphics.Typeface.BOLD_ITALIC;
                            else if (runBold)         style = android.graphics.Typeface.BOLD;
                            else if (runItalic)       style = android.graphics.Typeface.ITALIC;
                            ssb.setSpan(new android.text.style.StyleSpan(style),
                                    start, ssb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        // Font size: runSz is half-points → px for canvas
                        float pt = runSz / 2f;
                        int px = Math.round(pt * 1.333f);
                        if (px > 0 && Math.abs(pt - 11f) > 0.5f) {
                            ssb.setSpan(new AbsoluteSizeSpan(px, false),
                                    start, ssb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        // Color
                        if (runColor != 0xFF000000) {
                            ssb.setSpan(new ForegroundColorSpan(runColor),
                                    start, ssb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        // Font family → custom FontSpan backed by bundled Liberation fonts
                        if (runFont != null && !runFont.isEmpty()) {
                            ssb.setSpan(new FontSpan(runFont, runBold, runItalic),
                                    start, ssb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                    }
                    break;
                }

                case XmlPullParser.END_TAG: {
                    if ("p".equals(tag)) {
                        // Finish pending image reference
                        if (drawingRId != null && currentPara != null) {
                            String path = relsMap.get(drawingRId);
                            if (path != null && mediaFiles.containsKey(path)) {
                                currentPara.images.add(new ImageBlock(mediaFiles.get(path), imageW, imageH));
                            }
                            drawingRId = null;
                        }
                        if (currentPara != null && currentPara.hasContent()) {
                            result.add(currentPara);
                            pCount++;
                            if (listener != null && pCount % 30 == 0) {
                                listener.onProgress(Math.min(95, pCount * 3));
                            }
                        }
                        currentPara = null; ssb = null;
                    } else if ("pPr".equals(tag)) {
                        inPPr = false;
                    } else if ("r".equals(tag)) {
                        if (drawingRId != null && currentPara != null) {
                            String path = relsMap.get(drawingRId);
                            if (path != null && mediaFiles.containsKey(path)) {
                                currentPara.images.add(new ImageBlock(mediaFiles.get(path), imageW, imageH));
                            }
                            drawingRId = null;
                        }
                        inR = false; inT = false;
                    } else if ("rPr".equals(tag)) {
                        inRPr = false;
                    } else if ("t".equals(tag)) {
                        inT = false;
                    } else if ("drawing".equals(tag)) {
                        inDrawing = false;
                    }
                    break;
                }
            }

            eventType = parser.next();
        }

        if (listener != null) listener.onProgress(100);
        return result;
    }

    private static byte[] readFully(InputStream is) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int len;
        while ((len = is.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        return baos.toByteArray();
    }
}