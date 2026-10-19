package dev.allofus.fusioncore.repairip.bridge;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class PairipPatchRunner {

    public interface Listener {
        void onStart(String label);
        void onLog(String line);
        void onSuccess(File outputApk);
        void onError(Throwable t);
    }

    private static final ExecutorService EXEC =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "pairip-patch");
                t.setDaemon(true);
                return t;
            });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);

    private PairipPatchRunner() {}

    public static boolean isRunning() {
        return BUSY.get();
    }

    public static void patch(Context appContext, String packageName,
                             String label, Listener listener) {
        if (!BUSY.compareAndSet(false, true)) {
            MAIN.post(() -> listener.onError(
                    new IllegalStateException("Another patch is already running")));
            return;
        }

        MAIN.post(() -> listener.onStart(label));
        final Context ctx = appContext.getApplicationContext();

        EXEC.execute(() -> {
            PrintStream oldOut = System.out;
            PrintStream oldErr = System.err;
            LineCollector collector = new LineCollector(
                    line -> MAIN.post(() -> listener.onLog(line)));

            try {
                System.setOut(new PrintStream(collector, true));
                System.setErr(new PrintStream(collector, true));

                File workDir = new File(ctx.getExternalFilesDir(null),
                        "pairip/" + packageName);
                if (!workDir.exists() && !workDir.mkdirs()) {
                    throw new IOException("Cannot create work dir: " + workDir);
                }

                File apksFile = buildApks(ctx, packageName, workDir);

                dev.allofus.fusioncore.repairip.Main.main(
                        new String[]{"-i", apksFile.getAbsolutePath()});

                File out = findOutput(workDir);
                if (out == null || !out.exists()) {
                    throw new IOException("Patcher finished but no output APK was produced");
                }
                MAIN.post(() -> listener.onSuccess(out));
            } catch (Throwable t) {
                MAIN.post(() -> listener.onError(t));
            } finally {
                System.setOut(oldOut);
                System.setErr(oldErr);
                BUSY.set(false);
            }
        });
    }

    private static File buildApks(Context ctx, String packageName, File workDir)
            throws PackageManager.NameNotFoundException, IOException {
        PackageManager pm = ctx.getPackageManager();
        ApplicationInfo info = pm.getApplicationInfo(packageName, 0);

        File staging = new File(workDir, "staging");
        deleteTree(staging);
        if (!staging.mkdirs()) {
            throw new IOException("Cannot create staging dir: " + staging);
        }

        copy(new File(info.sourceDir), new File(staging, "base.apk"));

        if (info.splitSourceDirs != null) {
            String[] names = info.splitNames != null
                    ? info.splitNames
                    : new String[info.splitSourceDirs.length];
            for (int i = 0; i < info.splitSourceDirs.length; i++) {
                String name = (i < names.length && names[i] != null)
                        ? names[i] + ".apk"
                        : "split_" + i + ".apk";
                copy(new File(info.splitSourceDirs[i]), new File(staging, name));
            }
        }

        File zipOut = new File(workDir, "pairip_input.apks");
        zip(staging, zipOut);
        deleteTree(staging);
        return zipOut;
    }

    private static File findOutput(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith("_pairip.apk")) return f;
        }
        return null;
    }

    private static void copy(File src, File dst) throws IOException {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static void zip(File dir, File out) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            File[] files = dir.listFiles();
            if (files == null) throw new IOException("Empty staging dir");
            byte[] buf = new byte[64 * 1024];
            for (File f : files) {
                if (!f.isFile()) continue;
                try (FileInputStream in = new FileInputStream(f)) {
                    zos.putNextEntry(new ZipEntry(f.getName()));
                    int n;
                    while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
                    zos.closeEntry();
                }
            }
        }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteTree(c);
        }
        f.delete();
    }

    private static final class LineCollector extends OutputStream {
        private final Consumer<String> sink;
        private final StringBuilder buf = new StringBuilder();
        LineCollector(Consumer<String> sink) { this.sink = sink; }
        @Override public void write(int b) {
            char c = (char) b;
            if (c == '\n') {
                if (buf.length() > 0) { sink.accept(buf.toString()); buf.setLength(0); }
            } else if (c != '\r') {
                buf.append(c);
            }
        }
    }
}
