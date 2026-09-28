// ClusterMirrorService.java
//
// 奇瑞 T1E / 德赛西威 NV8341 —— 仪表投屏服务
//
// 设计依据（全部来自固件逆向）：
//   · 德赛参考实现 ClusterDisplayService (com.presentation.mypresentation)
//   · 原车高德配置 assets/res/HmiRes/MultipleScreen.json
//   · 德赛 HAL com.desaysv.vehiclelan.proxy@1.0
//
// 关键参数（原厂值）：
//   仪表屏 1920x720, WindowId 1001
//   原厂 Fps=6, Dpi=133（德赛车机算力弱，务必压低）
//   虚拟屏 flags = 11 (0x0B)
//   编码 H.264, color-format = 0x7F000789, 2 Mbps, 20fps
//
// 与德赛原版的差异：
//   【不依赖 vdisplay_jni.so】（固件里没有，会 UnsatisfiedLinkError）
//   → 改用 MediaProjection + createVirtualDisplay（Android 标准路径）
//   → 这条路德赛自己也在用（MediaEncoder 有 MediaProjection 构造函数）

package com.hermes.clustermirror;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.widget.Toast;

import java.nio.ByteBuffer;

/**
 * 把内容投到仪表屏（第二个 Display）。
 *
 * 两种模式：
 *   MODE_STANDARD  —— Presentation 直接画到第二屏（最省事，先试这个）
 *   MODE_ENCODE    —— 编码成 H.264 再送（德赛的路子，兜底）
 */
public class ClusterMirrorService {

    private static final String TAG = "ClusterMirror";

    // ===== 从 MultipleScreen.json 逆向出的原厂参数 =====
    public static final int INSTRUMENT_WIDTH = 1920;
    public static final int INSTRUMENT_HEIGHT = 720;
    public static final int INSTRUMENT_WINDOW_ID = 1001;   // 仪表屏
    public static final int WIDGET_WINDOW_ID = 1002;       // 小卡

    // 原厂刻意压低：德赛车机算力弱
    public static final int ORIGINAL_FPS = 6;
    public static final int ORIGINAL_DPI = 133;

    // ===== 编码参数（来自德赛 MediaEncoder）=====
    private static final String MIME_TYPE = "video/avc";
    private static final int BITRATE = 2_000_000;
    private static final int FRAME_RATE = 20;
    private static final int IFRAME_INTERVAL = 1;
    private static final int COLOR_FORMAT_SURFACE = 0x7F000789;  // COLOR_FormatSurface
    private static final int TIMEOUT_US = 10_000;

    // ===== 虚拟屏参数（来自德赛 LocalService）=====
    private static final String VD_NAME = "PresentationScreen";
    private static final int VD_FLAGS = 11;   // 0x0B

    private final Context context;
    private final DisplayManager displayManager;

    public ClusterMirrorService(Context ctx) {
        this.context = ctx;
        this.displayManager = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
    }

    // ------------------------------------------------------------------
    // 第一步：找出仪表屏
    // ------------------------------------------------------------------

    /** 列出所有 Display（诊断用）。 */
    public Display[] allDisplays() {
        return displayManager.getDisplays();
    }

    /**
     * 列出所有 Display，找出仪表屏。
     *
     * 预期：displayId > 0 的那个就是仪表（QNX 侧暴露过来的）。
     * 另一种可能：仪表屏压根不在 DisplayManager 里（归 QNX 管），
     *            此时必须走 IDisplayProxy HAL。
     */
    public Display findInstrumentDisplay() {
        Display[] displays = displayManager.getDisplays();
        Log.i(TAG, "共发现 " + displays.length + " 个 Display");
        Display best = null;
        for (Display d : displays) {
            String name = d.getName();
            Log.i(TAG, "  displayId=" + d.getDisplayId()
                    + " name=" + name
                    + " flags=0x" + Integer.toHexString(d.getFlags())
                    + " size=" + d.getMode().getPhysicalWidth()
                    + "x" + d.getMode().getPhysicalHeight());
            // 仪表屏特征：不是默认屏，分辨率像 1920x720
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY) {
                int w = d.getMode().getPhysicalWidth();
                int h = d.getMode().getPhysicalHeight();
                if (w == INSTRUMENT_WIDTH && h == INSTRUMENT_HEIGHT) {
                    Log.i(TAG, "  → 命中仪表屏（分辨率匹配）");
                    return d;
                }
                if (best == null) best = d;
            }
        }
        if (best != null) {
            Log.w(TAG, "未精确匹配 1920x720，退而用 displayId=" + best.getDisplayId());
        } else {
            Log.e(TAG, "❌ 找不到第二屏 —— 仪表屏可能归 QNX 管，需走 IDisplayProxy");
        }
        return best;
    }

    // ------------------------------------------------------------------
    // 第二步（模式 A）：Presentation 直接画（最省事）
    // ------------------------------------------------------------------

    /**
     * 用 Presentation 把任意 View 画到仪表屏。
     *
     * 前提：/etc/permissions/android.software.activities_on_secondary_displays.xml
     *       这份权限在系统里（vendor 分区已确认存在）。
     */
    public InstrumentPresentation showOnInstrument(Display target, android.view.View view) {
        if (target == null) return null;
        try {
            InstrumentPresentation p = new InstrumentPresentation(context, target);
            p.setContentView(view);
            p.show();
            Log.i(TAG, "✅ Presentation 已显示到 displayId=" + target.getDisplayId());
            return p;
        } catch (Exception e) {
            Log.e(TAG, "❌ Presentation 失败: " + e);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 第三步（模式 B）：编码投屏（德赛的路子，兜底）
    // ------------------------------------------------------------------

    /**
     * 申请 MediaProjection 权限（需用户点确认）。
     * 在 Activity 里调用：
     *   startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CODE);
     */
    public static Intent createProjectionIntent(Context ctx) {
        MediaProjectionManager mpm = (MediaProjectionManager)
                ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        return mpm.createScreenCaptureIntent();
    }

    /**
     * 拿到 MediaProjection 后，建虚拟屏 + H.264 编码。
     *
     * 这条路径德赛自己在用（MediaEncoder 有 MediaProjection 构造函数），
     * 且不需要 vdisplay_jni.so。
     */
    public ClusterEncoder startEncoding(MediaProjection projection,
                                        int width, int height, int dpi,
                                        Surface outputSurface) throws Exception {
        ClusterEncoder enc = new ClusterEncoder(projection, width, height, dpi, outputSurface);
        enc.start();
        return enc;
    }

    // ------------------------------------------------------------------
    // 内部类：Presentation
    // ------------------------------------------------------------------
    public static class InstrumentPresentation extends android.app.Presentation {
        public InstrumentPresentation(Context ctx, Display display) {
            super(ctx, display);
        }

        @Override
        public void onDisplayRemoved() {
            Log.w(TAG, "仪表屏断开");
            super.onDisplayRemoved();
            dismiss();
        }
    }

    // ------------------------------------------------------------------
    // 内部类：编码器（照德赛 MediaEncoder 复刻，去掉私有依赖）
    // ------------------------------------------------------------------
    public static class ClusterEncoder extends Thread {

        private final MediaProjection projection;
        private final int width, height, dpi;
        private final Surface outputSurface;   // 目标屏 Surface（可为 null）

        private MediaCodec encoder;
        private VirtualDisplay virtualDisplay;
        private MediaCodec.BufferInfo bufferInfo;
        private byte[] sps, pps;
        private volatile boolean running = true;

        public interface FrameCallback {
            /** 每编码出一帧 H.264 数据时回调。 */
            void onFrame(byte[] data, int flags, long ptsUs);
        }

        private FrameCallback callback;

        public ClusterEncoder(MediaProjection p, int w, int h, int dpi, Surface out) {
            this.projection = p;
            this.width = w;
            this.height = h;
            this.dpi = dpi;
            this.outputSurface = out;
            this.bufferInfo = new MediaCodec.BufferInfo();
        }

        public void setFrameCallback(FrameCallback cb) {
            this.callback = cb;
        }

        @Override
        public void run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
            try {
                prepareEncoder();
                loop();
            } catch (Exception e) {
                Log.e(TAG, "编码线程异常: " + e, e);
            } finally {
                release();
            }
        }

        private void prepareEncoder() throws Exception {
            // 1) 创建 H.264 编码器（Surface 输入模式，零拷贝）
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_FORMAT_SURFACE);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);

            encoder = MediaCodec.createEncoderByType(MIME_TYPE);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

            // 2) 编码器的输入 Surface —— 虚拟屏就往这里画
            Surface inputSurface = encoder.createInputSurface();
            encoder.start();

            // 3) 虚拟屏（德赛参数：名字 PresentationScreen，flags=11）
            virtualDisplay = projection.createVirtualDisplay(
                    VD_NAME, width, height, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
                            | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,   // = 0x0B
                    inputSurface, null, null);

            Log.i(TAG, "编码器就绪 " + width + "x" + height + " @" + FRAME_RATE + "fps "
                    + (BITRATE / 1000) + "kbps");
        }

        private void loop() {
            while (running) {
                int idx = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue;
                }
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    grabSpsPps(encoder.getOutputFormat());
                    continue;
                }
                if (idx < 0) continue;

                ByteBuffer buf = encoder.getOutputBuffer(idx);
                if (buf != null && bufferInfo.size > 0) {
                    // 首帧带上 SPS/PPS（解码端需要）
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0;
                    }
                    if (bufferInfo.size > 0) {
                        buf.position(bufferInfo.offset);
                        buf.limit(bufferInfo.offset + bufferInfo.size);
                        byte[] data = new byte[bufferInfo.size];
                        buf.get(data);
                        if (callback != null) {
                            callback.onFrame(data, bufferInfo.flags, bufferInfo.presentationTimeUs);
                        }
                    }
                }
                encoder.releaseOutputBuffer(idx, true);
            }
        }

        private void grabSpsPps(MediaFormat fmt) {
            try {
                ByteBuffer csd0 = fmt.getByteBuffer("csd-0");
                ByteBuffer csd1 = fmt.getByteBuffer("csd-1");
                if (csd0 != null) { sps = new byte[csd0.remaining()]; csd0.get(sps); }
                if (csd1 != null) { pps = new byte[csd1.remaining()]; csd1.get(pps); }
                Log.i(TAG, "SPS " + (sps == null ? "null" : sps.length + "B")
                        + "  PPS " + (pps == null ? "null" : pps.length + "B"));
            } catch (Exception e) {
                Log.w(TAG, "取 SPS/PPS 失败: " + e);
            }
        }

        public byte[] getSps() { return sps; }
        public byte[] getPps() { return pps; }

        public void stopEncoding() {
            running = false;
        }

        private void release() {
            try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) {}
            try { if (encoder != null) { encoder.stop(); encoder.release(); } } catch (Exception ignored) {}
            try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
            Log.i(TAG, "编码器已释放");
        }
    }
}
