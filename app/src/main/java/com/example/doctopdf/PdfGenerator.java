package com.example.doctopdf;

import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

public class PdfGenerator {

    public interface ProgressListener {
        void onProgress(int percent);
    }

    private static final int PAGE_WIDTH = 595;
    private static final int PAGE_HEIGHT = 842;
    private static final int MARGIN = 40;
    private static final int FONT_SIZE = 11;
    private static final int LINE_SPACING = 4;

    public static void generate(List<String> paragraphs, FileOutputStream out, ProgressListener listener) throws IOException {
        PdfDocument document = new PdfDocument();

        TextPaint textPaint = new TextPaint();
        textPaint.setColor(0xFF000000);
        textPaint.setTextSize(FONT_SIZE * 1.5f);
        textPaint.setAntiAlias(true);

        int contentWidth = PAGE_WIDTH - MARGIN * 2;
        int yPos = MARGIN;
        int pageNumber = 1;

        PdfDocument.Page currentPage = null;
        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(
                PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create();
        currentPage = document.startPage(pageInfo);

        int total = paragraphs.size();
        int processed = 0;

        for (int i = 0; i < paragraphs.size(); i++) {
            String text = paragraphs.get(i);
            if (text == null) text = "";

            StaticLayout staticLayout = new StaticLayout(
                    text,
                    textPaint,
                    contentWidth,
                    Layout.Alignment.ALIGN_NORMAL,
                    1.0f + LINE_SPACING / (float) FONT_SIZE,
                    0f,
                    false
            );

            int height = staticLayout.getHeight();

            if (yPos + height > PAGE_HEIGHT - MARGIN) {
                document.finishPage(currentPage);
                pageNumber++;
                pageInfo = new PdfDocument.PageInfo.Builder(
                        PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create();
                currentPage = document.startPage(pageInfo);
                yPos = MARGIN;
            }

            currentPage.getCanvas().save();
            currentPage.getCanvas().translate(MARGIN, yPos);
            staticLayout.draw(currentPage.getCanvas());
            currentPage.getCanvas().restore();

            yPos += height + FONT_SIZE * 1.5f;
            processed++;

            if (listener != null && processed % 20 == 0) {
                listener.onProgress(Math.min(99, processed * 100 / Math.max(1, total)));
            }
        }

        if (currentPage != null) {
            document.finishPage(currentPage);
        }

        document.writeTo(out);
        document.close();

        if (listener != null) {
            listener.onProgress(100);
        }
    }
}
