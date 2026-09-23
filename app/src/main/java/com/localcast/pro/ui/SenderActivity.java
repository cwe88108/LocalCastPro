package com.localcast.pro.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.localcast.pro.LocalCastApplication;
import com.localcast.pro.R;
import com.localcast.pro.core.CastManager;
import com.localcast.pro.utils.Logger;
import com.localcast.pro.utils.FrameRateUtils;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SenderActivity extends AppCompatActivity {

    private static final String TAG = "SenderActivity";
    private static final int REQUEST_AUDIO_PERMISSION = 1001;

    private TextView tvReceiverName, tvLatency, tvBitrate, tvFps;
    private MaterialButton btnPause, btnStop, btnSwitch, btnFullscreen;
    private View layoutStats;

    private CastManager castManager;
    private boolean isPaused;
    private boolean senderFullscreen;
    private final ExecutorService controlExecutor = Executors.newSingleThreadExecutor();

    private float dX, dY, initX, initY;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameRateUtils.applyCastingRefreshRate(this);
        setContentView(R.layout.activity_sender);

        // 检查音频权限
        if (!checkAudioPermission()) {
            Logger.w(TAG, "Audio permission not granted, casting without audio");
        }

        tvReceiverName = findViewById(R.id.tv_receiver_name);
        tvLatency = findViewById(R.id.tv_latency);
        tvBitrate = findViewById(R.id.tv_bitrate);
        tvFps = findViewById(R.id.tv_fps);
        btnPause = findViewById(R.id.btn_pause);
        btnStop = findViewById(R.id.btn_stop);
        btnSwitch = findViewById(R.id.btn_switch);
        btnFullscreen = findViewById(R.id.btn_fullscreen);
        layoutStats = findViewById(R.id.layout_stats);

        castManager = ((LocalCastApplication) getApplication()).getCastManager();
        if (castManager == null || castManager.getCurrentMode() != CastManager.CastMode.SENDING) {
            Toast.makeText(this, "投屏未启动", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // 显示"正在连接…"状态
        tvReceiverName.setText("正在连接…");

        // 暂停/恢复
        btnPause.setOnClickListener(v -> {
            isPaused = !isPaused;
            if (isPaused) {
                castManager.pauseCasting();
                btnPause.setText("恢复");
            } else {
                castManager.resumeCasting();
                btnPause.setText("暂停");
            }
        });

        btnStop.setOnClickListener(v -> {
            try {
                castManager.stopCurrentMode();
            } catch (Exception e) {
                // 确保即使stopCurrentMode异常也能关闭
            }
            finish();
        });

        btnSwitch.setOnClickListener(v -> {
            castManager.stopCurrentMode();
            startActivity(new Intent(this, ReceiverActivity.class));
            finish();
        });

        // 全屏：发送端切横屏捕获 + 接收端 COVER 铺满电视
        btnFullscreen.setOnClickListener(v -> toggleFullscreen());

        // 悬浮统计拖拽
        layoutStats.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initX = v.getX(); initY = v.getY();
                        dX = v.getX() - e.getRawX(); dY = v.getY() - e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        v.animate().x(e.getRawX() + dX).y(e.getRawY() + dY).setDuration(0).start();
                        return true;
                    case MotionEvent.ACTION_UP:
                        v.animate().x(initX).y(initY).setDuration(300).start();
                        return true;
                }
                return false;
            }
        });

        // 回调
        castManager.setOnCastStateListener(new CastManager.OnCastStateListener() {
            @Override public void onModeChanged(CastManager.CastMode m) {
                if (m == CastManager.CastMode.IDLE) finish();
            }

            @Override
            public void onConnected(com.localcast.pro.core.DeviceInfo d) {
                runOnUiThread(() -> {
                    tvReceiverName.setText("投屏至 " + d.getDeviceName());
                    Toast.makeText(SenderActivity.this,
                            "投屏已连接", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onDisconnected(String reason) {
                runOnUiThread(() -> {
                    Toast.makeText(SenderActivity.this,
                            "投屏断开: " + reason, Toast.LENGTH_LONG).show();
                    finish();
                });
            }

            @Override
            public void onSenderStats(int fps, int kbps, long ms) {
                runOnUiThread(() -> {
                    // The transport currently has no round-trip acknowledgement;
                    // presenting the placeholder 0ms as real latency is misleading.
                    tvLatency.setText(ms > 0 ? ms + "ms" : "--");
                    tvBitrate.setText(kbps < 100
                            ? kbps + " Kbps"
                            : String.format(Locale.getDefault(), "%.1f Mbps", kbps / 1000.0));
                    tvFps.setText(fps + " fps");
                    if (ms > 0) {
                        int c = ms <= 50 ? R.color.success : (ms <= 100 ? R.color.warning : R.color.md_error);
                        tvLatency.setTextColor(getColor(c));
                    }
                });
            }

            @Override public void onReceiverStats(int f, int b, long l) {}
            @Override public void onVideoSizeChanged(int w, int h) {}
            @Override public void onDisplaySizeChanged(int w, int h) {}
            @Override public void onDisplayModeChanged(int mode) {}

            @Override
            public void onError(String e) {
                runOnUiThread(() -> {
                    Toast.makeText(SenderActivity.this,
                            "投屏失败: " + e, Toast.LENGTH_LONG).show();
                    // Connection recovery reports progress through this callback.
                    // Keep the controller visible; terminal failures arrive via
                    // onDisconnected and close the activity there.
                    if (e != null && e.contains("自动重连")) {
                        tvReceiverName.setText("正在重连…");
                    }
                });
            }
        });

        // The TCP handshake can finish while this Activity is being created.
        // State listeners are not replayed, so render the current connection
        // snapshot rather than leaving a healthy session labelled “connecting”.
        com.localcast.pro.core.DeviceInfo connectedDevice =
                castManager.getConnectionManager().getRemoteDevice();
        if (castManager.getCurrentMode() == CastManager.CastMode.SENDING
                && connectedDevice != null) {
            tvReceiverName.setText("投屏至 " + connectedDevice.getDeviceName());
        }
    }

    @Override
    @SuppressLint("MissingSuperCall") // Back intentionally backgrounds an active foreground cast.
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    private void toggleFullscreen() {
        senderFullscreen = !senderFullscreen;
        if (senderFullscreen) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            btnFullscreen.setText("退出全屏");
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            btnFullscreen.setText("全屏");
        }
        castManager.setSenderFullscreenCapture(senderFullscreen);
        sendDisplayControlCommand(senderFullscreen ? (byte) 1 : (byte) 2);
    }

    private void sendDisplayControlCommand(byte commandType) {
        controlExecutor.execute(() -> {
            com.localcast.pro.core.StreamTransport transport =
                    castManager.getConnectionManager().getStreamTransport();
            boolean ready = transport != null && transport.isRunning();
            boolean success = ready && transport.sendFrame(
                    com.localcast.pro.core.StreamTransport.TYPE_CONTROL,
                    System.nanoTime(),
                    com.localcast.pro.core.StreamTransport.FLAG_NONE,
                    new byte[]{commandType},
                    1);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                String message = !ready ? "传输层未就绪"
                        : success ? (commandType == 1 ? "电视全屏铺满" : "已退出全屏")
                        : "发送失败，请检查连接";
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            });
        });
    }

    @Override
    protected void onDestroy() {
        controlExecutor.shutdownNow();
        super.onDestroy();
    }

    private boolean checkAudioPermission() {
        int permissionStatus = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO);
        Logger.i(TAG, "Checking audio permission, current status: " + 
                 (permissionStatus == PackageManager.PERMISSION_GRANTED ? "GRANTED" : "DENIED"));
        
        if (permissionStatus != PackageManager.PERMISSION_GRANTED) {
            Logger.i(TAG, "Requesting audio permission from user");
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_AUDIO_PERMISSION);
            return false;
        }
        Logger.i(TAG, "Audio permission already granted");
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_AUDIO_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Logger.i(TAG, "✅ Audio permission GRANTED by user");
                Toast.makeText(this, "已授予录音权限,音频功能已启用", Toast.LENGTH_SHORT).show();
            } else {
                Logger.e(TAG, "❌ Audio permission DENIED by user");
                Toast.makeText(this, "未授予录音权限,音频功能不可用\n请在设置中手动开启", Toast.LENGTH_LONG).show();
            }
        }
    }
}
