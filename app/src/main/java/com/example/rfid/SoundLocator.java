package com.example.rfid;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

/**
 * Ultra-low latency native audio and haptic locator for RFID shelf hunting.
 * Runs on a dedicated high-priority audio thread.
 */
public final class SoundLocator {

    private static final String TAG = "SoundLocator";

    private final Context context;
    private final Vibrator vibrator;
    private ToneGenerator toneGen;
    private HandlerThread audioThread;
    private Handler audioHandler;

    private volatile boolean running = false;
    private volatile boolean muted = false;
    private volatile float strength = 0f; // 0.0 .. 1.0

    public SoundLocator(Context context) {
        this.context = context.getApplicationContext();
        this.vibrator = (Vibrator) this.context.getSystemService(Context.VIBRATOR_SERVICE);
        try {
            toneGen = new ToneGenerator(AudioManager.STREAM_MUSIC, 85);
        } catch (Throwable t) {
            Log.w(TAG, "ToneGenerator init failed", t);
        }
    }

    public void start() {
        if (running) return;
        running = true;
        audioThread = new HandlerThread("rfid-audio", android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        audioThread.start();
        audioHandler = new Handler(audioThread.getLooper());
        audioHandler.post(tickRunnable);
    }

    public void stop() {
        running = false;
        if (audioHandler != null) {
            audioHandler.removeCallbacksAndMessages(null);
        }
        if (audioThread != null) {
            audioThread.quitSafely();
            audioThread = null;
        }
    }

    public void setStrength(float s) {
        this.strength = Math.max(0f, Math.min(1f, s));
    }

    public void setMuted(boolean m) {
        this.muted = m;
    }

    public boolean isMuted() {
        return muted;
    }

    public void destroy() {
        stop();
        if (toneGen != null) {
            try {
                toneGen.release();
            } catch (Throwable ignored) {}
            toneGen = null;
        }
    }

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) return;

            long nextDelayMs = 150;
            float s = strength;

            if (!muted && s > 0.04f && toneGen != null) {
                if (s >= 0.82f) {
                    // ==========================================
                    // PINPOINT ZONE: 0 to 15 cm
                    // Ultra-fast machine-gun rapid frenzy (15-25 beeps/sec)
                    // ==========================================
                    float p = Math.min(1f, Math.max(0f, (s - 0.82f) / 0.18f));
                    nextDelayMs = (long) (70 - p * 35); // 70ms down to 35ms
                    int durMs = (int) (24 - p * 6);     // 24ms down to 18ms

                    try {
                        toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, durMs);
                    } catch (Throwable ignored) {}

                    // Haptic pulse when right on top (<15cm)
                    if (vibrator != null && vibrator.hasVibrator()) {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                vibrator.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE));
                            } else {
                                vibrator.vibrate(18);
                            }
                        } catch (Throwable ignored) {}
                    }
                } else if (s >= 0.60f) {
                    // Very close: 15cm - 40cm
                    float p = (s - 0.60f) / 0.22f;
                    nextDelayMs = (long) (210 - p * 135); // 210ms down to 75ms
                    try {
                        toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 30);
                    } catch (Throwable ignored) {}
                } else if (s >= 0.30f) {
                    // Mid range: 40cm - 1.5m
                    float p = (s - 0.30f) / 0.30f;
                    nextDelayMs = (long) (520 - p * 310); // 520ms down to 210ms
                    try {
                        toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 40);
                    } catch (Throwable ignored) {}
                } else {
                    // Far: > 1.5m
                    float p = Math.max(0f, s / 0.30f);
                    nextDelayMs = (long) (880 - p * 360); // 880ms down to 520ms
                    try {
                        toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 45);
                    } catch (Throwable ignored) {}
                }
            }

            if (running && audioHandler != null) {
                audioHandler.postDelayed(this, Math.max(30, nextDelayMs));
            }
        }
    };
}
