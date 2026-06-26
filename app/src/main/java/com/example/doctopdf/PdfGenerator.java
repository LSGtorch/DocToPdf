package com.example.doctopdf;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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

    // A4 page in points (1pt = 1/72 inch)
    private static final int PAGE_WIDTH = 595;
    private static final int PAGE_HEIGHT = 842;

    // Default body font size (in points)
    private static final float DEFAULT_FONT_SIZE = 11f;

    public static void generate(List<DocxParser.ParagraphData> paragraphs,
                                FileOutputStream out,
                                ProgressListener listener) throws IOException {
        PdfDocument document = new PdfDocument();

        // Use document-level margins parsed from docx
        int marginLeft   = DocxParser.marginLeft;
        int marginRight  = DocxParser.marginRight;
        int marginTop    = DocxParser.marginTop;
        int marginBottom = DocxParser.marginBottom;
        int contentWidth = PAGE_WIDTH - marginLeft - marginRight;

        // Base TextPaint for rendered text
        TextPaint textPaint = new TextPaint();
        textPaint.setColor(0xFF000000);
        textPaint.setTextSize(DEFAULT_FONT_SIZE * 1.333f); // pt → px conversion
        textPaint.setAntiAlias(true);
        textPaint.setSubpixelText(true);
        textPaint.setLinearText(true);
        // Set default document font (Liberation Sans → Arial/Calibri equivalent)
        textPaint.setTypeface(FontHelper.getTypeface("LiberationSans", false, false));

        int yPos = marginTop;
        int pageNumber = 1;

        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(
                PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create();
        PdfDocument.Page currentPage = document.startPage(pageInfo);

        int total = paragraphs.size();
        int processed = 0;

        for (int i = 0; i < paragraphs.size(); i++) {
            DocxParser.ParagraphData para = paragraphs.get(i);
            if (para == null) continue;

            // --- Apply spacing before paragraph (from docx w:spacing before) ---
            yPos += para.spacingBefore;

            // --- Render images first ---
            for (DocxParser.ImageBlock img : para.images) {
                if (img.data == null || img.data.length == 0) continue;

                Bitmap bitmap = BitmapFactory.decodeByteArray(img.data, 0, img.data.length);
                if (bitmap == null) continue;

                // Calculate scaled dimensions to fit content width
                float scale = 1f;
                if (img.widthPt > 0 && img.widthPt > contentWidth) {
                    scale = contentWidth / img.widthPt;
                } else if (img.widthPt <= 0 && bitmap.getWidth() > contentWidth) {
                    scale = contentWidth / (float) bitmap.getWidth();
                }

                int drawW, drawH;
                if (img.widthPt > 0 && img.heightPt > 0) {
                    drawW = Math.round(img.widthPt * scale);
                    drawH = Math.round(img.heightPt * scale);
                } else {
                    drawW = Math.round(bitmap.getWidth() * scale);
                    drawH = Math.round(bitmap.getHeight() * scale);
                }

                if (drawW < 1) drawW = 1;
                if (drawH < 1) drawH = 1;

                // Check if image fits on current page
                int availableH = PAGE_HEIGHT - marginBottom - yPos;
                if (drawH > availableH) {
                    document.finishPage(currentPage);
                    pageNumber++;
                    pageInfo = new PdfDocument.PageInfo.Builder(
                            PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create();
                    currentPage = document.startPage(pageInfo);
                    yPos = marginTop;
                }

                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, drawW, drawH, true);
                int imgX = marginLeft + (contentWidth - drawW) / 2; // center image
                currentPage.getCanvas().drawBitmap(scaled, imgX, yPos, null);
                yPos += drawH + 8; // 8pt spacing after image
                scaled.recycle();
                bitmap.recycle();
            }

            // --- Render text ---
            if (para.text == null || para.text.length() == 0) continue;

            CharSequence text = para.text;

            // Map alignment
            Layout.Alignment align;
            switch (para.alignment) {
                case 1:  align = Layout.Alignment.ALIGN_CENTER;   break;
                case 2:  align = Layout.Alignment.ALIGN_OPPOSITE; break;
                default: align = Layout.Alignment.ALIGN_NORMAL;   break;
            }

            // Use paragraph-level line spacing from docx
            float lineMult = para.lineSpacingMult > 0 ? para.lineSpacingMult : 1.15f;
            float lineAdd  = para.lineSpacingAdd;

            // Create StaticLayout with the spanned text and actual paragraph spacing
            StaticLayout layout = new StaticLayout(
                    text,
                    0,
                    text.length(),
                    textPaint,
                    contentWidth,
                    align,
                    lineMult,
                    lineAdd,
                    true // include padding
            );

            int layoutHeight = layout.getHeight();
            int neededHeight = layoutHeight + para.spacingAfter;

            // Check if we need a page break
            if (yPos + neededHeight > PAGE_HEIGHT - marginBottom) {
                document.finishPage(currentPage);
                pageNumber++;
                pageInfo = new PdfDocument.PageInfo.Builder(
                        PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create();
                currentPage = document.startPage(pageInfo);
                yPos = marginTop;
            }

            // Draw the layout
            currentPage.getCanvas().save();
            currentPage.getCanvas().translate(marginLeft, yPos);
            layout.draw(currentPage.getCanvas());
            currentPage.getCanvas().restore();

            yPos += layoutHeight + para.spacingAfter;

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