package com.localcast.pro.ui;



import android.graphics.Matrix;

import android.graphics.Point;

import android.graphics.SurfaceTexture;

import android.os.Build;

import android.os.Bundle;

import android.view.GestureDetector;

import android.view.Gravity;

import android.view.MotionEvent;

import android.view.ScaleGestureDetector;

import android.view.TextureView;

import android.view.View;

import android.view.ViewTreeObserver;

import android.view.WindowInsetsController;

import android.widget.FrameLayout;

import android.widget.TextView;

import android.widget.Toast;



import androidx.appcompat.app.AppCompatActivity;



import com.google.android.material.button.MaterialButton;

import com.localcast.pro.LocalCastApplication;

import com.localcast.pro.R;

import com.localcast.pro.core.CastManager;

import com.localcast.pro.core.DeviceManager;

import com.localcast.pro.core.ReceiverManager;

import com.localcast.pro.utils.DisplayUtils;

import com.localcast.pro.utils.NetworkUtils;

import java.util.Locale;



public class ReceiverActivity extends AppCompatActivity

        implements TextureView.SurfaceTextureListener {



    private TextureView tvDisplay;

    private TextView tvSenderName, tvRecvResolution, tvRecvBitrate, tvRecvLatency;

    private View layoutInfoBar, layoutControlBar;

    private MaterialButton btnDisplayMode, btnStopRecv;



    private CastManager castManager;

    private DeviceManager receiverAdvertiser;

    private int displayMode = ReceiverManager.DISPLAY_MODE_FIT;

    private final String[] modeNames = {"等比缩放", "全屏铺满", "全屏拉伸", "原始尺寸"};



    private GestureDetector gestureDetector;

    private ScaleGestureDetector scaleDetector;

    private float userScale = 1.0f;



    private int videoWidth = 0;

    private int videoHeight = 0;

    private int viewWidth;

    private int viewHeight;

    private int displayLayoutWidth;

    private int displayLayoutHeight;



    private final Runnable hideBars = () -> {

        if (isFullscreenDisplayMode()) return;

        layoutInfoBar.animate().alpha(0f).setDuration(500).start();

        layoutControlBar.animate().alpha(0f).setDuration(500).start();

    };

    private boolean barsVisible = true;



    private boolean isFullscreenDisplayMode() {

        return displayMode == ReceiverManager.DISPLAY_MODE_COVER

                || displayMode == ReceiverManager.DISPLAY_MODE_STRETCH;

    }



    @Override

    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_receiver);



        tvDisplay = findViewById(R.id.tv_display);

        tvDisplay.setSurfaceTextureListener(this);

        tvSenderName = findViewById(R.id.tv_sender_name);

        tvRecvResolution = findViewById(R.id.tv_recv_resolution);

        tvRecvBitrate = findViewById(R.id.tv_recv_bitrate);

        tvRecvLatency = findViewById(R.id.tv_recv_latency);

        layoutInfoBar = findViewById(R.id.layout_info_bar);

        layoutControlBar = findViewById(R.id.layout_control_bar);

        btnDisplayMode = findViewById(R.id.btn_display_mode);

        btnStopRecv = findViewById(R.id.btn_stop_recv);



        castManager = ((LocalCastApplication) getApplication()).getCastManager();

        if (castManager == null) {

            Toast.makeText(this, "初始化失败，请返回重试", Toast.LENGTH_LONG).show();

            finish();

            return;

        }



        setupGestures();

        setupControls();



        String localIp = NetworkUtils.getLocalIpAddress();

        tvSenderName.setText("等待投屏… IP: " + localIp);

        Toast.makeText(this, "等待投屏连接…\n本机IP: " + localIp, Toast.LENGTH_LONG).show();



        castManager.setOnCastStateListener(new CastManager.OnCastStateListener() {

            @Override public void onModeChanged(CastManager.CastMode m) {

                if (m == CastManager.CastMode.IDLE) finish();

            }

            @Override public void onConnected(com.localcast.pro.core.DeviceInfo d) {

                runOnUiThread(() -> tvSenderName.setText("接收自 " + d.getDeviceName()));

            }

            @Override public void onDisconnected(String r) {

                runOnUiThread(() -> {

                    String localIp = NetworkUtils.getLocalIpAddress();

                    tvSenderName.setText("等待投屏… IP: " + localIp);

                    Toast.makeText(ReceiverActivity.this,

                            "连接断开，等待发送端重连", Toast.LENGTH_SHORT).show();

                });

            }

            @Override public void onReceiverStats(int fps, int kbps, long ms) {

                runOnUiThread(() -> {

                    tvRecvBitrate.setText(String.format(Locale.getDefault(), "%.1f Mbps", kbps / 1000.0));

                    tvRecvLatency.setText(ms + "ms");

                    int c = ms <= 50 ? R.color.success : (ms <= 100 ? R.color.warning : R.color.md_error);

                    tvRecvLatency.setTextColor(getColor(c));

                });

            }

            @Override public void onVideoSizeChanged(int w, int h) {

                runOnUiThread(() -> {

                    videoWidth = w;

                    videoHeight = h;

                    tvRecvResolution.setText(h >= 2160 ? "4K" : h >= 1080 ? "1080p" : h + "p");

                    syncSurfaceBufferSize();

                    applyDisplayLayout(displayLayoutWidth, displayLayoutHeight);

                });

            }

            @Override public void onDisplaySizeChanged(int w, int h) {

                runOnUiThread(() -> applyDisplayLayout(w, h));

            }

            @Override public void onDisplayModeChanged(int mode) {

                runOnUiThread(() -> applyDisplayMode(mode));

            }

            @Override public void onSenderStats(int f, int b, long l) {}

            @Override public void onError(String e) {

                runOnUiThread(() -> {

                    Toast.makeText(ReceiverActivity.this, e, Toast.LENGTH_LONG).show();

                });

            }

        });



        scheduleHideBars();

        castManager.ensureReceiverReady();
        startReceiverAdvertisement();

    }



    @Override

    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {

        syncSurfaceBufferSize(surface);

        viewWidth = width;

        viewHeight = height;

        castManager.startAsReceiver(new android.view.Surface(surface));

        notifyViewSizeChanged(width, height);

        applyDisplayLayout(displayLayoutWidth, displayLayoutHeight);

    }



    @Override

    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

        viewWidth = width;

        viewHeight = height;

        notifyViewSizeChanged(width, height);

        applyDisplayLayout(displayLayoutWidth, displayLayoutHeight);

    }



    @Override

    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {

        return true;

    }



    @Override

    public void onSurfaceTextureUpdated(SurfaceTexture surface) {

    }



    private void notifyViewSizeChanged(int width, int height) {

        ReceiverManager receiverManager = castManager.getReceiverManager();

        if (receiverManager != null) {

            receiverManager.setViewSize(width, height);

        }

    }



    private void refreshViewSizeFromLayout() {

        int w = tvDisplay.getWidth();

        int h = tvDisplay.getHeight();

        if (w > 0 && h > 0) {

            viewWidth = w;

            viewHeight = h;

            notifyViewSizeChanged(w, h);

        }

    }



    /** 变换目标区域：优先 TextureView 实测尺寸，4K 电视回退物理分辨率 */

    private int[] getTransformTargetSize() {

        int w = tvDisplay.getWidth();

        int h = tvDisplay.getHeight();

        if (w <= 0 || h <= 0) {

            w = viewWidth;

            h = viewHeight;

        }

        if (w <= 0 || h <= 0) {

            View content = findViewById(android.R.id.content);

            if (content != null) {

                w = content.getWidth();

                h = content.getHeight();

            }

        }

        if (w <= 0 || h <= 0) {

            Point physical = DisplayUtils.getPhysicalScreenSize(this);

            w = physical.x;

            h = physical.y;

        }

        return new int[]{w, h};

    }



    private int[] getVideoBufferSize() {

        int bufferW = videoWidth;

        int bufferH = videoHeight;

        if (bufferW <= 0 || bufferH <= 0) {

            bufferW = castManager.getConnectionManager().getNegotiatedWidth();

            bufferH = castManager.getConnectionManager().getNegotiatedHeight();

        }

        return new int[]{bufferW, bufferH};

    }



    private void syncSurfaceBufferSize() {

        SurfaceTexture surfaceTexture = tvDisplay.getSurfaceTexture();

        if (surfaceTexture != null) {

            syncSurfaceBufferSize(surfaceTexture);

        }

    }



    private void syncSurfaceBufferSize(SurfaceTexture surfaceTexture) {

        int[] buffer = getVideoBufferSize();

        if (buffer[0] <= 0 || buffer[1] <= 0) return;

        surfaceTexture.setDefaultBufferSize(buffer[0], buffer[1]);

    }



    private void applyDisplayLayout(int displayW, int displayH) {

        if (viewWidth <= 0 || viewHeight <= 0) return;



        displayLayoutWidth = displayW;

        displayLayoutHeight = displayH;



        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tvDisplay.getLayoutParams();



        if (isFullscreenDisplayMode()) {

            lp.width = FrameLayout.LayoutParams.MATCH_PARENT;

            lp.height = FrameLayout.LayoutParams.MATCH_PARENT;

            lp.gravity = Gravity.CENTER;

            tvDisplay.setLayoutParams(lp);

            syncSurfaceBufferSize();

            // COVER/STRETCH 模式切换后必须等布局 pass 完成，tvDisplay.getWidth/Height 才能返回
            // 真实的全屏尺寸。直接调用 updateTextureTransform() 会拿到切换前的旧尺寸（如
            // FIT 模式的小窗尺寸），导致 Matrix scale < 1，视频缩小显示在屏幕中央。
            ViewTreeObserver vto = tvDisplay.getViewTreeObserver();

            if (vto.isAlive()) {

                vto.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {

                    @Override

                    public void onGlobalLayout() {

                        if (tvDisplay.getViewTreeObserver().isAlive()) {

                            tvDisplay.getViewTreeObserver().removeOnGlobalLayoutListener(this);

                        }

                        int w = tvDisplay.getWidth();

                        int h = tvDisplay.getHeight();

                        if (w > 0 && h > 0) {

                            viewWidth = w;

                            viewHeight = h;

                            notifyViewSizeChanged(w, h);

                        }

                        updateTextureTransform();

                    }

                });

            } else {

                // ViewTreeObserver 不可用时退化为 post，稍有风险但不阻塞
                tvDisplay.post(this::updateTextureTransform);

            }

        } else if (displayMode == ReceiverManager.DISPLAY_MODE_ORIGINAL) {

            lp.width = videoWidth > 0 ? videoWidth : Math.max(displayW, 1);

            lp.height = videoHeight > 0 ? videoHeight : Math.max(displayH, 1);

            lp.gravity = Gravity.CENTER;

            tvDisplay.setLayoutParams(lp);

            updateTextureTransform();

        } else {

            lp.width = displayW > 0 ? displayW : FrameLayout.LayoutParams.MATCH_PARENT;

            lp.height = displayH > 0 ? displayH : FrameLayout.LayoutParams.MATCH_PARENT;

            lp.gravity = Gravity.CENTER;

            tvDisplay.setLayoutParams(lp);

            updateTextureTransform();

        }

    }



    /**
     * 计算并应用 TextureView 的 Matrix 变换，实现不同显示模式。
     *
     * TextureView 的默认行为：将 SurfaceTexture 缓冲区（bufferW×bufferH）拉伸填满自身
     * 视图区域（targetW×targetH）。如果二者宽高比不同，默认会产生变形。
     * Matrix 作用于视图坐标空间，用于修正/补偿这一默认拉伸，达到目标显示效果：
     *
     * - COVER  : 等比放大裁剪，保持视频比例铺满屏幕（object-fit: cover）
     * - STRETCH: 直接拉伸填满，可能变形（保持 TextureView 默认行为，Identity Matrix）
     * - FIT    : 等比缩放居中，保持视频比例，可能有黑边（object-fit: contain）
     * - ORIGINAL: 按视频原始像素 1:1 显示
     *
     * 公式推导（以 COVER 为例）：
     *   TextureView 默认 X 轴拉伸 stretchX = targetW/bufferW，Y 轴拉伸 stretchY = targetH/bufferH。
     *   COVER 目标：让 X/Y 统一按 max(stretchX, stretchY) 缩放。
     *   若 stretchX >= stretchY，X 已是主轴，需将 Y 额外放大 stretchX/stretchY 倍来匹配 X 轴比例。
     */

    private void updateTextureTransform() {

        int[] target = getTransformTargetSize();

        int targetW = target[0];

        int targetH = target[1];

        int[] buffer = getVideoBufferSize();

        int bufferW = buffer[0];

        int bufferH = buffer[1];

        if (bufferW <= 0 || bufferH <= 0 || targetW <= 0 || targetH <= 0) return;

        // TextureView 默认隐式拉伸因子
        float stretchX = (float) targetW / bufferW;

        float stretchY = (float) targetH / bufferH;

        float scaleX;

        float scaleY;



        switch (displayMode) {

            case ReceiverManager.DISPLAY_MODE_COVER:

                // 补偿默认拉伸，让两轴统一按 max(stretchX, stretchY) 缩放 → 填满并裁剪
                if (stretchX >= stretchY) {

                    scaleX = 1f;

                    scaleY = stretchX / stretchY;

                } else {

                    scaleX = stretchY / stretchX;

                    scaleY = 1f;

                }

                break;



            case ReceiverManager.DISPLAY_MODE_STRETCH:

                // 直接拉伸即为 TextureView 默认行为，Identity Matrix
                tvDisplay.setTransform(new Matrix());

                return;



            case ReceiverManager.DISPLAY_MODE_ORIGINAL:

                // 抵消默认拉伸，回到 1:1 像素显示
                scaleX = 1f / stretchX;

                scaleY = 1f / stretchY;

                break;



            case ReceiverManager.DISPLAY_MODE_FIT:

            default:

                // FIT 模式下 TextureView 已由 applyDisplayLayout 设置为视频等比尺寸，
                // stretchX ≈ stretchY，Matrix 近似 Identity；只需处理用户 pinch 缩放。
                if (stretchX <= stretchY) {

                    scaleX = 1f;

                    scaleY = stretchX / stretchY;

                } else {

                    scaleX = stretchY / stretchX;

                    scaleY = 1f;

                }

                break;

        }



        // 叠加用户手势缩放
        scaleX *= userScale;

        scaleY *= userScale;



        // 居中：(targetSize * (1 - scale)) / 2
        float tx = targetW * (1f - scaleX) / 2f;

        float ty = targetH * (1f - scaleY) / 2f;



        Matrix matrix = new Matrix();

        matrix.setScale(scaleX, scaleY);

        matrix.postTranslate(tx, ty);

        tvDisplay.setTransform(matrix);

    }



    private void enterImmersiveFullscreen(boolean immersive) {

        View decorView = getWindow().getDecorView();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {

            WindowInsetsController controller = decorView.getWindowInsetsController();

            if (controller != null) {

                if (immersive) {

                    controller.hide(android.view.WindowInsets.Type.statusBars()

                            | android.view.WindowInsets.Type.navigationBars());

                    controller.setSystemBarsBehavior(

                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

                } else {

                    controller.show(android.view.WindowInsets.Type.statusBars()

                            | android.view.WindowInsets.Type.navigationBars());

                }

            }

        } else {

            if (immersive) {

                decorView.setSystemUiVisibility(

                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

                                | View.SYSTEM_UI_FLAG_FULLSCREEN

                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION

                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

            } else {

                decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

            }

        }

    }



    private void applyDisplayMode(int mode) {

        displayMode = mode;

        if (displayMode >= 0 && displayMode < modeNames.length) {

            btnDisplayMode.setText(modeNames[displayMode]);

        }

        userScale = 1.0f;

        if (isFullscreenDisplayMode()) {

            enterImmersiveFullscreen(true);

            layoutInfoBar.setVisibility(View.GONE);

            layoutControlBar.setVisibility(View.GONE);

            barsVisible = false;

        } else {

            enterImmersiveFullscreen(false);

            layoutInfoBar.setVisibility(View.VISIBLE);

            layoutControlBar.setVisibility(View.VISIBLE);

            layoutInfoBar.setAlpha(0.92f);

            layoutControlBar.setAlpha(0.92f);

            barsVisible = true;

        }

        tvDisplay.post(() -> {

            refreshViewSizeFromLayout();

            applyDisplayLayout(displayLayoutWidth, displayLayoutHeight);

        });

    }



    private void requestDisplayMode(int mode) {

        castManager.setDisplayMode(mode);

    }



    private void setupGestures() {

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {

            @Override

            public boolean onDoubleTap(MotionEvent e) {

                requestDisplayMode(displayMode == ReceiverManager.DISPLAY_MODE_FIT

                        ? ReceiverManager.DISPLAY_MODE_COVER

                        : ReceiverManager.DISPLAY_MODE_FIT);

                return true;

            }



            @Override

            public boolean onSingleTapConfirmed(MotionEvent e) {

                if (isFullscreenDisplayMode()) {

                    requestDisplayMode(ReceiverManager.DISPLAY_MODE_FIT);

                    Toast.makeText(ReceiverActivity.this, modeNames[ReceiverManager.DISPLAY_MODE_FIT],

                            Toast.LENGTH_SHORT).show();

                } else {

                    toggleBars();

                }

                return true;

            }

        });



        scaleDetector = new ScaleGestureDetector(this,

                new ScaleGestureDetector.SimpleOnScaleGestureListener() {

                    @Override

                    public boolean onScale(ScaleGestureDetector d) {

                        userScale *= d.getScaleFactor();

                        userScale = Math.max(0.5f, Math.min(3.0f, userScale));

                        updateTextureTransform();

                        return true;

                    }

                });

    }



    private void setupControls() {

        btnDisplayMode.setOnClickListener(v -> {

            int newMode = (displayMode + 1) % modeNames.length;

            requestDisplayMode(newMode);

            Toast.makeText(this, modeNames[newMode], Toast.LENGTH_SHORT).show();

        });



        btnStopRecv.setOnClickListener(v -> {

            castManager.stopCurrentMode();

            finish();

        });



        tvDisplay.setOnTouchListener((v, e) -> {

            gestureDetector.onTouchEvent(e);

            scaleDetector.onTouchEvent(e);

            if (e.getAction() == MotionEvent.ACTION_DOWN && !isFullscreenDisplayMode()) {

                showBars();

                tvDisplay.removeCallbacks(hideBars);

                tvDisplay.postDelayed(hideBars, 5000);

            }

            return true;

        });

    }



    private void toggleBars() { if (barsVisible) hideBarsNow(); else showBars(); }

    private void showBars() {

        barsVisible = true;

        layoutInfoBar.setVisibility(View.VISIBLE);

        layoutControlBar.setVisibility(View.VISIBLE);

        layoutInfoBar.animate().alpha(0.92f).setDuration(300).start();

        layoutControlBar.animate().alpha(0.92f).setDuration(300).start();

    }

    private void hideBarsNow() {

        barsVisible = false;

        layoutInfoBar.animate().alpha(0f).setDuration(500).start();

        layoutControlBar.animate().alpha(0f).setDuration(500).start();

    }

    private void scheduleHideBars() { tvDisplay.postDelayed(hideBars, 5000); }



    @Override

    public void onBackPressed() {

        if (castManager != null) castManager.stopCurrentMode();

        super.onBackPressed();

    }

    @Override

    protected void onDestroy() {

        stopReceiverAdvertisement();
        super.onDestroy();

        tvDisplay.removeCallbacks(hideBars);

    }

    private void startReceiverAdvertisement() {
        DeviceManager.setReceiverAvailable(true);
        receiverAdvertiser = new DeviceManager();
        receiverAdvertiser.init(this);
        receiverAdvertiser.registerService(castManager.getLocalDevice());
    }

    private void stopReceiverAdvertisement() {
        DeviceManager.setReceiverAvailable(false);
        if (receiverAdvertiser != null) {
            receiverAdvertiser.unregisterService();
            receiverAdvertiser = null;
        }
    }

}

