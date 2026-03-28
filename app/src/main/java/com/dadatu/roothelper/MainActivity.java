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
    private TextView textCdpStatus;
    private TextView textOutput;
    private Button btnStart;
    private Button btnStop;
    private Button btnPing;
    private Button btnId;
    private Button btnBoot;
    private Button btnEdge;
    private Button btnSelinuxPermissive;

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {});

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textStatus = findViewById(R.id.textStatus);
        textCdpStatus = findViewById(R.id.textCdpStatus);
        textOutput = findViewById(R.id.textOutput);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        btnPing = findViewById(R.id.btnPing);
        btnId = findViewById(R.id.btnId);
        btnBoot = findViewById(R.id.btnBoot);
        btnEdge = findViewById(R.id.btnEdge);
        btnSelinuxPermissive = findViewById(R.id.btnSelinuxPermissive);

        applyRootConnectedState();
        applyCdpDisconnectedState();

        btnStart.setOnClickListener(v -> runTask(() -> {
            startHelperService();
            Thread.sleep(1200);
            return "Root 桥接正常\n\n" + HttpUtils.get(RootHelperConfig.HOST + "/ping");
        }, () -> {
            applyRootConnectedState();
            applyCdpDisconnectedState();
        }, this::applyRootDisconnectedState));

        btnStop.setOnClickListener(v -> runTask(() -> {
            stopService(new Intent(this, RootHelperService.class));
            return "桥接模式已断开";
        }, () -> {
            applyRootDisconnectedState();
            applyCdpDisconnectedState();
        }, this::applyRootDisconnectedState));

        btnPing.setOnClickListener(v -> runTask(
            () -> HttpUtils.get(RootHelperConfig.HOST + "/ping"),
            this::applyRootConnectedState,
            this::applyRootDisconnectedState));

        btnId.setOnClickListener(v -> runTask(
            () -> "Root 权限正常\n\n" + HttpUtils.postJson(
                RootHelperConfig.HOST + "/exec",
                "{\"argv\":[\"id\"]}"
            ),
            this::applyRootConnectedState,
            this::applyRootDisconnectedState));

        btnSelinuxPermissive.setOnClickListener(v -> runTask(
            () -> "SELinux 已切换为宽松模式\n\n" + HttpUtils.postJson(
                RootHelperConfig.HOST + "/exec",
                "{\"argv\":[\"sh\",\"-c\",\"setenforce 0\"]}"
            ),
            this::applyRootConnectedState,
            this::applyRootDisconnectedState));

        btnBoot.setOnClickListener(v -> toggleBoot());

        btnEdge.setOnClickListener(v -> runTask(
            () -> "CDP 连接正常\n\n" + HttpUtils.postJson(
                RootHelperConfig.HOST + "/edge/open",
                "{}"
            ),
            () -> {
                applyRootConnectedState();
                applyCdpConnectedState();
            },
            () -> {
                applyRootConnectedState();
                applyCdpDisconnectedState();
            }));

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

    private void runTask(Task task, Runnable onSuccess, Runnable onError) {
        setBusy(true);
        executor.execute(() -> {
            String result;
            boolean ok = false;
            try {
                result = task.run();
                ok = true;
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
            boolean finalOk = ok;
            runOnUiThread(() -> {
                textOutput.setText(finalResult);
                if (finalOk) {
                    if (onSuccess != null) onSuccess.run();
                } else {
                    if (onError != null) onError.run();
                }
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
        btnSelinuxPermissive.setEnabled(!busy);
    }

    private void applyRootConnectedState() {
        textStatus.setText("Root 桥接正常");
        textStatus.setTextColor(getColor(R.color.rabbit_success));
        textStatus.setBackgroundResource(R.drawable.bg_rabbit_status);
    }

    private void applyRootDisconnectedState() {
        textStatus.setText("Root 桥接已掉线");
        textStatus.setTextColor(getColor(R.color.rabbit_danger));
        textStatus.setBackgroundResource(R.drawable.bg_rabbit_status_danger);
    }

    private void applyCdpConnectedState() {
        textCdpStatus.setText("CDP 连接正常");
        textCdpStatus.setTextColor(getColor(R.color.rabbit_success));
        textCdpStatus.setBackgroundResource(R.drawable.bg_rabbit_status);
    }

    private void applyCdpDisconnectedState() {
        textCdpStatus.setText("CDP 未连接");
        textCdpStatus.setTextColor(getColor(R.color.rabbit_text_muted));
        textCdpStatus.setBackgroundResource(R.drawable.bg_rabbit_status_idle);
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
        textOutput.setText(!enabled ? "开机自启已开启" : "开机自启已关闭");
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
