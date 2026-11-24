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
    private volatile float strength = 0f; // 0.0 .. 1.0 (beep speed)
    private volatile boolean vibrate = false; // buzz together with the beeps

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

    public void setVibrate(boolean v) {
        this.vibrate = v;
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
                // One smooth curve, no steps: s = 1 (15 cm or closer) beeps about every 45 ms;
                // s near 0 (far) about every 900 ms; in between it speeds up gradually.
                nextDelayMs = (long) (45 + 850 * Math.pow(1.0 - s, 1.6));
                try {
                    if (s >= 0.9f) {
                        toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 20);
                    } else if (s >= 0.5f) {
                        toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 30);
                    } else if (s >= 0.3f) {
                        toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 40);
                    } else {
                        toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 45);
                    }
                } catch (Throwable ignored) {}

                // Second confirmation: buzz together with the beep when you are on top of the file.
                if (vibrate && vibrator != null && vibrator.hasVibrator()) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE));
                        } else {
                            vibrator.vibrate(30);
                        }
                    } catch (Throwable ignored) {}
                }
            }

            if (running && audioHandler != null) {
                audioHandler.postDelayed(this, Math.max(30, nextDelayMs));
            }
        }
    };
}
