package com.example.doctopdf;

import android.content.Context;
import android.net.Uri;
import android.os.AsyncTask;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

public class DocToPdfConverter extends AsyncTask<Uri, Object, File> {

    public interface Callback {
        void onProgress(int progress, String status);
        void onSuccess(File pdfFile);
        void onFailure(String error);
    }

    private final WeakReference<Context> contextRef;
    private final WeakReference<Callback> callbackRef;
    private String errorMessage = "";

    public DocToPdfConverter(Context context, Callback callback) {
        this.contextRef = new WeakReference<>(context);
        this.callbackRef = new WeakReference<>(callback);
    }

    @Override
    protected void onPreExecute() {
        Callback cb = callbackRef.get();
        if (cb != null) {
            cb.onProgress(0, "准备中...");
        }
    }

    @Override
    protected File doInBackground(Uri... uris) {
        Context context = contextRef.get();
        if (context == null || uris.length == 0) {
            errorMessage = "上下文无效";
            return null;
        }

        try {
            Uri uri = uris[0];
            String fileName = getFileName(context, uri);
            reportProgress(5, "读取文件: " + fileName);

            if (fileName.toLowerCase().endsWith(".docx")) {
                return convertDocx(context, uri, fileName);
            } else if (fileName.toLowerCase().endsWith(".doc")) {
                errorMessage = ".doc 格式暂不支持，请先另存为 .docx";
                return null;
            } else {
                errorMessage = "不支持的文件格式";
                return null;
            }
        } catch (Exception e) {
            errorMessage = e.getMessage();
            return null;
        }
    }

    private File convertDocx(Context context, Uri uri, String fileName) throws Exception {
        reportProgress(10, "解析文档结构...");

        List<String> paragraphs;
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            paragraphs = DocxParser.parseParagraphs(is, new DocxParser.ProgressListener() {
                @Override
                public void onProgress(int percent) {
                    publishProgress(10 + percent * 40 / 100, "解析文档中... " + percent + "%");
                }
            });
        }

        reportProgress(55, "生成 PDF 中...");

        String pdfName = fileName.replaceAll("\\.(?i)(docx|doc)$", ".pdf");
        File outFile = new File(context.getCacheDir(), pdfName);

        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            PdfGenerator.generate(paragraphs, fos, new PdfGenerator.ProgressListener() {
                @Override
                public void onProgress(int percent) {
                    reportProgress(55 + percent * 40 / 100, "生成 PDF 中... " + percent + "%");
                }
            });
        }

        reportProgress(100, "完成!");
        return outFile;
    }

    @Override
    protected void onProgressUpdate(Object... values) {
        Callback cb = callbackRef.get();
        if (cb == null) return;
        if (values.length >= 2) {
            int progress = 0;
            if (values[0] instanceof Integer) progress = (Integer) values[0];
            else if (values[0] != null) {
                try { progress = Integer.parseInt(values[0].toString()); } catch (Exception ignored) {}
            }
            String status = values[1] != null ? values[1].toString() : "";
            cb.onProgress(progress, status);
        } else if (values.length == 1) {
            int progress = 0;
            if (values[0] instanceof Integer) progress = (Integer) values[0];
            cb.onProgress(progress, "");
        }
    }

    private void reportProgress(int progress, String status) {
        publishProgress(Integer.valueOf(progress), status);
    }

    @Override
    protected void onPostExecute(File result) {
        Callback cb = callbackRef.get();
        if (cb == null) return;
        if (result != null && result.exists()) {
            cb.onSuccess(result);
        } else {
            cb.onFailure(errorMessage.isEmpty() ? "转换失败" : errorMessage);
        }
    }

    private static String getFileName(Context context, Uri uri) {
        String name = "document.docx";
        try (android.database.Cursor c = context.getContentResolver()
                .query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    name = c.getString(idx);
                }
            }
        } catch (Exception e) {
            name = "document.docx";
        }
        return name;
    }
}
