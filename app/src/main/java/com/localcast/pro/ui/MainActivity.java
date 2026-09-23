package com.localcast.pro.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.localcast.pro.LocalCastApplication;
import com.localcast.pro.R;
import com.localcast.pro.core.CastManager;
import com.localcast.pro.service.CastService;
import com.localcast.pro.core.ConnectionManager;
import com.localcast.pro.core.DeviceInfo;
import com.localcast.pro.core.DeviceManager;
import com.localcast.pro.utils.NetworkUtils;
import com.localcast.pro.utils.FrameRateUtils;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private TextView tvLocalIp, tvWifiInfo;
    private LinearProgressIndicator progressIndicator;
    private TextView tvModeStatus;
    private MaterialButton btnStopCasting;
    private View layoutModeIndicator;

    private MaterialCardView cardSender, cardReceiver;
    private RecyclerView rvDevices;
    private View layoutEmpty;
    private TextView tvEmptyText, tvGuideTip;
    private CircularProgressIndicator progressSearch;
    private MaterialButton btnManualConnect;

    private CastManager castManager;
    private DeviceManager deviceManager;
    private DeviceAdapter adapter;
    private final List<DeviceInfo> devices = new ArrayList<>();

    // AndroidX 现代权限请求 API
    private ActivityResultLauncher<Intent> screenCaptureLauncher;
    private String pendingTargetIp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 注册屏幕录制权限启动器（必须在 onCreate 中注册）
        screenCaptureLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        startCastServiceAfterProjectionGranted(
                                result.getResultCode(), result.getData());
                    } else {
                        toast("需要屏幕录制权限才能投屏");
                    }
                });

        initViews();
        initCastManager();
        initDeviceManager();
        requestNeededPermissions();
    }

    private void initViews() {
        tvLocalIp = findViewById(R.id.tv_local_ip);
        tvWifiInfo = findViewById(R.id.tv_wifi_info);
        layoutModeIndicator = findViewById(R.id.layout_mode_indicator);
        progressIndicator = findViewById(R.id.progress_indicator);
        tvModeStatus = findViewById(R.id.tv_mode_status);
        btnStopCasting = findViewById(R.id.btn_stop_casting);
        cardSender = findViewById(R.id.card_sender);
        cardReceiver = findViewById(R.id.card_receiver);
        rvDevices = findViewById(R.id.rv_devices);
        layoutEmpty = findViewById(R.id.layout_empty);
        tvEmptyText = findViewById(R.id.tv_empty_text);
        tvGuideTip = findViewById(R.id.tv_guide_tip);
        progressSearch = findViewById(R.id.progress_search);
        btnManualConnect = findViewById(R.id.btn_manual_connect);

        rvDevices.setLayoutManager(new LinearLayoutManager(this));
        adapter = new DeviceAdapter();
        rvDevices.setAdapter(adapter);

        cardSender.setOnClickListener(v -> {
            if (devices.isEmpty()) {
                toast("未发现设备\n请先在另一台设备上打开App并点击【接收投屏】");
            } else {
                toast("请在下方的设备列表中点击【连接】");
            }
        });

        cardReceiver.setOnClickListener(v ->
                startActivity(new Intent(this, ReceiverActivity.class)));

        btnStopCasting.setOnClickListener(v -> castManager.stopCurrentMode());
        btnManualConnect.setOnClickListener(v -> showManualConnectDialog());
        findViewById(R.id.fab_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
    }

    private void initCastManager() {
        LocalCastApplication app = (LocalCastApplication) getApplication();
        castManager = app.getCastManager();
        if (castManager == null) {
            castManager = new CastManager();
            castManager.init(this);
            app.setCastManager(castManager);
        }
        castManager.setOnCastStateListener(new CastManager.OnCastStateListener() {
            @Override public void onModeChanged(CastManager.CastMode m) { refreshStatusBar(); }
            @Override public void onConnected(DeviceInfo d) {
                runOnUiThread(() -> { toast("已连接 " + d.getDeviceName()); refreshStatusBar(); });
            }
            @Override public void onDisconnected(String r) {
                runOnUiThread(() -> { toast("连接断开: " + r); refreshStatusBar(); });
            }
            @Override public void onSenderStats(int f, int b, long l) {}
            @Override public void onReceiverStats(int f, int b, long l) {}
            @Override public void onVideoSizeChanged(int w, int h) {}
            @Override public void onDisplaySizeChanged(int w, int h) {}
            @Override public void onDisplayModeChanged(int mode) {}
            @Override public void onError(String e) {
                runOnUiThread(() -> toast(e));
            }
        });
    }

    private void initDeviceManager() {
        deviceManager = new DeviceManager();
        deviceManager.init(this);
        deviceManager.setLocalDeviceInfo(castManager.getLocalDevice());
        deviceManager.addListener(new DeviceManager.OnDeviceDiscoveryListener() {
            @Override public void onDeviceFound(DeviceInfo d) {
                runOnUiThread(() -> { upsertDevice(d); refreshEmptyView(); });
            }
            @Override public void onDeviceLost(DeviceInfo d) {
                runOnUiThread(() -> { devices.remove(d); adapter.notifyDataSetChanged(); refreshEmptyView(); });
            }
            @Override public void onDeviceUpdated(DeviceInfo d) {
                runOnUiThread(() -> { upsertDevice(d); refreshEmptyView(); });
            }
            @Override public void onDiscoveryError(String e) { runOnUiThread(() -> toast(e)); }
        });
        // Discovery must not advertise this device until the receiver TCP
        // listener is actually active. ReceiverActivity owns that advertisement.
        deviceManager.startDiscovery();

        tvLocalIp.setText(NetworkUtils.getLocalIpAddress());
        String ssid = NetworkUtils.getWifiSSID(this);
        tvWifiInfo.setText(ssid != null ? ssid : "未连接WiFi");
    }

    private void requestNeededPermissions() {
        List<String> perms = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED)
            perms.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED)
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!perms.isEmpty()) {
            // 如果需要音频权限，先显示解释对话框
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("需要录音权限")
                    .setMessage("为了在投屏时传输音频，应用需要录音权限。\n\n" +
                            "音频模式说明：\n" +
                            "• 默认使用麦克风模式\n" +
                            "• 会捕获周围环境的声音\n" +
                            "• 建议在安静环境中使用\n\n" +
                            "如不需要音频，可在设置中关闭。")
                    .setPositiveButton("授予权限", (dialog, which) -> {
                        ActivityCompat.requestPermissions(this, perms.toArray(new String[0]), 100);
                    })
                    .setNegativeButton("稍后再说", null)
                    .show();
            } else {
                // 只需要通知权限
                ActivityCompat.requestPermissions(this, perms.toArray(new String[0]), 100);
            }
        }
    }

    private void refreshStatusBar() {
        CastManager.CastMode mode = castManager.getCurrentMode();
        layoutModeIndicator.setVisibility(mode == CastManager.CastMode.IDLE ? View.GONE : View.VISIBLE);
        btnStopCasting.setVisibility(mode == CastManager.CastMode.IDLE ? View.GONE : View.VISIBLE);
        if (mode != CastManager.CastMode.IDLE) {
            String name = castManager.getConnectionManager().getRemoteDevice() != null ?
                    castManager.getConnectionManager().getRemoteDevice().getDeviceName() : "设备";
            tvModeStatus.setText(mode == CastManager.CastMode.SENDING ? "投屏中 → " + name : "接收投屏中");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (castManager != null) refreshStatusBar();
    }

    private void upsertDevice(DeviceInfo d) {
        int idx = devices.indexOf(d);
        if (idx >= 0) { devices.set(idx, d); adapter.notifyItemChanged(idx); }
        else { devices.add(d); adapter.notifyItemInserted(devices.size() - 1); }
    }

    private void refreshEmptyView() {
        boolean empty = devices.isEmpty();
        layoutEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        rvDevices.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    // ==================== 连接 ====================

    private void connectToDevice(DeviceInfo device) {
        pendingTargetIp = device.getIpAddress();
        // Request the display mode before projection consent so MediaProjection
        // captures a high-refresh source when the device permits it.
        FrameRateUtils.applyCastingRefreshRate(this);
        // Android 14+：必须先完成 MediaProjection 用户授权，再启动 mediaProjection 前台服务
        launchScreenCaptureIntent();
    }

    private void startCastServiceAfterProjectionGranted(int resultCode, Intent resultData) {
        if (isFinishing() || isDestroyed()) {
            return;
        }

        CastService.setProjectionReadyListener(new CastService.OnProjectionReadyListener() {
            @Override
            public void onProjectionReady(MediaProjection projection) {
                runOnUiThread(() -> {
                    CastService.setProjectionReadyListener(null);
                    if (isFinishing() || isDestroyed()) {
                        projection.stop();
                        stopCastService();
                        return;
                    }
                    castManager.startAsSender(pendingTargetIp, projection);
                    startActivity(new Intent(MainActivity.this, SenderActivity.class));
                });
            }

            @Override
            public void onProjectionFailed(String error) {
                runOnUiThread(() -> {
                    CastService.setProjectionReadyListener(null);
                    toast(error);
                    stopCastService();
                });
            }
        });

        Intent serviceIntent = new Intent(this, CastService.class);
        serviceIntent.setAction(CastService.ACTION_START_CAST);
        serviceIntent.putExtra(CastService.EXTRA_RESULT_CODE, resultCode);
        serviceIntent.putExtra(CastService.EXTRA_RESULT_DATA, resultData);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            CastService.setProjectionReadyListener(null);
            toast("启动投屏服务失败: " + e.getMessage());
        }
    }

    private void launchScreenCaptureIntent() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        MediaProjectionManager pm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (pm == null) {
            toast("设备不支持屏幕录制");
            return;
        }
        Intent intent;
        try {
            intent = pm.createScreenCaptureIntent();
        } catch (Exception e) {
            toast("创建屏幕录制请求失败: " + e.getMessage());
            return;
        }
        if (intent == null) {
            toast("无法创建屏幕录制请求");
            return;
        }
        screenCaptureLauncher.launch(intent);
    }

    private void showManualConnectDialog() {
        TextInputEditText et = new TextInputEditText(this);
        et.setHint("输入电视/盒子的IP地址");
        et.setSingleLine();
        et.setPadding(48, 32, 48, 32);
        new MaterialAlertDialogBuilder(this)
                .setTitle("手动连接")
                .setMessage("请先在目标设备上打开App并点击【接收投屏】")
                .setView(et)
                .setPositiveButton("连接", (d, w) -> {
                    String ip = et.getText().toString().trim();
                    if (NetworkUtils.isValidIp(ip)) {
                        DeviceInfo di = new DeviceInfo();
                        di.setIpAddress(ip); di.setDeviceName("手动-" + ip);
                        di.setTcpPort(ConnectionManager.TCP_PORT);
                        connectToDevice(di);
                    } else { toast("无效的IP地址"); }
                })
                .setNegativeButton("取消", null).show();
    }

    private void stopCastService() {
        Intent serviceIntent = new Intent(this, CastService.class);
        serviceIntent.setAction(CastService.ACTION_STOP_CAST);
        startService(serviceIntent);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    // ==================== 设备适配器 ====================

    private class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.VH> {
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_device, parent, false));
        }
        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            DeviceInfo d = devices.get(pos);
            h.name.setText(d.getDeviceName());
            h.type.setText(d.getDeviceTypeName());
            h.ip.setText(d.getIpAddress());
            boolean isTv = d.getDeviceType() == DeviceInfo.TYPE_TV || d.getDeviceType() == DeviceInfo.TYPE_BOX;
            h.icon.setImageResource(isTv ? R.drawable.ic_tv : R.drawable.ic_phone);
            h.btnConnect.setOnClickListener(v -> connectToDevice(d));
        }
        @Override public int getItemCount() { return devices.size(); }
        class VH extends RecyclerView.ViewHolder {
            ImageView icon; TextView name, type, ip; MaterialButton btnConnect;
            VH(View v) {
                super(v);
                icon = v.findViewById(R.id.iv_device_icon);
                name = v.findViewById(R.id.tv_device_name);
                type = v.findViewById(R.id.tv_device_type);
                ip = v.findViewById(R.id.tv_device_ip);
                btnConnect = v.findViewById(R.id.btn_connect);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100) {
            boolean audioGranted = true;
            for (int i = 0; i < permissions.length; i++) {
                if (Manifest.permission.RECORD_AUDIO.equals(permissions[i])) {
                    audioGranted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
                    break;
                }
            }
            if (!audioGranted) {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("权限被拒绝")
                    .setMessage("未授予录音权限，投屏将不包含音频。\n\n" +
                            "说明：\n" +
                            "• 本应用使用麦克风模式捕获音频\n" +
                            "• 会录制环境声音，而非系统内部音频\n" +
                            "• 如需启用，请前往设置手动授权\n\n" +
                            "如不需要音频，可在设置中关闭。")
                    .setPositiveButton("去设置", (dialog, which) -> {
                        Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(android.net.Uri.fromParts("package", getPackageName(), null));
                        startActivity(intent);
                    })
                    .setNegativeButton("取消", null)
                    .show();
            }
        }
    }

    @Override protected void onDestroy() {
        CastService.setProjectionReadyListener(null);
        super.onDestroy();
        if (deviceManager != null) deviceManager.release();
        if (castManager != null && castManager.getCurrentMode() == CastManager.CastMode.IDLE)
            castManager.release();
    }
}
