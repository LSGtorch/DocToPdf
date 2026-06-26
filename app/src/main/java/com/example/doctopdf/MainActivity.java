package com.example.doctopdf;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {

    private static final String GITHUB_URL = "https://github.com/LSGtorch/DocToPdf";

    private ProgressBar progressBar;
    private TextView tvStatus;
    private TextView tvFileName;
    private Button btnShare;
    private Button btnOpen;
    private Button btnRetry;
    private View layoutResult;
    private View layoutIdle;

    private File currentPdfFile;
    private DocToPdfConverter currentTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize font system (loads bundled Liberation fonts from assets)
        FontHelper.init(this);

        progressBar = findViewById(R.id.progressBar);
        tvStatus = findViewById(R.id.tvStatus);
        tvFileName = findViewById(R.id.tvFileName);
        btnShare = findViewById(R.id.btnShare);
        btnOpen = findViewById(R.id.btnOpen);
        btnRetry = findViewById(R.id.btnRetry);
        layoutResult = findViewById(R.id.layoutResult);
        layoutIdle = findViewById(R.id.layoutIdle);

        btnShare.setOnClickListener(v -> sharePdf());
        btnOpen.setOnClickListener(v -> openPdf());
        btnRetry.setOnClickListener(v -> resetAndHandleIntent(getIntent()));

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        resetAndHandleIntent(intent);
    }

    private void resetAndHandleIntent(Intent intent) {
        if (currentTask != null) {
            currentTask.cancel(true);
        }
        currentPdfFile = null;
        layoutResult.setVisibility(View.GONE);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        String type = intent.getType();

        Uri fileUri = null;
        String fileName = null;

        if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            fileUri = intent.getData();
            fileName = getFileName(fileUri);
        } else if (Intent.ACTION_SEND.equals(action) && type != null) {
            fileUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (fileUri != null) {
                fileName = getFileName(fileUri);
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action) && type != null) {
            ArrayList<Uri> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (uris != null && !uris.isEmpty()) {
                fileUri = uris.get(0);
                fileName = getFileName(fileUri) + (uris.size() > 1 ? " (共" + uris.size() + "个文件，仅处理第一个)" : "");
            }
        }

        if (fileUri != null) {
            startConversion(fileUri, fileName);
        } else {
            showIdle();
        }
    }

    private void startConversion(Uri uri, String fileName) {
        progressBar.setVisibility(View.VISIBLE);
        layoutResult.setVisibility(View.GONE);
        tvFileName.setText(fileName);
        progressBar.setProgress(0);

        currentTask = new DocToPdfConverter(this, new DocToPdfConverter.Callback() {
            @Override
            public void onProgress(int progress, String status) {
                progressBar.setProgress(progress);
                tvStatus.setText(status);
            }

            @Override
            public void onSuccess(File pdfFile) {
                currentPdfFile = pdfFile;
                progressBar.setProgress(100);
                tvStatus.setText("转换完成");
                showResult();
            }

            @Override
            public void onFailure(String error) {
                tvStatus.setText("转换失败: " + error);
                progressBar.setVisibility(View.GONE);
                btnRetry.setVisibility(View.VISIBLE);
            }
        });
        currentTask.execute(uri);
    }

    private void showIdle() {
        layoutIdle.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.GONE);
        tvStatus.setText(R.string.how_to_use);
    }

    private void showResult() {
        progressBar.setVisibility(View.GONE);
        layoutResult.setVisibility(View.VISIBLE);
        btnRetry.setVisibility(View.GONE);
    }

    private void sharePdf() {
        if (currentPdfFile == null || !currentPdfFile.exists()) {
            Toast.makeText(this, "PDF 文件不存在", Toast.LENGTH_SHORT).show();
            return;
        }

        Uri pdfUri = FileProvider.getUriForFile(this,
                getPackageName() + ".fileprovider", currentPdfFile);

        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("application/pdf");
        share.putExtra(Intent.EXTRA_STREAM, pdfUri);
        share.putExtra(Intent.EXTRA_SUBJECT, currentPdfFile.getName());
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, "分享 PDF"));
    }

    private void openPdf() {
        if (currentPdfFile == null || !currentPdfFile.exists()) {
            Toast.makeText(this, "PDF 文件不存在", Toast.LENGTH_SHORT).show();
            return;
        }

        Uri pdfUri = FileProvider.getUriForFile(this,
                getPackageName() + ".fileprovider", currentPdfFile);

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(pdfUri, "application/pdf");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(intent, "打开 PDF"));
        } catch (Exception e) {
            Toast.makeText(this, "没有找到可打开 PDF 的应用", Toast.LENGTH_SHORT).show();
        }
    }

    private String getFileName(Uri uri) {
        String name = "document.docx";
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception e) {
            name = "document.docx";
        }
        return name;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (currentTask != null) {
            currentTask.cancel(true);
        }
    }

    public void onSourceClick(View view) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL));
        startActivity(intent);
    }
}
