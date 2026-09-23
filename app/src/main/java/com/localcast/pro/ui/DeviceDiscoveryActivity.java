package com.localcast.pro.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.card.MaterialCardView;
import com.localcast.pro.LocalCastApplication;
import com.localcast.pro.R;
import com.localcast.pro.core.CastManager;
import com.localcast.pro.utils.NetworkUtils;

public class DeviceDiscoveryActivity extends AppCompatActivity {

    private TextView tvLocalDevice, tvLocalIp;
    private MaterialCardView cardSenderMode, cardReceiverMode;
    private View layoutWaiting;
    private CastManager castManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_discovery);

        tvLocalDevice = findViewById(R.id.tv_local_device);
        tvLocalIp = findViewById(R.id.tv_local_ip);
        cardSenderMode = findViewById(R.id.btn_sender_mode);
        cardReceiverMode = findViewById(R.id.btn_receiver_mode);
        layoutWaiting = findViewById(R.id.layout_waiting);

        castManager = ((LocalCastApplication) getApplication()).getCastManager();

        if (castManager != null) {
            tvLocalDevice.setText("本机: " + castManager.getLocalDevice().getDeviceName());
            tvLocalIp.setText("IP: " + NetworkUtils.getLocalIpAddress());
        }

        cardSenderMode.setOnClickListener(v -> finish());

        cardReceiverMode.setOnClickListener(v -> {
            layoutWaiting.setVisibility(View.VISIBLE);
            cardSenderMode.setEnabled(false);
            cardReceiverMode.setEnabled(false);

            if (castManager != null) {
                castManager.getConnectionManager().startAsServer();
                Toast.makeText(this,
                        "接收服务已启动\n本机IP: " + NetworkUtils.getLocalIpAddress(),
                        Toast.LENGTH_LONG).show();
            }

            Intent intent = new Intent(this, ReceiverActivity.class);
            startActivity(intent);
            finish();
        });
    }
}
