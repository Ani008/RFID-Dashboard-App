package com.example.rfid;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;
import java.util.Locale;

/**
 * 60 FPS Native RFID Hardware Radar Activity.
 * Interacts directly with the SEUIC UHF radio and compass sensors in native memory.
 */
public class RadarActivity extends AppCompatActivity implements UhfReader.Listener {

    private String targetEpc = "";
    private String fileId = "";
    private String fileName = "";
    private String caseId = "";
    private String storageLocation = "";

    private UhfReader reader;
    private SoundLocator soundLocator;
    private SensorManager sensorManager;
    private Sensor rotationSensor;

    private RadarView radarView;
    private TextView tvFileTitle;
    private TextView tvFileId;
    private TextView tvCaseId;
    private TextView tvStorageLocation;
    private TextView tvGuidance;
    private TextView tvSignalReadout;
    private ProgressBar pbSignal;
    private Button btnMute;
    private TextView tvPower;
    private TextView tvDebug;
    private boolean debugVisible = false;

    /** Transmit power used on the radar screen (dBm). Lower = more accurate direction. */
    private static final String PREFS = "rfid";
    private static final String KEY_RADAR_POWER = "radar_power_dbm";
    private static final int DEFAULT_POWER_DBM = 20;
    private static final int MIN_POWER_DBM = 5;

    /**
     * Optional fixed compass correction in degrees. Leave at 0f. Only if the diagnostic line
     * shows a steady error that does not change with how the reader is held, put that error
     * here (for example 90f or -90f).
     */
    private static final float COMPASS_OFFSET_DEG = 0f;

    private float currentHeading = 0f;

    // The tag is stationary. Its bearing and peak strength come from the estimator and are only
    // changed by FRESH reads; they never follow the reader's heading.
    private final TagBearingEstimator estimator = new TagBearingEstimator();
    private float targetBearing = 0f;   // estimated bearing of the tag (fixed in the room)
    private float peakStrength = 0f;    // 0..1 from the strongest signal seen: places the dot
    private float liveStrength = 0f;    // 0..1 right now: drives the signal bar and the sound
    private int lastTargetCount = -1;   // SDK read counter of the target at the previous poll
    private boolean triggerHeld = false;
    private boolean hasSeenTarget = false;
    private int totalTargetReads = 0;
    private int lastRssi = -999;
    private float estDistanceMeters = -1f;

    private final float[] rotationMatrix = new float[9];
    private final float[] orientationAngles = new float[3];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_radar);

        // Retrieve extras
        targetEpc = normalizeTag(getIntent().getStringExtra("rfidTag"));
        fileId = getIntent().getStringExtra("fileId");
        fileName = getIntent().getStringExtra("fileName");
        caseId = getIntent().getStringExtra("caseId");
        storageLocation = getIntent().getStringExtra("storageLocation");

        if (fileId == null) fileId = "";
        if (fileName == null || fileName.isEmpty()) fileName = "File name not recorded";
        if (caseId == null || caseId.isEmpty()) caseId = "Not recorded";
        if (storageLocation == null || storageLocation.isEmpty()) storageLocation = "Not recorded";

        radarView = findViewById(R.id.radar_view);
        tvFileTitle = findViewById(R.id.tv_file_title);
        tvFileId = findViewById(R.id.tv_file_id);
        tvCaseId = findViewById(R.id.tv_case_id);
        tvStorageLocation = findViewById(R.id.tv_storage_location);
        tvGuidance = findViewById(R.id.tv_guidance);
        tvSignalReadout = findViewById(R.id.tv_signal_readout);
        pbSignal = findViewById(R.id.pb_signal);
        btnMute = findViewById(R.id.btn_mute);

        tvFileTitle.setText(fileName);
        tvFileId.setText("File ID: " + fileId);
        tvCaseId.setText("Case: " + caseId);
        tvStorageLocation.setText("Location: " + storageLocation);

        if (targetEpc.isEmpty()) {
            tvGuidance.setText("NO RFID TAG IS REGISTERED FOR THIS FILE");
            tvGuidance.setBackgroundColor(Color.parseColor("#DC2626"));
            tvSignalReadout.setText("Register a tag for this file on the dashboard");
        }

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        btnMute.setOnClickListener(v -> {
            boolean nextMuted = !soundLocator.isMuted();
            soundLocator.setMuted(nextMuted);
            btnMute.setText(nextMuted ? "Audio Off" : "Audio On");
        });

        reader = new UhfReader(this, this);
        soundLocator = new SoundLocator(this);

        tvPower = findViewById(R.id.tv_power);
        tvDebug = findViewById(R.id.tv_debug);
        SeekBar sbPower = findViewById(R.id.sb_power);
        final SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int savedPower = prefs.getInt(KEY_RADAR_POWER, DEFAULT_POWER_DBM);
        savedPower = Math.max(MIN_POWER_DBM, Math.min(30, savedPower));
        sbPower.setProgress(savedPower - MIN_POWER_DBM);
        tvPower.setText("Reader power: " + savedPower + " dBm");
        reader.setPowerLevel(savedPower);
        sbPower.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int dbm = progress + MIN_POWER_DBM;
                tvPower.setText("Reader power: " + dbm + " dBm");
                if (fromUser) {
                    reader.setPowerLevel(dbm);
                    prefs.edit().putInt(KEY_RADAR_POWER, dbm).apply();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {}

            @Override
            public void onStopTrackingTouch(SeekBar bar) {}
        });

        // Tap the title to show or hide the diagnostic line.
        findViewById(R.id.tv_title).setOnClickListener(v -> {
            debugVisible = !debugVisible;
            tvDebug.setVisibility(debugVisible ? View.VISIBLE : View.GONE);
            renderUI();
        });

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            if (rotationSensor == null) {
                rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        reader.resume();
        soundLocator.start();
        if (sensorManager != null && rotationSensor != null) {
            sensorManager.registerListener(sensorListener, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override
    protected void onPause() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(sensorListener);
        }
        soundLocator.stop();
        reader.pause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        soundLocator.destroy();
        reader.destroy();
        super.onDestroy();
    }

    // ---- UHF Hardware Reads (Main Thread) ----

    @Override
    public void onTags(List<UhfReader.Tag> tags) {
        if (tags == null || targetEpc.isEmpty()) return;

        UhfReader.Tag target = null;
        for (UhfReader.Tag t : tags) {
            if (t == null || t.epc == null) continue;
            if (normalizeTag(t.epc).equals(targetEpc)) {
                target = t;
                break;
            }
        }

        // The SDK list is cumulative and is polled every ~25 ms, so the same entry keeps coming
        // back even when the radio has not heard the tag again. Only an increase of the read
        // counter is a fresh read. (A lower counter means a new trigger press cleared the list.)
        boolean fresh = target != null && target.count != lastTargetCount;

        if (fresh) {
            int prev = Math.max(lastTargetCount, 0);
            int newReads = target.count > prev ? target.count - prev : Math.max(1, target.count);
            lastTargetCount = target.count;

            hasSeenTarget = true;
            totalTargetReads += newReads;
            lastRssi = target.rssi;

            // Where the reader pointed WHEN this read happened feeds the estimate; the estimate
            // itself stays put in the room.
            estimator.addRead(currentHeading, target.rssi, newReads);
            targetBearing = estimator.bearing();

            // Scale RSSI: -58 dBm = 0%, -8 dBm = 100%
            float rawNow = clamp01((target.rssi + 58f) / 50f, 0.06f);
            float rawPeak = clamp01((estimator.bestRssi() + 58f) / 50f, 0.06f);
            liveStrength += (rawNow - liveStrength) * 0.6f;
            peakStrength = rawPeak;

            // Log-distance estimate from the PEAK signal, so it does not jitter with aim.
            estDistanceMeters = (float) Math.pow(10.0, (-8.0 - estimator.bestRssi()) / 22.0);
            estDistanceMeters = Math.max(0.1f, Math.min(8.0f, Math.round(estDistanceMeters * 10f) / 10f));
        } else {
            // No new read: the tag is out of the beam right now. Fade the live signal only.
            liveStrength *= 0.9f;
            if (liveStrength < 0.02f) liveStrength = 0f;
        }

        soundLocator.setStrength(liveStrength);
        renderUI();
    }

    private static float clamp01(float v, float min) {
        return Math.max(min, Math.min(1.0f, v));
    }

    @Override
    public void onState(boolean available, boolean reading, String message) {
        if (targetEpc.isEmpty()) return;
        triggerHeld = reading;
        if (!reading) {
            // Trigger released: stop the live signal and the sound. The dot and its bearing
            // are left exactly where they are, because the file has not moved.
            liveStrength = 0f;
            soundLocator.setStrength(0f);
            if (hasSeenTarget) renderUI();
        }
        if (!hasSeenTarget) {
            tvSignalReadout.setText(reading ? "Scanning... tag not in range yet" : "Hold trigger to scan");
            renderUI();
        }
    }

    // ---- Compass / Gyroscope Sensor (60 Hz) ----

    private final SensorEventListener sensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
                Float pointing = pointingHeading(rotationMatrix);
                if (pointing != null) {
                    currentHeading = applyCompassOffset(pointing);
                }
                // else: the reader points straight up or down, no horizontal direction exists;
                // keep the last heading instead of jumping.
            } else if (event.sensor.getType() == Sensor.TYPE_ORIENTATION) {
                float deg = event.values[0];
                if (deg < 0) deg += 360f;
                currentHeading = applyCompassOffset(deg);
            }
            renderUI();
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    private void renderUI() {
        if (targetEpc.isEmpty()) return; // no tag registered: the message from onCreate stays
        float diff = (targetBearing - currentHeading) % 360f;
        if (diff > 180f) diff -= 360f;
        if (diff < -180f) diff += 360f;

        boolean inBeam = Math.abs(diff) <= 28f;
        boolean isPinpoint = inBeam && peakStrength >= 0.82f;

        if (!hasSeenTarget) {
            tvGuidance.setText(triggerHeld
                    ? "TAG NOT DETECTED - KEEP SCANNING AND MOVING"
                    : "HOLD TRIGGER & SWEEP AROUND ROOM");
            tvGuidance.setBackgroundColor(Color.parseColor("#334155"));
        } else if (isPinpoint) {
            tvGuidance.setText("EXACT FILE - PINPOINT (under 15 cm)");
            tvGuidance.setBackgroundColor(Color.parseColor("#059669"));
        } else if (inBeam) {
            String distText = estDistanceMeters > 0 ? String.format(Locale.US, " (~%.1fm)", estDistanceMeters) : "";
            tvGuidance.setText("DIRECT AIM - IN BEAM RANGE" + distText);
            tvGuidance.setBackgroundColor(Color.parseColor("#16A34A"));
        } else if (diff < -28f && diff >= -120f) {
            tvGuidance.setText(String.format(Locale.US, "TURN LEFT (%d deg)", Math.round(Math.abs(diff))));
            tvGuidance.setBackgroundColor(Color.parseColor("#B45309"));
        } else if (diff > 28f && diff <= 120f) {
            tvGuidance.setText(String.format(Locale.US, "TURN RIGHT (%d deg)", Math.round(Math.abs(diff))));
            tvGuidance.setBackgroundColor(Color.parseColor("#B45309"));
        } else {
            tvGuidance.setText("BEHIND YOU - TURN AROUND");
            tvGuidance.setBackgroundColor(Color.parseColor("#DC2626"));
        }

        int pct = Math.round(liveStrength * 100f);
        pbSignal.setProgress(pct);

        if (hasSeenTarget) {
            tvSignalReadout.setText(String.format(Locale.US, "%d%% Signal  |  %d dBm  |  ~%.1fm  |  %d reads",
                    pct, lastRssi, estDistanceMeters, totalTargetReads));
        }

        if (debugVisible) {
            tvDebug.setText(String.format(Locale.US,
                    "heading %.0f | tag bearing %.0f | diff %.0f%nlast rssi %d | best rssi %.1f | reads %d%npower %d dBm | trigger %s",
                    currentHeading, targetBearing, diff, lastRssi,
                    estimator.hasEstimate() ? estimator.bestRssi() : 0f, totalTargetReads,
                    reader.getPower(), triggerHeld ? "held" : "released"));
        }

        radarView.update(peakStrength, currentHeading, targetBearing, inBeam, isPinpoint, hasSeenTarget);
    }

    /**
     * Compass heading of the direction the READER IS POINTING, correct at any tilt.
     *
     * The usual Android azimuth is the direction of the device's top edge and is only reliable
     * when the device lies roughly flat. A handheld reader is held more upright, with the
     * antenna looking out of the back (-Z) or the top (+Y). R maps device axes to the world
     * (x = East, y = North, z = Up), so the horizontal parts of those two axes are
     * (R[1], R[4]) for +Y and (-R[2], -R[5]) for -Z. Held flat the top edge gives the
     * direction, held upright the back does, and in between both agree, so they are added.
     * Returns null when the reader points almost straight up or down.
     */
    private static Float pointingHeading(float[] r) {
        double east = r[1] - r[2];
        double north = r[4] - r[5];
        if (Math.hypot(east, north) < 0.2) return null;
        double deg = Math.toDegrees(Math.atan2(east, north));
        if (deg < 0) deg += 360.0;
        return (float) deg;
    }

    private static float applyCompassOffset(float deg) {
        float h = (deg + COMPASS_OFFSET_DEG) % 360f;
        if (h < 0) h += 360f;
        return h;
    }

    private static String normalizeTag(String s) {
        if (s == null) return "";
        return s.replaceAll("[^0-9a-fA-F]", "").toUpperCase(Locale.US);
    }
}