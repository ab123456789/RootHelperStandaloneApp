package com.dadatu.roothelper;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView textStatus;
    private TextView textOutput;
    private Button btnStart;
    private Button btnStop;
    private Button btnPing;
    private Button btnId;
    private Button btnBoot;
    private Button btnEdge;

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {});

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textStatus = findViewById(R.id.textStatus);
        textOutput = findViewById(R.id.textOutput);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        btnPing = findViewById(R.id.btnPing);
        btnId = findViewById(R.id.btnId);
        btnBoot = findViewById(R.id.btnBoot);
        btnEdge = findViewById(R.id.btnEdge);

        btnStart.setOnClickListener(v -> runTask(getString(R.string.status_working), () -> {
            startHelperService();
            Thread.sleep(1200);
            return HttpUtils.get(RootHelperConfig.HOST + "/ping");
        }));

        btnStop.setOnClickListener(v -> runTask(getString(R.string.status_working), () -> {
            stopService(new Intent(this, RootHelperService.class));
            return "Service stop requested";
        }));

        btnPing.setOnClickListener(v -> runTask(getString(R.string.status_working),
            () -> HttpUtils.get(RootHelperConfig.HOST + "/ping")));

        btnId.setOnClickListener(v -> runTask(getString(R.string.status_working),
            () -> HttpUtils.postJson(
                RootHelperConfig.HOST + "/exec",
                RootHelperConfig.TOKEN,
                "{\"argv\":[\"id\"]}"
            )));

        btnBoot.setOnClickListener(v -> toggleBoot());

        btnEdge.setOnClickListener(v -> runTask(getString(R.string.status_working),
            () -> HttpUtils.postJson(
                RootHelperConfig.HOST + "/edge/open",
                RootHelperConfig.TOKEN,
                "{}"
            )));

        updateBootButton();
        ensureNotificationPermission();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private interface Task {
        String run() throws Exception;
    }

    private void runTask(String status, Task task) {
        setBusy(true);
        textStatus.setText(status);
        executor.execute(() -> {
            String result;
            try {
                result = task.run();
            } catch (Throwable t) {
                String msg = t.getMessage();
                if (msg == null || msg.isEmpty()) msg = t.toString();
                Throwable cause = t.getCause();
                while ((msg == null || msg.contains("unexpected end of stream")) && cause != null) {
                    if (cause.getMessage() != null && !cause.getMessage().isEmpty()) {
                        msg = cause.getMessage();
                    } else {
                        msg = cause.toString();
                    }
                    cause = cause.getCause();
                }
                result = "ERROR: " + msg;
            }
            String finalResult = result;
            runOnUiThread(() -> {
                textOutput.setText(finalResult);
                textStatus.setText(R.string.status_ready);
                setBusy(false);
            });
        });
    }

    private void setBusy(boolean busy) {
        btnStart.setEnabled(!busy);
        btnStop.setEnabled(!busy);
        btnPing.setEnabled(!busy);
        btnId.setEnabled(!busy);
        btnBoot.setEnabled(!busy);
        btnEdge.setEnabled(!busy);
    }

    private void startHelperService() {
        Intent intent = new Intent(this, RootHelperService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void toggleBoot() {
        SharedPreferences prefs = getSharedPreferences(RootHelperConfig.PREFS, Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(RootHelperConfig.PREF_BOOT, false);
        prefs.edit().putBoolean(RootHelperConfig.PREF_BOOT, !enabled).apply();

        PackageManager pm = getPackageManager();
        int newState = !enabled
            ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        pm.setComponentEnabledSetting(
            new ComponentName(this, BootReceiver.class),
            newState,
            PackageManager.DONT_KILL_APP
        );
        updateBootButton();
        textOutput.setText(!enabled ? "Boot start enabled" : "Boot start disabled");
    }

    private void updateBootButton() {
        SharedPreferences prefs = getSharedPreferences(RootHelperConfig.PREFS, Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(RootHelperConfig.PREF_BOOT, false);
        btnBoot.setText(enabled ? "已开启开机自启（点击关闭）" : "切换开机自启");
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }
}
