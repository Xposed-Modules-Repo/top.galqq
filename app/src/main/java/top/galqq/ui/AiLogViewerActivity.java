package top.galqq.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import top.galqq.R;
import top.galqq.utils.AiLogManager;
import top.galqq.utils.HostInfo;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * AI日志查看Activity
 * 使用 ScrollView + TextView 实现自由文本选择复制
 */
public class AiLogViewerActivity extends AppCompatTransferActivity {
    
    private ScrollView logScrollView;
    private TextView logTextView;
    private Button btnExport;
    private Button btnClear;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 应用主题管理器，检测QQ的夜间模式设置
        top.galqq.utils.ThemeManager.applyTheme(this);
        top.galqq.utils.ThemeManager.updateConfiguration(this);
        
        // 设置主题
        setTheme(top.galqq.utils.ThemeManager.getThemeResId(this));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_log_viewer);
        
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.ai_log_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        
        logScrollView = findViewById(R.id.log_scroll_view);
        logTextView = findViewById(R.id.log_text_view);
        btnExport = findViewById(R.id.btn_export);
        btnClear = findViewById(R.id.btn_clear);
        
        btnExport.setOnClickListener(v -> exportLogs());
        btnClear.setOnClickListener(v -> {
            AiLogManager.clearLogs(this);
            Toast.makeText(this, R.string.logs_cleared, Toast.LENGTH_SHORT).show();
            loadLogs();
        });
        
        loadLogs();
    }
    
    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
    
    private void loadLogs() {
        String logs = AiLogManager.getLogs(this);
        logTextView.setText(logs);
        
        // 滚动到底部显示最新日志
        logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
    }
    
    private void exportLogs() {
        try {
            String logs = AiLogManager.getLogs(this);
            
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String filename = "galqq_ai_logs_" + timestamp + ".txt";
            
            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File logFile = new File(downloadsDir, filename);
            
            FileWriter writer = new FileWriter(logFile);
            writer.write(logs);
            writer.close();
            
            Uri fileUri = FileProvider.getUriForFile(this, 
                getApplicationContext().getPackageName() + ".fileprovider", logFile);
            
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "GalQQ AI Logs");
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            
            startActivity(Intent.createChooser(shareIntent, getString(R.string.export_logs)));
            Toast.makeText(this, "日志已保存到: " + logFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
            
        } catch (Exception e) {
            Toast.makeText(this, "导出失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
