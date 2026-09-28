// MainActivity.java
//
// 飞屏选择器 —— 主界面
//
// 交互：点任意已安装 App → 自动走最优通道把它投到仪表屏
//   通道① 直投：ActivityOptions.setLaunchDisplayId 把目标 App 启动到仪表屏
//   通道② 镜像：目标 App 在中控前台运行 → MediaProjection 虚拟屏
//               直接输出到仪表 Presentation 的 Surface（零编解码）
//   默认"先试①，异常自动切②"；勾选"强制镜像"可跳过①（直投无声失败时用）
//
// 诊断区保留了探测版的三个按钮（列屏幕/试画仪表/全部释放），上车先跑①看屏幕拓扑。

package com.hermes.clustermirror;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String TAG = "ClusterFly";
    private static final int REQ_PROJECTION = 1001;

    private ClusterMirrorService service;
    private MediaProjectionManager mpm;
    private MediaProjection projection;
    private VirtualDisplay mirrorVd;
    private ClusterMirrorService.InstrumentPresentation presentation;

    private Display cluster;               // 仪表屏
    private String pendingPkg;             // 等授权期间记住要飞的包

    private TextView statusView;
    private TextView logView;
    private CheckBox forceMirrorCb;
    private CheckBox autoFlyCb;            // 记住并自动飞上次
    private SharedPreferences prefs;
    private LinearLayout diagBox;
    private AppAdapter adapter;
    private List<ResolveInfo> allApps = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        service = new ClusterMirrorService(this);
        mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        prefs = getSharedPreferences("fly", MODE_PRIVATE);
        buildUi();
        loadApps();
        refreshCluster();

        // 勾选状态持久化
        forceMirrorCb.setOnCheckedChangeListener(
                (b, c) -> prefs.edit().putBoolean("force_mirror", c).apply());
        autoFlyCb.setOnCheckedChangeListener(
                (b, c) -> prefs.edit().putBoolean("auto_fly", c).apply());
        forceMirrorCb.setChecked(prefs.getBoolean("force_mirror", false));
        autoFlyCb.setChecked(prefs.getBoolean("auto_fly", true));

        log("就绪。点列表里的 App 开始飞屏。");

        // 自动恢复上次飞屏（等 onResume 之后再动，避免授权弹窗时机问题）
        final String lastPkg = prefs.getString("last_pkg", null);
        if (autoFlyCb.isChecked() && lastPkg != null
                && getPackageManager().getLaunchIntentForPackage(lastPkg) != null) {
            final String lastLabel = prefs.getString("last_label", lastPkg);
            log("自动恢复上次飞屏: " + lastLabel);
            statusView.postDelayed(() -> autoFly(lastPkg), 800);
        }
    }

    /** 按包名找启动入口。 */
    private ResolveInfo findLaunchInfo(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        return i == null ? null : getPackageManager().resolveActivity(i, 0);
    }

    /** 恢复上次的飞屏操作。 */
    private void autoFly(String pkg) {
        ResolveInfo ri = findLaunchInfo(pkg);
        if (ri == null) {
            log("❌ 上次的 App 已卸载: " + pkg);
            return;
        }
        onAppClick(ri);
    }

    /** 记住本次选择（下次开机打开自动恢复）。 */
    private void remember(String pkg, String label) {
        prefs.edit().putString("last_pkg", pkg).putString("last_label", label).apply();
    }

    // ================= UI =================

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 20, 20, 20);

        statusView = new TextView(this);
        statusView.setTextSize(13);
        statusView.setPadding(0, 0, 0, 8);
        root.addView(statusView);

        forceMirrorCb = new CheckBox(this);
        forceMirrorCb.setText("强制镜像（直投没画面时勾选）");
        forceMirrorCb.setTextSize(13);
        root.addView(forceMirrorCb);

        autoFlyCb = new CheckBox(this);
        autoFlyCb.setText("记住并自动飞上次（打开本软件即恢复）");
        autoFlyCb.setTextSize(13);
        root.addView(autoFlyCb);

        // 按钮行：停止飞屏 / 诊断
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView stopBtn = mkButton("停止飞屏", v -> stopFly());
        TextView diagBtn = mkButton("诊断", v ->
                diagBox.setVisibility(diagBox.getVisibility() == View.GONE
                        ? View.VISIBLE : View.GONE));
        row.addView(stopBtn);
        row.addView(diagBtn);
        root.addView(row);

        // ---- 导航行（车机无系统返回/桌面键，这里补上）----
        LinearLayout navRow = new LinearLayout(this);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navRow.addView(mkButton("返回桌面", v -> {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
            log("已返回车机桌面（飞屏继续运行）");
        }));
        navRow.addView(mkButton("最小化", v -> {
            moveTaskToBack(true);   // 任务退到后台，不销毁，飞屏继续
            log("已最小化（飞屏继续运行）");
        }));
        navRow.addView(mkButton("关闭", v -> {
            log("关闭软件：已停止飞屏并退出");
            stopFly();
            finish();
        }));
        root.addView(navRow);

        // ---- 诊断区（探测版三按钮 + 日志）----
        diagBox = new LinearLayout(this);
        diagBox.setOrientation(LinearLayout.VERTICAL);
        diagBox.setVisibility(View.GONE);

        LinearLayout drow = new LinearLayout(this);
        drow.setOrientation(LinearLayout.HORIZONTAL);
        drow.addView(mkButton("①列屏幕", v -> dumpDisplays()));
        drow.addView(mkButton("②试画仪表", v -> tryPresentation()));
        drow.addView(mkButton("③全部释放", v -> stopFly()));
        diagBox.addView(drow);

        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTextColor(Color.WHITE);
        logView.setBackgroundColor(0xDD000000);
        logView.setPadding(12, 12, 12, 12);
        ScrollView sv = new ScrollView(this);
        sv.addView(logView);
        diagBox.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 420));
        root.addView(diagBox);

        // ---- 搜索过滤 ----
        EditText filter = new EditText(this);
        filter.setHint("搜索 App…");
        root.addView(filter);
        filter.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable e) { adapter.getFilter().filter(e.toString()); }
        });

        // ---- App 列表 ----
        adapter = new AppAdapter();
        ListView list = new ListView(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) ->
                onAppClick(adapter.getItem(position)));
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        updateStatus();
    }

    private TextView mkButton(String text, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(14);
        b.setPadding(24, 16, 24, 16);
        b.setTextColor(0xFF2196F3);
        b.setOnClickListener(l);
        return b;
    }

    private void log(String s) {
        Log.i(TAG, s);
        runOnUiThread(() -> logView.append(s + "\n"));
    }

    private void updateStatus() {
        String clusterInfo = cluster == null
                ? "❌ 未发现仪表屏"
                : "✅ 仪表屏 displayId=" + cluster.getDisplayId()
                  + " " + cluster.getMode().getPhysicalWidth()
                  + "x" + cluster.getMode().getPhysicalHeight();
        String flyInfo = mirrorVd != null ? "🛫 镜像飞屏运行中"
                : (presentation != null ? "🖼 Presentation 显示中" : "○ 未飞屏");
        statusView.setText(clusterInfo + "\n" + flyInfo
                + "   通道: " + (forceMirrorCb.isChecked() ? "强制镜像" : "直投优先"));
    }

    // ================= App 列表 =================

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ri = pm.queryIntentActivities(launcher, 0);
        final Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(ri, (a, b) -> collator.compare(
                a.loadLabel(pm).toString(), b.loadLabel(pm).toString()));
        allApps.clear();
        allApps.addAll(ri);
        adapter.setVisible(allApps);   // 必须灌进 shown，否则列表是空的
        log("已加载 " + ri.size() + " 个可飞 App");
    }

    private class AppAdapter extends BaseAdapter {
        private List<ResolveInfo> shown = new ArrayList<>(allApps);

        void setVisible(List<ResolveInfo> list) {
            shown.clear();
            shown.addAll(list);
            notifyDataSetChanged();
        }

        @Override public int getCount() { return shown.size(); }
        @Override public ResolveInfo getItem(int pos) { return shown.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            Context ctx = MainActivity.this;
            ResolveInfo info = shown.get(pos);
            LinearLayout row;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
            } else {
                row = new LinearLayout(ctx);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(20, 14, 20, 14);
                ImageView iv = new ImageView(ctx);
                int px = (int) (40 * getResources().getDisplayMetrics().density);
                row.addView(iv, new LinearLayout.LayoutParams(px, px));
                TextView tv = new TextView(ctx);
                tv.setTextSize(16);
                tv.setPadding(20, 0, 0, 0);
                row.addView(tv, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            ((ImageView) row.getChildAt(0)).setImageDrawable(
                    info.loadIcon(getPackageManager()));
            ((TextView) row.getChildAt(1)).setText(
                    info.loadLabel(getPackageManager()));
            return row;
        }

        // 简单名字过滤
        android.widget.Filter getFilter() {
            return new android.widget.Filter() {
                @Override
                protected FilterResults performFiltering(CharSequence constraint) {
                    FilterResults r = new FilterResults();
                    String q = constraint == null ? "" : constraint.toString().toLowerCase(Locale.ROOT);
                    List<ResolveInfo> out = new ArrayList<>();
                    if (q.isEmpty()) {
                        out.addAll(allApps);
                    } else {
                        for (ResolveInfo ri : allApps) {
                            String label = ri.loadLabel(getPackageManager()).toString()
                                    .toLowerCase(Locale.ROOT);
                            if (label.contains(q)
                                    || ri.activityInfo.packageName.contains(q)) {
                                out.add(ri);
                            }
                        }
                    }
                    r.values = out;
                    r.count = out.size();
                    return r;
                }
                @Override
                @SuppressWarnings("unchecked")
                protected void publishResults(CharSequence c, FilterResults r) {
                    final List<ResolveInfo> list = (List<ResolveInfo>) r.values;
                    runOnUiThread(() -> setVisible(list));   // 保证在 UI 线程刷新
                }
            };
        }
    }

    // ================= 飞屏主流程 =================

    private void refreshCluster() {
        cluster = service.findInstrumentDisplay();
        updateStatus();
    }

    private void onAppClick(ResolveInfo info) {
        final String pkg = info.activityInfo.packageName;
        final String label = info.loadLabel(getPackageManager()).toString();
        refreshCluster();
        if (cluster == null) {
            log("❌ 没有仪表屏，先跑诊断①");
            diagBox.setVisibility(View.VISIBLE);
            return;
        }
        log("── 飞屏: " + label + " (" + pkg + ") ──");
        remember(pkg, label);

        if (!forceMirrorCb.isChecked()) {
            if (tryDirectLaunch(info)) {
                updateStatus();
                return;                       // 通道① 成功
            }
            log("→ 直投失败，自动切镜像通道");
        }
        flyByMirror(pkg, label);
    }

    /** 通道①：把目标 App 直接启动到仪表屏。 */
    private boolean tryDirectLaunch(ResolveInfo info) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.setClassName(info.activityInfo.packageName, info.activityInfo.name);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(cluster.getDisplayId());
            startActivity(i, opts.toBundle());
            log("✅ 通道①直投: 已启动到 displayId=" + cluster.getDisplayId()
                    + "（看仪表有没有画面；没有就勾「强制镜像」）");
            return true;
        } catch (Throwable t) {
            log("❌ 通道①直投异常: " + t);
            return false;
        }
    }

    /** 通道②：镜像。先确保有 MediaProjection，再建虚拟屏，最后把 App 拉到中控前台。 */
    private void flyByMirror(String pkg, String label) {
        if (projection != null && mirrorVd != null) {
            // 镜像已在跑，直接把 App 拉到中控前台即可
            launchOnMain(pkg);
            log("✅ 通道②镜像: " + label + " 已在中控前台（仪表同步显示）");
            updateStatus();
            return;
        }
        pendingPkg = pkg;
        log("申请录屏授权（系统弹窗，点「开始」）…");
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PROJECTION) return;
        if (res == RESULT_OK && data != null) {
            projection = mpm.getMediaProjection(res, data);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    runOnUiThread(() -> { log("录屏授权被系统回收"); stopFly(); });
                }
            }, null);
            startMirror(pendingPkg);
        } else {
            log("❌ 授权取消，飞屏中止");
            pendingPkg = null;
        }
    }

    /** 建 Presentation(SurfaceView) + 虚拟屏直连 Surface，就绪后把 App 拉前台。 */
    private void startMirror(final String pkg) {
        if (cluster == null) refreshCluster();
        if (cluster == null || projection == null) { log("❌ 缺仪表屏或录屏授权"); return; }
        stopPresentationOnly();

        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        final int mainW = dm.widthPixels, mainH = dm.heightPixels, dpi = dm.densityDpi;

        // 保持中控宽高比做 letterbox，避免拉伸变形
        float aspect = (float) mainW / mainH;
        int viewW = ClusterMirrorService.INSTRUMENT_WIDTH;
        int viewH = (int) (viewW / aspect);
        if (viewH > ClusterMirrorService.INSTRUMENT_HEIGHT) {
            viewH = ClusterMirrorService.INSTRUMENT_HEIGHT;
            viewW = (int) (viewH * aspect);
        }
        final int boxW = viewW, boxH = viewH;   // 内部类只能引用 final

        FrameLayout fl = new FrameLayout(this);
        fl.setBackgroundColor(Color.BLACK);
        SurfaceView sv = new SurfaceView(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(boxW, boxH, Gravity.CENTER);
        fl.addView(sv, lp);

        presentation = new ClusterMirrorService.InstrumentPresentation(this, cluster);
        presentation.setContentView(fl);
        sv.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                try {
                    mirrorVd = projection.createVirtualDisplay(
                            "ClusterFly", mainW, mainH, dpi,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                            holder.getSurface(), null, null);
                    log("✅ 通道②镜像: 虚拟屏 " + mainW + "x" + mainH
                            + " → 仪表 Surface (letterbox " + boxW + "x" + boxH + ")");
                    if (pkg != null) launchOnMain(pkg);
                    updateStatus();
                } catch (Throwable t) {
                    log("❌ 建虚拟屏失败: " + t);
                }
            }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {}
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                if (mirrorVd != null) { mirrorVd.release(); mirrorVd = null; }
            }
        });
        try {
            presentation.show();
        } catch (Throwable t) {
            log("❌ Presentation 被拒绝（可能该屏 restricted）: " + t);
            presentation = null;
        }
    }

    private void launchOnMain(String pkg) {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } else {
                log("❌ 找不到启动入口: " + pkg);
            }
        } catch (Throwable t) {
            log("❌ 拉起失败: " + t);
        }
    }

    private void stopPresentationOnly() {
        if (presentation != null) { presentation.dismiss(); presentation = null; }
    }

    private void stopFly() {
        if (mirrorVd != null) { try { mirrorVd.release(); } catch (Exception ignored) {} mirrorVd = null; }
        if (projection != null) { try { projection.stop(); } catch (Exception ignored) {} projection = null; }
        stopPresentationOnly();
        pendingPkg = null;
        log("已停止飞屏");
        updateStatus();
    }

    @Override
    protected void onDestroy() {
        stopFly();
        super.onDestroy();
    }

    // ================= 诊断 =================

    private void dumpDisplays() {
        log("===== 屏幕列表 =====");
        Display[] ds = service.allDisplays();
        log("共 " + ds.length + " 个 Display");
        for (Display d : ds) {
            log("  id=" + d.getDisplayId() + "  " + d.getName()
                    + "  " + d.getMode().getPhysicalWidth() + "x"
                    + d.getMode().getPhysicalHeight()
                    + "  flags=0x" + Integer.toHexString(d.getFlags())
                    + "  state=" + d.getState());
        }
        refreshCluster();
    }

    private void tryPresentation() {
        stopFly();
        refreshCluster();
        if (cluster == null) { log("❌ 无第二屏"); return; }
        LinearLayout v = new LinearLayout(this);
        v.setBackgroundColor(0xFF1565C0);
        TextView tv = new TextView(this);
        tv.setText("Presentation 仪表测试\n" + System.currentTimeMillis());
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(40);
        tv.setGravity(Gravity.CENTER);
        v.addView(tv);
        ClusterMirrorService.InstrumentPresentation p = service.showOnInstrument(cluster, v);
        if (p != null) {
            log("✅ Presentation 已提交，看仪表有没有蓝屏文字");
            presentation = p;   // 纳入统一释放
            updateStatus();
        }
    }
}
