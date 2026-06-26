package com.example.doctopdf;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class DocxParser {

    public interface ProgressListener {
        void onProgress(int percent);
    }

    public static List<String> parseParagraphs(InputStream inputStream, ProgressListener listener) throws Exception {
        List<String> paragraphs = new ArrayList<>();

        byte[] zipBytes = readFully(inputStream);
        java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(zipBytes);
        ZipInputStream zis = new ZipInputStream(bais);
        ZipEntry entry;
        byte[] documentXml = null;

        while ((entry = zis.getNextEntry()) != null) {
            if ("word/document.xml".equals(entry.getName())) {
                documentXml = readFully(zis);
                break;
            }
            zis.closeEntry();
        }
        zis.close();
        bais.close();

        if (documentXml == null) {
            throw new IOException("未找到 document.xml，文件可能不是有效的 docx 格式");
        }

        java.io.ByteArrayInputStream xmlBais = new java.io.ByteArrayInputStream(documentXml);
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(xmlBais, "UTF-8");

        int eventType = parser.getEventType();
        StringBuilder currentText = new StringBuilder();
        boolean inP = false;
        int count = 0;

        while (eventType != XmlPullParser.END_DOCUMENT) {
            String tagName = parser.getName();

            if (eventType == XmlPullParser.START_TAG) {
                if ("p".equals(tagName)) {
                    inP = true;
                    currentText.setLength(0);
                }
            } else if (eventType == XmlPullParser.TEXT) {
                if (inP) {
                    currentText.append(parser.getText());
                }
            } else if (eventType == XmlPullParser.END_TAG) {
                if ("p".equals(tagName)) {
                    paragraphs.add(currentText.toString());
                    currentText.setLength(0);
                    inP = false;
                    count++;
                    if (listener != null && count % 30 == 0) {
                        listener.onProgress(Math.min(95, count * 5));
                    }
                }
            }

            eventType = parser.next();
        }

        if (listener != null) {
            listener.onProgress(100);
        }
        xmlBais.close();
        return paragraphs;
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
