package com.example.doctopdf;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

public class PdfGenerator {

    public interface ProgressListener {
        void onProgress(int percent);
    }

    // A4 page in points
    private static final int PAGE_WIDTH = 595;
    private static final int PAGE_HEIGHT = 842;
    private static final int MARGIN = 40;
    private static final int CONTENT_WIDTH = PAGE_WIDTH - MARGIN * 2; // 515pt

    // Default body font size (in points for the PDF)
    private static final float DEFAULT_FONT_SIZE = 11f;

    public static void generate(List<DocxParser.ParagraphData> paragraphs,
                                FileOutputStream out,
                                ProgressListener listener) throws IOException {
        PdfDocument document = new PdfDocument();

        // Base TextPaint for rendered text (StaticLayout handles spans internally)
        TextPaint textPaint = new TextPaint();
        textPaint.setColor(0xFF000000);
        textPaint.setTextSize(DEFAULT_FONT_SIZE * 1.333f); // ~14.7px at default density
        textPaint.setAntiAlias(true);
        textPaint.setSubpixelText(true);
        textPaint.setLinearText(true);

        int yPos = MARGIN;
        int pageNumber = 1;

        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(
                PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create();
        PdfDocument.Page currentPage = document.startPage(pageInfo);

        int total = paragraphs.size();
        int processed = 0;

        // When we skip to a new page, we need to recalculate content offsets
        // Required for proper vertical alignment on centered images
        final float LINE_SPACING_MULT = 1.3f;
        final float LINE_SPACING_ADD = 0f;

        for (int i = 0; i < paragraphs.size(); i++) {
            DocxParser.ParagraphData para = paragraphs.get(i);
            if (para == null) continue;

            // --- Render images first ---
            for (DocxParser.ImageBlock img : para.images) {
                if (img.data == null || img.data.length == 0) continue;

                Bitmap bitmap = BitmapFactory.decodeByteArray(img.data, 0, img.data.length);
                if (bitmap == null) continue;

                // Calculate scaled dimensions to fit content width
                float scale = 1f;
                if (img.widthPt > 0 && img.widthPt > CONTENT_WIDTH) {
                    scale = CONTENT_WIDTH / img.widthPt;
                } else if (img.widthPt <= 0 && bitmap.getWidth() > CONTENT_WIDTH) {
                    // If EMU size is missing, use bitmap pixel size as reference
                    scale = CONTENT_WIDTH / (float) bitmap.getWidth();
                }

                int drawW, drawH;
                if (img.widthPt > 0 && img.heightPt > 0) {
                    drawW = Math.round(img.widthPt * scale);
                    drawH = Math.round(img.heightPt * scale);
                } else {
                    drawW = Math.round(bitmap.getWidth() * scale);
                    drawH = Math.round(bitmap.getHeight() * scale);
                }

                // Ensure minimum dimensions
                if (drawW < 1) drawW = 1;
                if (drawH < 1) drawH = 1;

                int availableH = PAGE_HEIGHT - MARGIN - yPos;
                if (drawH > availableH) {
                    // Need a new page
                    document.finishPage(currentPage);
                    pageNumber++;
                    pageInfo = new PdfDocument.PageInfo.Builder(
                            PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create();
                    currentPage = document.startPage(pageInfo);
                    yPos = MARGIN;
                }

                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, drawW, drawH, true);
                int imgX = MARGIN + (CONTENT_WIDTH - drawW) / 2; // center image
                currentPage.getCanvas().drawBitmap(scaled, imgX, yPos, null);
                yPos += drawH + 8; // 8pt spacing after image
                scaled.recycle();
                bitmap.recycle();
            }

            // --- Render text ---
            if (para.text == null || para.text.length() == 0) continue;

            CharSequence text = para.text;

            // Map our alignment to Layout.Alignment
            Layout.Alignment align;
            switch (para.alignment) {
                case 1:  align = Layout.Alignment.ALIGN_CENTER;  break;
                case 2:  align = Layout.Alignment.ALIGN_OPPOSITE; break;
                default: align = Layout.Alignment.ALIGN_NORMAL;   break;
            }

            // Create StaticLayout with the spanned text
            StaticLayout layout = new StaticLayout(
                    text,
                    0,
                    text.length(),
                    textPaint,
                    CONTENT_WIDTH,
                    align,
                    LINE_SPACING_MULT,
                    LINE_SPACING_ADD,
                    true // include padding
            );

            int layoutHeight = layout.getHeight();

            // Check if we need a page break
            int neededHeight = layoutHeight + 6; // 6pt spacing after paragraph
            if (yPos + neededHeight > PAGE_HEIGHT - MARGIN) {
                document.finishPage(currentPage);
                pageNumber++;
                pageInfo = new PdfDocument.PageInfo.Builder(
                        PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create();
                currentPage = document.startPage(pageInfo);
                yPos = MARGIN;
            }

            // Draw the layout
            currentPage.getCanvas().save();
            currentPage.getCanvas().translate(MARGIN, yPos);
            layout.draw(currentPage.getCanvas());
            currentPage.getCanvas().restore();

            yPos += layoutHeight + 6;

            processed++;
            if (listener != null && processed % 10 == 0) {
                listener.onProgress(Math.min(99, processed * 100 / Math.max(1, total)));
            }
        }

        // Finish last page
        if (currentPage != null) {
            document.finishPage(currentPage);
        }

        // Write to output stream
        document.writeTo(out);
        document.close();

        if (listener != null) {
            listener.onProgress(100);
        }
    }
}