package com.example.doctopdf;

import android.text.SpannableStringBuilder;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
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
        public List<ImageBlock> images = new ArrayList<>();

        public boolean hasContent() {
            return (text != null && text.length() > 0) || !images.isEmpty();
        }
    }

    /**
     * Parse a .docx stream into a list of paragraphs with formatting and images.
     */
    public static List<ParagraphData> parse(InputStream inputStream, ProgressListener listener) throws Exception {
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

            switch (eventType) {
                case XmlPullParser.START_TAG: {
                    if ("p".equals(tag)) {
                        currentPara = new ParagraphData();
                        ssb = new SpannableStringBuilder();
                        currentPara.text = ssb;
                        runBold = false;
                        runItalic = false;
                        runSz = 22;
                        runColor = 0xFF000000;
                        runFont = null;
                        inR = false; inRPr = false; inT = false; inDrawing = false;
                    } else if ("r".equals(tag)) {
                        inR = true;
                        runBold = false;
                        runItalic = false;
                        runSz = 22;
                        runColor = 0xFF000000;
                        runFont = null;
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
                            try {
                                runColor = 0xFF000000 | Integer.parseInt(val, 16);
                            } catch (Exception ignored) {}
                        }
                    } else if ("rFonts".equals(tag) && inRPr) {
                        String ascii = parser.getAttributeValue(null, "ascii");
                        String hAnsi = parser.getAttributeValue(null, "hAnsi");
                        String eastAsia = parser.getAttributeValue(null, "eastAsia");
                        runFont = (ascii != null) ? ascii : (hAnsi != null ? hAnsi : eastAsia);
                    } else if ("jc".equals(tag)) {
                        String val = parser.getAttributeValue(null, "val");
                        if (val != null) {
                            if ("center".equals(val)) {
                                if (currentPara != null) currentPara.alignment = 1;
                            } else if ("right".equals(val) || "end".equals(val)) {
                                if (currentPara != null) currentPara.alignment = 2;
                            } else {
                                if (currentPara != null) currentPara.alignment = 0;
                            }
                        }
                    } else if ("t".equals(tag)) {
                        inT = true;
                    } else if ("drawing".equals(tag)) {
                        inDrawing = true;
                        drawingRId = null;
                        imageW = 0;
                        imageH = 0;
                    } else if ("blip".equals(tag) && inDrawing) {
                        // Check r:embed attribute (may have different namespace prefix)
                        String embed = parser.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "embed");
                        if (embed == null) {
                            // try with any namespace
                            for (int i = 0; i < parser.getAttributeCount(); i++) {
                                String an = parser.getAttributeName(i);
                                if ("embed".equals(an) || an.endsWith(":embed")) {
                                    embed = parser.getAttributeValue(i);
                                    break;
                                }
                            }
                        }
                        if (embed == null) {
                            // try without namespace
                            embed = parser.getAttributeValue(null, "embed");
                        }
                        drawingRId = embed;
                    } else if ("extent".equals(tag) && inDrawing) {
                        String cx = parser.getAttributeValue(null, "cx");
                        String cy = parser.getAttributeValue(null, "cy");
                        if (cx != null) {
                            try { imageW = Long.parseLong(cx) / 12700f; } catch (Exception ignored) {}
                        }
                        if (cy != null) {
                            try { imageH = Long.parseLong(cy) / 12700f; } catch (Exception ignored) {}
                        }
                    } else if ("tab".equals(tag) && inR) {
                        if (ssb != null) ssb.append('\t');
                    } else if ("br".equals(tag) && inR) {
                        if (ssb != null) ssb.append('\n');
                    }
                    break;
                }

                case XmlPullParser.TEXT: {
                    if (inT && inR && currentPara != null && ssb != null) {
                        String text = parser.getText();
                        int start = ssb.length();
                        ssb.append(text);

                        // Apply spans for this run
                        if (runBold || runItalic) {
                            int style = 0;
                            if (runBold && runItalic) style = android.graphics.Typeface.BOLD_ITALIC;
                            else if (runBold) style = android.graphics.Typeface.BOLD;
                            else if (runItalic) style = android.graphics.Typeface.ITALIC;
                            ssb.setSpan(new StyleSpan(style), start, ssb.length(),
                                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        // Font size: runSz is in half-points, convert to pixels
                        float pt = runSz / 2f;
                        int px = Math.round(pt * 1.333f); // 1pt ≈ 1.333px at default canvas density
                        if (px > 0 && Math.abs(pt - 11f) > 0.5f) {
                            ssb.setSpan(new AbsoluteSizeSpan(px, false), start, ssb.length(),
                                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        // Color
                        if (runColor != 0xFF000000) {
                            ssb.setSpan(new ForegroundColorSpan(runColor), start, ssb.length(),
                                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        // Font family
                        if (runFont != null && !runFont.isEmpty()) {
                            String mapped = mapFontFamily(runFont);
                            ssb.setSpan(new android.text.style.TypefaceSpan(mapped), start, ssb.length(),
                                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                    }
                    break;
                }

                case XmlPullParser.END_TAG: {
                    if ("p".equals(tag)) {
                        // Finish image reference (if any)
                        if (drawingRId != null && currentPara != null && mediaFiles.containsKey(relsMap.get(drawingRId))) {
                            String path = relsMap.get(drawingRId);
                            if (path != null && mediaFiles.containsKey(path)) {
                                ImageBlock ib = new ImageBlock(mediaFiles.get(path), imageW, imageH);
                                currentPara.images.add(ib);
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
                        currentPara = null;
                        ssb = null;
                    } else if ("r".equals(tag)) {
                        // At end of run, check for completed drawing
                        if (drawingRId != null && currentPara != null) {
                            String path = relsMap.get(drawingRId);
                            if (path != null && mediaFiles.containsKey(path)) {
                                ImageBlock ib = new ImageBlock(mediaFiles.get(path), imageW, imageH);
                                currentPara.images.add(ib);
                            }
                            drawingRId = null;
                        }
                        inR = false;
                        inT = false;
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

        if (listener != null) {
            listener.onProgress(100);
        }
        return result;
    }

    /** Map common MS Office font names to Android system fonts. */
    private static String mapFontFamily(String fontName) {
        if (fontName == null) return "sans-serif";
        String lower = fontName.toLowerCase();
        if (lower.contains("times") || lower.contains("serif")) return "serif";
        if (lower.contains("courier") || lower.contains("mono")) return "monospace";
        if (lower.contains("arial") || lower.contains("helvetica") || lower.contains("calibri")
                || lower.contains("verdana") || lower.contains("tahoma") || lower.contains("segoe")) {
            return "sans-serif";
        }
        return "sans-serif";
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