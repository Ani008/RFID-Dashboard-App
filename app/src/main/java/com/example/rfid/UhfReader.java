package com.example.rfid;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import com.seuic.scankey.IKeyEventCallback;
import com.seuic.scankey.ScanKeyService;
import com.seuic.uhf.EPC;
import com.seuic.uhf.UHFService;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Everything that touches the SEUIC UHF SDK lives here.
 *
 * All SDK calls run on one background thread ("uhf"), so the UI never blocks on the radio and the
 * SDK is never called from two threads at once. Results are delivered to {@link Listener} on the
 * main thread.
 *
 * The trigger buttons are handled here too: hold = read, release = stop.
 */
public final class UhfReader {

    private static final String TAG = "UhfReader";

    /** Vendor UHF app that can hold the module open. */
    private static final String VENDOR_APP = "com.seuic.uhftool";

    /** Hardware trigger key codes (from the vendor demo: 100, 101, 102, 249, 250). */
    private static final String TRIGGER_KEYS = "100,101,102,249,250";

    /** Ultra-fast native SDK tag polling (~40 Hz for sub-30ms instant hardware identification). */
    private static final long POLL_MS = 25;

    /** One tag as reported by the SDK. rssi is the raw SDK value (unit still to be confirmed). */
    public static final class Tag {
        public final String epc;
        public final int rssi;
        public final int count;

        Tag(String epc, int rssi, int count) {
            this.epc = epc;
            this.rssi = rssi;
            this.count = count;
        }
    }

    public interface Listener {
        /** Every poll while reading: all tags seen since the trigger was pressed. Main thread. */
        void onTags(List<Tag> tags);

        /** Reader availability or reading state changed. Main thread. */
        void onState(boolean available, boolean reading, String message);
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("uhf");
    private final Handler worker;

    // Touched only on the worker thread.
    private UHFService device;
    private boolean open;
    private boolean reading;
    private volatile int power = 30; // default output power (dBm)

    private ScanKeyService scanKeys;
    // Created lazily inside try/catch: if the SDK classes are only stubs (or missing) on this
    // device, constructing IKeyEventCallback.Stub throws, and that must not crash the app.
    private IKeyEventCallback keyCallback;

    public UhfReader(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    // ---- lifecycle (call from Activity onResume / onPause / onDestroy) ----

    /** Opens the reader and starts listening to the trigger. */
    public void resume() {
        worker.post(this::openInternal);
        try {
            if (keyCallback == null) {
                keyCallback = new IKeyEventCallback.Stub() {
                    @Override
                    public void onKeyDown(int keyCode) {
                        startReading();
                    }

                    @Override
                    public void onKeyUp(int keyCode) {
                        stopReading();
                    }
                };
            }
            scanKeys = ScanKeyService.getInstance();
            scanKeys.registerCallback(keyCallback, TRIGGER_KEYS);
        } catch (Throwable t) {
            // Includes RuntimeException("Stub!") and NoClassDefFoundError.
            Log.w(TAG, "Trigger key service not available", t);
        }
    }

    /** Stops reading and hands the module back (so the vendor app can use it while we are hidden). */
    public void pause() {
        try {
            if (scanKeys != null && keyCallback != null) {
                scanKeys.unregisterCallback(keyCallback);
            }
        } catch (Throwable t) {
            Log.w(TAG, "unregisterCallback failed", t);
        }
        worker.post(() -> {
            stopInternal();
            closeInternal();
        });
    }

    public void destroy() {
        worker.post(() -> {
            stopInternal();
            closeInternal();
            thread.quitSafely();
        });
    }

    // ---- reading ----

    public int getPower() {
        return power;
    }

    public void startReading() {
        worker.post(() -> {
            if (reading) return;
            if (!open) openInternal();
            if (!open) return;
            try {
                int p = device.getPower();
                if (p > 0) power = p;
            } catch (Throwable ignored) {}
            try {
                if (device.inventoryStart()) {
                    reading = true;
                    publishState("Reading");
                    worker.postDelayed(poll, POLL_MS);
                } else {
                    publishState("Could not start reading");
                }
            } catch (Throwable t) {
                Log.e(TAG, "inventoryStart failed", t);
                publishState("Reader error: " + t.getMessage());
            }
        });
    }

    public void stopReading() {
        worker.post(() -> {
            stopInternal();
            publishState(open ? "Ready" : "Reader not available");
        });
    }

    public void toggleReading() {
        worker.post(() -> {
            if (reading) {
                stopInternal();
                publishState("Ready");
            } else {
                startReading();
            }
        });
    }

    // ---- worker-thread internals ----

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!reading) return;
            try {
                List<EPC> raw = device.getTagIDs();
                if (raw != null) {
                    // Copy first: the SDK may update this list while we iterate.
                    List<EPC> copy = new ArrayList<>(raw);
                    final List<Tag> tags = new ArrayList<>(copy.size());
                    for (EPC e : copy) {
                        String id = e == null ? null : e.getId();
                        if (id != null && !id.isEmpty()) {
                            tags.add(new Tag(id, e.rssi, e.count));
                        }
                    }
                    main.post(() -> listener.onTags(Collections.unmodifiableList(tags)));
                }
            } catch (Throwable t) {
                // Usually a concurrent modification of the SDK list. Skip this tick, try the next.
                Log.w(TAG, "poll failed", t);
            }
            worker.postDelayed(this, POLL_MS);
        }
    };

    private void openInternal() {
        if (open) return;
        try {
            device = UHFService.getInstance();
            open = device.open();
            if (!open) {
                // The vendor UHF app may be holding the module. Ask Android to stop it, then retry once.
                releaseFromVendorApp();
                open = device.open();
            }
            if (open) {
                // Start every trigger press with an empty list, so counts mean "this scan".
                device.setParameters(UHFService.PARAMETER_CLEAR_EPCLIST_WHEN_START_INVENTORY, 1);
                try {
                    int p = device.getPower();
                    if (p > 0) power = p;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            // Includes NoClassDefFoundError on a device that is not a SEUIC reader.
            Log.e(TAG, "open failed", t);
            open = false;
        }
        publishState(open ? "Ready"
                : "Reader not available. Close the vendor UHF app and reopen this app.");
    }

    private void stopInternal() {
        worker.removeCallbacks(poll);
        if (!reading) return;
        reading = false;
        try {
            device.inventoryStop();
        } catch (Throwable t) {
            Log.w(TAG, "inventoryStop failed", t);
        }
    }

    private void closeInternal() {
        if (!open) return;
        try {
            device.close();
        } catch (Throwable t) {
            Log.w(TAG, "close failed", t);
        }
        open = false;
    }

    private void publishState(String message) {
        final boolean a = open;
        final boolean r = reading;
        main.post(() -> listener.onState(a, r, message));
    }

    /**
     * Best effort. forceStopPackage needs a system permission and is a hidden API, so on a normal
     * install it usually fails; killBackgroundProcesses is the fallback. Failures are only logged.
     */
    private void releaseFromVendorApp() {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return;
        try {
            Method m = am.getClass().getDeclaredMethod("forceStopPackage", String.class);
            m.setAccessible(true);
            m.invoke(am, VENDOR_APP);
            Log.i(TAG, "forceStopPackage(" + VENDOR_APP + ") ok");
            return;
        } catch (Throwable t) {
            Log.i(TAG, "forceStopPackage not allowed: " + t);
        }
        try {
            am.killBackgroundProcesses(VENDOR_APP);
            Log.i(TAG, "killBackgroundProcesses(" + VENDOR_APP + ") called");
        } catch (Throwable t) {
            Log.i(TAG, "killBackgroundProcesses failed: " + t);
        }
    }
}