package id.anhap.camprobe;

import static android.hardware.camera2.CameraCharacteristics.*;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.ImageFormat;
import android.graphics.Typeface;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends Activity {

    interface W { void write(OutputStream os) throws Exception; }

    static final String[] CAPS = {"BACKWARD_COMPATIBLE", "MANUAL_SENSOR", "MANUAL_POST_PROCESSING", "RAW",
            "PRIVATE_REPROCESSING", "READ_SENSOR_SETTINGS", "BURST_CAPTURE", "YUV_REPROCESSING", "DEPTH_OUTPUT",
            "CONSTRAINED_HIGH_SPEED_VIDEO", "MOTION_TRACKING", "LOGICAL_MULTI_CAMERA", "MONOCHROME",
            "SECURE_IMAGE_DATA", "SYSTEM_CAMERA", "OFFLINE_PROCESSING", "ULTRA_HIGH_RESOLUTION_SENSOR",
            "REMOSAIC_REPROCESSING", "DYNAMIC_RANGE_TEN_BIT", "STREAM_USE_CASE", "COLOR_SPACE_PROFILES"};
    static final String[] LEVELS = {"LIMITED", "FULL", "LEGACY", "LEVEL_3", "EXTERNAL"};

    TextView out;
    ScrollView sv;
    final StringBuilder report = new StringBuilder();
    final Handler ui = new Handler(Looper.getMainLooper());
    Handler cam;
    final ExecutorService ex = Executors.newSingleThreadExecutor();
    CameraManager mgr;
    volatile boolean busy = false;
    final List<String> ids = new ArrayList<>();
    final Map<String, String> notes = new HashMap<>();
    final List<String> summary = new ArrayList<>();
    Runnable pending;
    String stamp = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        mgr = (CameraManager) getSystemService(CAMERA_SERVICE);
        HandlerThread ht = new HandlerThread("cam");
        ht.start();
        cam = new Handler(ht.getLooper());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 48, 24, 24);
        TextView t = new TextView(this);
        t.setText("CamProbe - diagnosa Camera2");
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(t);
        root.addView(btn("Jalankan SEMUA tes", v -> runTask(true, true, true)));
        root.addView(btn("1. Info kamera saja", v -> runTask(true, false, false)));
        root.addView(btn("2. Tes foto JPEG + RAW (DNG)", v -> runTask(false, true, false)));
        root.addView(btn("3. Tes high-speed 120 fps", v -> runTask(false, false, true)));
        sv = new ScrollView(this);
        out = new TextView(this);
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextSize(11);
        out.setTextIsSelectable(true);
        out.setText("Tekan 'Jalankan SEMUA tes'. Hasil otomatis disimpan di Download/CamProbe/.\n");
        sv.addView(out);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    Button btn(String text, View.OnClickListener l) {
        Button bt = new Button(this);
        bt.setText(text);
        bt.setAllCaps(false);
        bt.setOnClickListener(l);
        return bt;
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] g) {
        super.onRequestPermissionsResult(rc, p, g);
        if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED && pending != null) {
            Runnable r = pending;
            pending = null;
            r.run();
        } else {
            toast("Izin kamera diperlukan untuk tes 2 dan 3");
        }
    }

    void toast(final String s) {
        ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show());
    }

    // ---------- util log ----------
    void log(final String s) {
        synchronized (report) { report.append(s).append('\n'); }
        ui.post(() -> {
            out.append(s + "\n");
            sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
        });
    }

    void p(String label, Object v) {
        log(String.format(Locale.US, "%-26s %s", label, str(v)));
    }

    static String str(Object o) {
        if (o == null) return "-";
        if (o instanceof int[]) return Arrays.toString((int[]) o);
        if (o instanceof long[]) return Arrays.toString((long[]) o);
        if (o instanceof float[]) return Arrays.toString((float[]) o);
        if (o instanceof double[]) return Arrays.toString((double[]) o);
        if (o instanceof boolean[]) return Arrays.toString((boolean[]) o);
        if (o instanceof byte[]) return Arrays.toString((byte[]) o);
        if (o instanceof Object[]) return Arrays.deepToString((Object[]) o);
        return o.toString();
    }

    static String err(Throwable t) {
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    static String fmtName(int f) {
        switch (f) {
            case 256: return "JPEG";
            case 32: return "RAW_SENSOR";
            case 37: return "RAW10";
            case 38: return "RAW12";
            case 36: return "RAW_PRIVATE";
            case 35: return "YUV_420_888";
            case 34: return "PRIVATE";
            case 17: return "NV21";
            case 20: return "YUY2";
            case 1144402265: return "DEPTH16";
            case 257: return "DEPTH_POINT_CLOUD";
            case 1212500294: return "HEIC";
            case 4101: return "JPEG_R";
            case 54: return "YCBCR_P010";
            default: return "FORMAT_" + f;
        }
    }

    static boolean has(int[] a, int v) {
        if (a == null) return false;
        for (int x : a) if (x == v) return true;
        return false;
    }

    static Size[] desc(Size[] s) {
        Size[] r = s.clone();
        Arrays.sort(r, (a, b) -> Long.compare((long) b.getWidth() * b.getHeight(), (long) a.getWidth() * a.getHeight()));
        return r;
    }

    <T> void k(CameraCharacteristics c, String label, CameraCharacteristics.Key<T> key) {
        try {
            p(label, c.get(key));
        } catch (Throwable t) {
            p(label, "(error " + t.getClass().getSimpleName() + ")");
        }
    }

    String save(String name, String mime, W w) throws Exception {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CamProbe");
        Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (u == null) throw new IOException("insert MediaStore gagal");
        try (OutputStream os = getContentResolver().openOutputStream(u)) {
            w.write(os);
        }
        long size = -1;
        try (Cursor cu = getContentResolver().query(u, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cu != null && cu.moveToFirst()) size = cu.getLong(0);
        }
        return name + " (" + (size / 1024) + " KB)";
    }

    String saveReportNow() throws Exception {
        final String txt;
        synchronized (report) { txt = report.toString(); }
        return save("camprobe_laporan_" + stamp + ".txt", "text/plain", os -> os.write(txt.getBytes("UTF-8")));
    }

    // ---------- runner ----------
    void runTask(final boolean info, final boolean shots, final boolean hs) {
        if (busy) { toast("Masih berjalan, tunggu selesai"); return; }
        if ((shots || hs) && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pending = () -> runTask(info, shots, hs);
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
            return;
        }
        busy = true;
        ex.execute(() -> {
            try {
                synchronized (report) { report.setLength(0); }
                ui.post(() -> out.setText(""));
                stamp = new SimpleDateFormat("MMdd_HHmmss", Locale.US).format(new Date());
                log("CamProbe 1.0 | " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ") | Android "
                        + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
                log("Build: " + Build.DISPLAY);
                buildIds();
                if (info) testInfo();
                if (shots) testShots();
                if (hs) testHighSpeed();
                log("\n== SELESAI ==");
                log("Laporan disimpan: Download/CamProbe/" + saveReportNow());
            } catch (Throwable t) {
                log("ERROR: " + err(t));
                try { saveReportNow(); } catch (Throwable ignored) { }
            } finally {
                busy = false;
            }
        });
    }

    void buildIds() throws Exception {
        ids.clear();
        notes.clear();
        for (String s : mgr.getCameraIdList()) ids.add(s);
        log("ID kamera resmi: " + ids);
        for (int i = 0; i < 10; i++) {
            String s = String.valueOf(i);
            if (ids.contains(s)) continue;
            try {
                mgr.getCameraCharacteristics(s);
                ids.add(s);
                notes.put(s, "(TERSEMBUNYI, tidak ada di daftar resmi)");
            } catch (Throwable ignored) { }
        }
        for (String s : new ArrayList<>(ids)) {
            try {
                Set<String> ph = mgr.getCameraCharacteristics(s).getPhysicalCameraIds();
                for (String q : ph) {
                    if (!ids.contains(q)) {
                        try {
                            mgr.getCameraCharacteristics(q);
                            ids.add(q);
                            notes.put(q, "(kamera fisik dari logical " + s + ")");
                        } catch (Throwable ignored) { }
                    }
                }
                if (!ph.isEmpty()) log("Kamera " + s + " logical, fisik: " + ph);
            } catch (Throwable ignored) { }
        }
        log("Semua ID yang dites: " + ids);
    }

    // ---------- TES 1 ----------
    void testInfo() {
        log("\n##### TES 1: INFO KAMERA #####");
        summary.clear();
        for (String id : ids) {
            try { dumpCamera(id); } catch (Throwable t) { log("Kamera " + id + " gagal dibaca: " + err(t)); }
        }
        log("\n===== RINGKASAN =====");
        for (String s : summary) log(s);
    }

    void dumpCamera(String id) throws Exception {
        CameraCharacteristics c = mgr.getCameraCharacteristics(id);
        log("\n===== KAMERA ID " + id + " " + (notes.containsKey(id) ? notes.get(id) : "") + " =====");
        Integer fc = c.get(LENS_FACING);
        String face = fc == null ? "?" : fc == 0 ? "DEPAN" : fc == 1 ? "BELAKANG" : "EXTERNAL";
        Integer lv = c.get(INFO_SUPPORTED_HARDWARE_LEVEL);
        String lvn = lv == null ? "?" : (lv >= 0 && lv < LEVELS.length ? LEVELS[lv] : "?" + lv);
        p("Arah", face);
        p("Hardware level", lvn);
        int[] caps = c.get(REQUEST_AVAILABLE_CAPABILITIES);
        StringBuilder cs = new StringBuilder();
        if (caps != null) for (int x : caps) cs.append(x >= 0 && x < CAPS.length ? CAPS[x] : "CAP_" + x).append(", ");
        p("Capabilities", cs);
        boolean capRaw = has(caps, 3);
        boolean capHs = has(caps, 9);
        boolean manual = has(caps, 1);

        StreamConfigurationMap m = c.get(SCALER_STREAM_CONFIGURATION_MAP);
        boolean rawMap = false, raw10 = false, raw12 = false;
        String rawSizes = "-";
        String hsInfo = "TIDAK";
        if (m != null) {
            log("--- Format output ---");
            for (int f : m.getOutputFormats()) {
                Size[] s = m.getOutputSizes(f);
                if (s == null || s.length == 0) { p("Format " + fmtName(f), "(tanpa daftar ukuran)"); continue; }
                Size[] d = desc(s);
                long dur = m.getOutputMinFrameDuration(f, d[0]);
                long stall = m.getOutputStallDuration(f, d[0]);
                String fps = dur > 0 ? String.format(Locale.US, "%.1f", 1e9 / dur) : "?";
                StringBuilder sb = new StringBuilder();
                boolean isRaw = f == 32 || f == 37 || f == 38 || f == 36;
                int n = isRaw ? d.length : Math.min(4, d.length);
                for (int i = 0; i < n; i++) sb.append(d[i]).append(' ');
                p("Format " + fmtName(f), s.length + " ukuran, maks " + fps + " fps, stall " + stall / 1000000 + " ms");
                p("   ukuran terbesar", sb);
                if (f == 32) { rawMap = true; rawSizes = sb.toString().trim(); }
                if (f == 37) raw10 = true;
                if (f == 38) raw12 = true;
            }
            log("--- Video (MediaRecorder) ---");
            Size[] vs = m.getOutputSizes(MediaRecorder.class);
            if (vs != null) {
                Size[] d = desc(vs);
                for (int i = 0; i < Math.min(8, d.length); i++) {
                    long dur = m.getOutputMinFrameDuration(MediaRecorder.class, d[i]);
                    p("   " + d[i], dur > 0 ? String.format(Locale.US, "%.1f fps maks", 1e9 / dur) : "?");
                }
            }
            log("--- High-speed (constrained) ---");
            Size[] hs = m.getHighSpeedVideoSizes();
            if (hs == null || hs.length == 0) {
                p("High-speed", "TIDAK ADA");
            } else {
                hsInfo = "YA";
                for (Size s : hs) p("   HS " + s, Arrays.toString(m.getHighSpeedVideoFpsRangesFor(s)));
            }
        } else {
            log("Stream configuration map: null");
        }

        if (Build.VERSION.SDK_INT >= 31) {
            try {
                StreamConfigurationMap mm = c.get(SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION);
                if (mm != null) {
                    log("--- Mode resolusi maksimum (sensor penuh) ---");
                    for (int f : mm.getOutputFormats()) {
                        Size[] s = mm.getOutputSizes(f);
                        if (s != null && s.length > 0) p("MaxRes " + fmtName(f), desc(s)[0]);
                    }
                }
            } catch (Throwable t) { p("MaxRes", "(error " + t.getClass().getSimpleName() + ")"); }
        }

        log("--- Sensor, lensa, kontrol ---");
        k(c, "Pixel array", SENSOR_INFO_PIXEL_ARRAY_SIZE);
        k(c, "Active array", SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        k(c, "Ukuran fisik sensor mm", SENSOR_INFO_PHYSICAL_SIZE);
        k(c, "Orientasi sensor", SENSOR_ORIENTATION);
        k(c, "Filter warna (CFA)", SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
        k(c, "ISO range", SENSOR_INFO_SENSITIVITY_RANGE);
        k(c, "ISO analog maks", SENSOR_MAX_ANALOG_SENSITIVITY);
        k(c, "Eksposur ns range", SENSOR_INFO_EXPOSURE_TIME_RANGE);
        k(c, "Frame duration maks ns", SENSOR_INFO_MAX_FRAME_DURATION);
        k(c, "White level", SENSOR_INFO_WHITE_LEVEL);
        k(c, "Black level", SENSOR_BLACK_LEVEL_PATTERN);
        k(c, "Timestamp source", SENSOR_INFO_TIMESTAMP_SOURCE);
        k(c, "Lens shading applied", SENSOR_INFO_LENS_SHADING_APPLIED);
        k(c, "Test pattern", SENSOR_AVAILABLE_TEST_PATTERN_MODES);
        k(c, "Ref illuminant 1", SENSOR_REFERENCE_ILLUMINANT1);
        k(c, "Color transform 1", SENSOR_COLOR_TRANSFORM1);
        k(c, "Forward matrix 1", SENSOR_FORWARD_MATRIX1);
        k(c, "Focal length mm", LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        k(c, "Aperture f", LENS_INFO_AVAILABLE_APERTURES);
        k(c, "OIS", LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        k(c, "Fokus terdekat (dioptri)", LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        k(c, "Hyperfocal (dioptri)", LENS_INFO_HYPERFOCAL_DISTANCE);
        k(c, "Kalibrasi fokus", LENS_INFO_FOCUS_DISTANCE_CALIBRATION);
        k(c, "Flash", FLASH_INFO_AVAILABLE);
        k(c, "AE modes", CONTROL_AE_AVAILABLE_MODES);
        k(c, "AE target FPS", CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        k(c, "AE comp range", CONTROL_AE_COMPENSATION_RANGE);
        k(c, "AF modes", CONTROL_AF_AVAILABLE_MODES);
        k(c, "AWB modes", CONTROL_AWB_AVAILABLE_MODES);
        k(c, "Scene modes", CONTROL_AVAILABLE_SCENE_MODES);
        k(c, "Stabilisasi video", CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
        k(c, "Boost ISO pasca-RAW", CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE);
        k(c, "Noise reduction modes", NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES);
        k(c, "Edge modes", EDGE_AVAILABLE_EDGE_MODES);
        k(c, "Tonemap modes", TONEMAP_AVAILABLE_TONE_MAP_MODES);
        k(c, "Lens shading modes", SHADING_AVAILABLE_MODES);
        k(c, "Hot pixel modes", HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES);
        k(c, "Face detect modes", STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);
        k(c, "Zoom digital maks", SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        k(c, "Maks stream RAW", REQUEST_MAX_NUM_OUTPUT_RAW);
        k(c, "Maks stream proc", REQUEST_MAX_NUM_OUTPUT_PROC);
        k(c, "Maks stream stalling", REQUEST_MAX_NUM_OUTPUT_PROC_STALLING);
        k(c, "Pipeline depth maks", REQUEST_PIPELINE_MAX_DEPTH);
        k(c, "Sync max latency", SYNC_MAX_LATENCY);
        if (Build.VERSION.SDK_INT >= 30) k(c, "Zoom ratio range", CONTROL_ZOOM_RATIO_RANGE);
        if (Build.VERSION.SDK_INT >= 33) k(c, "Stream use cases", SCALER_AVAILABLE_STREAM_USE_CASES);

        try {
            StringBuilder vk = new StringBuilder();
            for (CameraCharacteristics.Key<?> kk : c.getKeys())
                if (!kk.getName().startsWith("android.")) vk.append(kk.getName()).append(", ");
            p("Key vendor (karakteristik)", vk.length() == 0 ? "(tidak ada)" : vk);
            StringBuilder rk = new StringBuilder();
            for (CaptureRequest.Key<?> kk : c.getAvailableCaptureRequestKeys())
                if (!kk.getName().startsWith("android.")) rk.append(kk.getName()).append(", ");
            p("Key vendor (request)", rk.length() == 0 ? "(tidak ada)" : rk);
            StringBuilder sk = new StringBuilder();
            for (CaptureResult.Key<?> kk : c.getAvailableCaptureResultKeys())
                if (!kk.getName().startsWith("android.")) sk.append(kk.getName()).append(", ");
            p("Key vendor (result)", sk.length() == 0 ? "(tidak ada)" : sk);
        } catch (Throwable t) { p("Key vendor", "(error " + t.getClass().getSimpleName() + ")"); }

        log("--- KESIMPULAN kamera " + id + " ---");
        p("RAW capability", capRaw ? "YA" : "TIDAK");
        p("RAW_SENSOR di stream map", rawMap ? "YA " + rawSizes : "TIDAK");
        p("RAW10 / RAW12", raw10 + " / " + raw12);
        p("Manual sensor", manual ? "YA" : "TIDAK");
        p("High-speed", hsInfo + (capHs ? " (capability ada)" : " (capability tidak ada)"));
        summary.add("ID " + id + " " + face + " " + lvn + " | RAW cap=" + capRaw + " map=" + rawMap
                + " | HS=" + hsInfo + " | manual=" + manual + " " + (notes.containsKey(id) ? notes.get(id) : ""));
    }

    // ---------- helper kamera ----------
    CameraDevice openCam(String id) throws Exception {
        final CountDownLatch l = new CountDownLatch(1);
        final CameraDevice[] dev = new CameraDevice[1];
        final int[] er = {0};
        mgr.openCamera(id, new CameraDevice.StateCallback() {
            @Override public void onOpened(CameraDevice d) { dev[0] = d; l.countDown(); }
            @Override public void onDisconnected(CameraDevice d) { d.close(); l.countDown(); }
            @Override public void onError(CameraDevice d, int e) { er[0] = e; d.close(); l.countDown(); }
        }, cam);
        if (!l.await(10, TimeUnit.SECONDS) || dev[0] == null) throw new Exception("gagal buka kamera " + id + " (kode " + er[0] + ")");
        return dev[0];
    }

    CameraCaptureSession mkSession(CameraDevice d, List<Surface> s) throws Exception {
        final CountDownLatch l = new CountDownLatch(1);
        final CameraCaptureSession[] r = new CameraCaptureSession[1];
        d.createCaptureSession(s, new CameraCaptureSession.StateCallback() {
            @Override public void onConfigured(CameraCaptureSession ss) { r[0] = ss; l.countDown(); }
            @Override public void onConfigureFailed(CameraCaptureSession ss) { l.countDown(); }
        }, cam);
        if (!l.await(10, TimeUnit.SECONDS) || r[0] == null) throw new Exception("konfigurasi sesi gagal");
        return r[0];
    }

    // ---------- TES 2 ----------
    void testShots() {
        log("\n##### TES 2: FOTO JPEG + RAW #####");
        for (String id : ids) {
            try {
                CameraCharacteristics c = mgr.getCameraCharacteristics(id);
                StreamConfigurationMap m = c.get(SCALER_STREAM_CONFIGURATION_MAP);
                if (m == null) { log("Kamera " + id + ": tidak ada stream map"); continue; }
                Size[] js = m.getOutputSizes(ImageFormat.JPEG);
                if (js != null && js.length > 0) shotTest(id, c, ImageFormat.JPEG, desc(js)[0]);
                Size[] rs = m.getOutputSizes(ImageFormat.RAW_SENSOR);
                if (rs != null && rs.length > 0) shotTest(id, c, ImageFormat.RAW_SENSOR, desc(rs)[0]);
                else log("[RAW] kamera " + id + ": RAW_SENSOR tidak tersedia, tes DNG dilewati");
            } catch (Throwable t) {
                log("Kamera " + id + " gagal: " + err(t));
            }
        }
    }

    void shotTest(String id, CameraCharacteristics c, int fmt, Size sz) {
        String tag = fmt == ImageFormat.JPEG ? "JPEG" : "RAW";
        CameraDevice dev = null;
        ImageReader rd = null;
        CameraCaptureSession ss = null;
        Image kept = null;
        try {
            log("[" + tag + "] kamera " + id + " ukuran " + sz);
            dev = openCam(id);
            rd = ImageReader.newInstance(sz.getWidth(), sz.getHeight(), fmt, 3);
            final AtomicReference<Image> ref = new AtomicReference<>();
            final AtomicBoolean keep = new AtomicBoolean(false);
            rd.setOnImageAvailableListener(r -> {
                Image im;
                try { im = r.acquireLatestImage(); } catch (Exception e) { return; }
                if (im == null) return;
                if (keep.get() && ref.get() == null) ref.set(im); else im.close();
            }, cam);
            ss = mkSession(dev, Arrays.asList(rd.getSurface()));
            final Map<Long, TotalCaptureResult> res = new ConcurrentHashMap<>();
            CaptureRequest.Builder b = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(rd.getSurface());
            if (has(c.get(CONTROL_AF_AVAILABLE_MODES), CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE))
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            ss.setRepeatingRequest(b.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest rq, TotalCaptureResult r) {
                    Long ts = r.get(CaptureResult.SENSOR_TIMESTAMP);
                    if (ts != null) res.put(ts, r);
                }
            }, cam);
            Thread.sleep(1500);
            keep.set(true);
            long w = 0;
            while (ref.get() == null && w < 5000) { Thread.sleep(50); w += 50; }
            kept = ref.get();
            if (kept == null) throw new Exception("tidak ada frame diterima");
            try { ss.stopRepeating(); } catch (Throwable ignored) { }
            long ts = kept.getTimestamp();
            TotalCaptureResult tr = res.get(ts);
            w = 0;
            while (tr == null && w < 3000) { Thread.sleep(50); w += 50; tr = res.get(ts); }
            log("  frame OK: " + kept.getWidth() + "x" + kept.getHeight() + " format " + fmtName(kept.getFormat()));
            if (tr != null) {
                p("  ISO", tr.get(CaptureResult.SENSOR_SENSITIVITY));
                p("  Eksposur ns", tr.get(CaptureResult.SENSOR_EXPOSURE_TIME));
                p("  Frame duration ns", tr.get(CaptureResult.SENSOR_FRAME_DURATION));
                p("  Jarak fokus (dioptri)", tr.get(CaptureResult.LENS_FOCUS_DISTANCE));
                p("  AE/AF state", tr.get(CaptureResult.CONTROL_AE_STATE) + " / " + tr.get(CaptureResult.CONTROL_AF_STATE));
            } else {
                log("  (CaptureResult tidak cocok dengan frame)");
            }
            String base = "camprobe_cam" + id + "_" + stamp;
            if (fmt == ImageFormat.JPEG) {
                ByteBuffer bb = kept.getPlanes()[0].getBuffer();
                final byte[] by = new byte[bb.remaining()];
                bb.get(by);
                log("  tersimpan: " + save(base + ".jpg", "image/jpeg", os -> os.write(by)));
            } else {
                if (tr == null) throw new Exception("DNG butuh CaptureResult, tidak ada");
                final Image ki = kept;
                DngCreator dng = new DngCreator(c, tr);
                try {
                    log("  tersimpan: " + save(base + ".dng", "image/x-adobe-dng", os -> dng.writeImage(os, ki)));
                } finally {
                    dng.close();
                }
                log("  >>> DNG BERHASIL DIBUAT, RAW DIDUKUNG <<<");
            }
        } catch (Throwable t) {
            log("  GAGAL: " + err(t));
            Log.e("CamProbe", "shotTest", t);
        } finally {
            if (kept != null) kept.close();
            if (ss != null) ss.close();
            if (dev != null) dev.close();
            if (rd != null) rd.close();
        }
    }

    // ---------- TES 3 ----------
    static class Combo {
        Size s;
        Range<Integer> r;
        Combo(Size s, Range<Integer> r) { this.s = s; this.r = r; }
    }

    void testHighSpeed() {
        log("\n##### TES 3: HIGH-SPEED #####");
        for (String id : ids) {
            try {
                CameraCharacteristics c = mgr.getCameraCharacteristics(id);
                StreamConfigurationMap m = c.get(SCALER_STREAM_CONFIGURATION_MAP);
                Size[] hs = m == null ? null : m.getHighSpeedVideoSizes();
                if (hs == null || hs.length == 0) { log("Kamera " + id + ": tidak ada mode high-speed"); continue; }
                List<Combo> combos = new ArrayList<>();
                for (Size s : hs)
                    for (Range<Integer> r : m.getHighSpeedVideoFpsRangesFor(s))
                        if (r.getUpper() >= 60) combos.add(new Combo(s, r));
                Collections.sort(combos, (a, b) -> {
                    if (!a.r.getUpper().equals(b.r.getUpper())) return b.r.getUpper() - a.r.getUpper();
                    return Long.compare((long) a.s.getWidth() * a.s.getHeight(), (long) b.s.getWidth() * b.s.getHeight());
                });
                boolean saved = false;
                int n = Math.min(10, combos.size());
                for (int i = 0; i < n; i++) {
                    boolean ok = hsTest(id, combos.get(i), !saved);
                    if (ok) saved = true;
                }
            } catch (Throwable t) {
                log("Kamera " + id + " gagal: " + err(t));
            }
        }
    }

    boolean hsTest(String id, Combo cb, boolean saveFile) {
        final int w = cb.s.getWidth(), h = cb.s.getHeight(), fps = cb.r.getUpper();
        MediaRecorder mr = null;
        CameraDevice dev = null;
        CameraConstrainedHighSpeedCaptureSession hss = null;
        File f = new File(getCacheDir(), "hs_" + id + "_" + w + "x" + h + "_" + fps + ".mp4");
        boolean success = false;
        try {
            log("[HS] kamera " + id + " " + w + "x" + h + " range " + cb.r);
            mr = new MediaRecorder();
            mr.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mr.setOutputFile(f.getAbsolutePath());
            mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mr.setVideoSize(w, h);
            mr.setVideoFrameRate(fps);
            mr.setVideoEncodingBitRate((int) Math.min(60000000L, (long) w * h * fps / 8));
            mr.prepare();
            Surface rs = mr.getSurface();
            dev = openCam(id);
            final CountDownLatch l = new CountDownLatch(1);
            final CameraConstrainedHighSpeedCaptureSession[] holder = new CameraConstrainedHighSpeedCaptureSession[1];
            dev.createConstrainedHighSpeedCaptureSession(Arrays.asList(rs), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    holder[0] = (CameraConstrainedHighSpeedCaptureSession) s;
                    l.countDown();
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) { l.countDown(); }
            }, cam);
            if (!l.await(10, TimeUnit.SECONDS) || holder[0] == null) throw new Exception("sesi high-speed gagal dikonfigurasi");
            hss = holder[0];
            CaptureRequest.Builder b = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, cb.r);
            b.addTarget(rs);
            List<CaptureRequest> burst = hss.createHighSpeedRequestList(b.build());
            final List<Long> stamps = Collections.synchronizedList(new ArrayList<Long>());
            hss.setRepeatingBurst(burst, new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest rq, TotalCaptureResult r) {
                    Long ts = r.get(CaptureResult.SENSOR_TIMESTAMP);
                    if (ts != null) stamps.add(ts);
                }
            }, cam);
            mr.start();
            Thread.sleep(3000);
            try { hss.stopRepeating(); } catch (Throwable ignored) { }
            mr.stop();
            mr.release();
            mr = null;

            List<Long> st;
            synchronized (stamps) { st = new ArrayList<>(stamps); }
            if (st.size() >= 2) {
                double span = (st.get(st.size() - 1) - st.get(0)) / 1e9;
                p("  fps dari timestamp", String.format(Locale.US, "%.1f (%d frame / %.2f s)", (st.size() - 1) / span, st.size(), span));
            } else {
                p("  fps dari timestamp", "tidak cukup data (" + st.size() + " callback)");
            }
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(f.getAbsolutePath());
                String dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                String fcnt = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT);
                String cfr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
                double fileFps = -1;
                if (dur != null && fcnt != null) fileFps = Double.parseDouble(fcnt) / (Double.parseDouble(dur) / 1000.0);
                p("  file video", f.length() / 1024 + " KB, durasi " + dur + " ms, frame " + fcnt + ", fps file "
                        + String.format(Locale.US, "%.1f", fileFps) + ", capture fps meta " + cfr);
                success = fileFps > 0;
            } finally {
                mmr.release();
            }
            if (success && saveFile) {
                final File src = f;
                log("  tersimpan: " + save("camprobe_hs_cam" + id + "_" + w + "x" + h + "_" + fps + "fps_" + stamp + ".mp4",
                        "video/mp4", os -> {
                            try (FileInputStream in = new FileInputStream(src)) {
                                byte[] buf = new byte[65536];
                                int r;
                                while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
                            }
                        }));
            }
        } catch (Throwable t) {
            log("  GAGAL: " + err(t));
            Log.e("CamProbe", "hsTest", t);
        } finally {
            try { if (hss != null) hss.close(); } catch (Throwable ignored) { }
            try { if (dev != null) dev.close(); } catch (Throwable ignored) { }
            try { if (mr != null) mr.release(); } catch (Throwable ignored) { }
            f.delete();
        }
        return success;
    }
}
