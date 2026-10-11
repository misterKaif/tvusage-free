package com.tvusage.free.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.tvusage.free.R;
import com.tvusage.free.service.UsageMonitorService;
import com.tvusage.free.util.AppPreferences;
import com.tvusage.free.util.NetworkUtils;
import com.tvusage.free.util.QrCodeHelper;

/**
 * MainActivity acts as the on-TV dashboard and control panel.
 *
 * Security & Ergonomics:
 * - When opened, it requires the adult Parent PIN if not already authenticated.
 * - Allows parents to directly with their TV remote:
 *   1) Toggle Parent Mode: Bypass all restrictions until the TV sleeps.
 *   2) Reset Today's Usage: Immediately zero out today's watch timer.
 *   3) View QR Code / URL to manage from phone without TV remote typing.
 */
public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_CODE_AUTH = 1001;

    private AppPreferences prefs;
    private boolean isAuthenticated = false;

    private TextView tvModeStatus;
    private TextView tvRemaining;
    private TextView tvProtectedSummary;
    private Button btnToggleParentMode;
    private Button btnReset;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = new AppPreferences(this);

        // Ensure background monitor service is active
        Intent svc = new Intent(this, UsageMonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }

        // Require adult master PIN before revealing TV controls to children
        if (!isAuthenticated && !prefs.isUnlockedUntilSleep()) {
            Intent lockIntent = new Intent(this, LockActivity.class);
            lockIntent.putExtra(LockActivity.EXTRA_UNLOCK_FOR_SETTINGS, true);
            startActivityForResult(lockIntent, REQUEST_CODE_AUTH);
        } else {
            initUi();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_AUTH) {
            if (resultCode == RESULT_OK) {
                isAuthenticated = true;
                initUi();
            } else {
                // Backed out or wrong PIN -> return to home screen
                finish();
            }
        }
    }

    private void initUi() {
        setContentView(R.layout.activity_main);

        ImageView ivQr = findViewById(R.id.ivQrCode);
        TextView tvQrUrl = findViewById(R.id.tvQrUrl);
        tvModeStatus = findViewById(R.id.tvModeStatus);
        tvRemaining = findViewById(R.id.tvRemainingTime);
        tvProtectedSummary = findViewById(R.id.tvProtectedSummary);
        btnToggleParentMode = findViewById(R.id.btnToggleParentMode);
        btnReset = findViewById(R.id.btnResetStats);

        // Local Web Admin QR URL
        String ip = NetworkUtils.getLocalIpAddress();
        String url = "http://" + ip + ":8080";
        tvQrUrl.setText(url);

        Bitmap qrBitmap = QrCodeHelper.generateQrBitmap(url, 400, 400);
        if (qrBitmap != null) {
            ivQr.setImageBitmap(qrBitmap);
        }

        updateUiState();

        // Button: Toggle Parent Mode (Без ограничений до сна ТВ)
        btnToggleParentMode.setOnClickListener(v -> {
            boolean current = prefs.isUnlockedUntilSleep();
            boolean newState = !current;
            prefs.setUnlockedUntilSleep(newState);

            if (newState) {
                Toast.makeText(MainActivity.this, "👑 Режим родителя включен: без ограничений до сна ТВ!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(MainActivity.this, "🛡️ Детский контроль снова активен!", Toast.LENGTH_SHORT).show();
            }
            updateUiState();
        });

        // Button: Reset today's screen time
        btnReset.setOnClickListener(v -> {
            prefs.resetTodayUsage();
            Toast.makeText(MainActivity.this, "🔄 Счетчик времени на сегодня обнулен!", Toast.LENGTH_SHORT).show();
            updateUiState();
        });

        // Focus first action button for easy D-Pad remote control
        btnToggleParentMode.requestFocus();
    }

    private void updateUiState() {
        if (prefs == null || tvModeStatus == null) return;

        boolean isParent = prefs.isUnlockedUntilSleep();
        if (isParent) {
            tvModeStatus.setText("👑 Режим родителя: Ограничения сняты до выключения ТВ");
            tvModeStatus.setTextColor(0xFF00E676);
            btnToggleParentMode.setText("🛡️ Включить детский контроль");
        } else {
            tvModeStatus.setText("🛡️ Детский режим: Контроль приложений активен");
            tvModeStatus.setTextColor(0xFF64B5F6);
            btnToggleParentMode.setText("👑 Снять ограничения до сна ТВ");
        }

        int limit = prefs.getTotalEffectiveLimitMinutes();
        int usedMin = prefs.getTodayUsageSeconds() / 60;
        int rem = prefs.getRemainingMinutes();
        tvRemaining.setText("Использовано сегодня: " + usedMin + " мин (Осталось: " + rem + " из " + limit + " мин)");

        int countProtected = prefs.getRestrictedPackages().size();
        tvProtectedSummary.setText("Контролируется приложений: " + countProtected);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isAuthenticated) {
            updateUiState();
        }
    }
}
