package com.example.rfid;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.button.MaterialButton;

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
    private MaterialButton btnMute;
    private TextView tvHeading;
    private TextView tvDistance;
    private int lastShownHeading = -1;
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
    private float radarProximity = 0f;  // 0 = outer ring .. 1 = at the reader: places the dot
    private float liveStrength = 0f;    // 0..1 right now: drives the signal bar and the sound
    private int lastTargetCount = -1;   // SDK read counter of the target at the previous poll
    private boolean triggerHeld = false;
    private boolean hasSeenTarget = false;
    private int totalTargetReads = 0;
    private int lastRssi = -999;
    private float distanceCm = -1f;        // estimated reader-to-tag distance, -1 = unknown
    private long lastTargetSeenMs = 0;
    private long lastReadProcessedMs = 0;

    // Distance: estimated from the signal of the most recent reads (see DistanceEstimator).
    private final DistanceEstimator distance = new DistanceEstimator();
    private int currentPowerDbm = DEFAULT_POWER_DBM;
    private static final float PINPOINT_CM = 15f;

    // Used when no calibration exists for the current power. The reference is a rough value at
    // 30 dBm; the signal is assumed to move 1:1 with transmit power. Calibrate for real numbers.
    private static final double DEFAULT_REF_CM = 15.0;
    private static final double DEFAULT_REF_RSSI_AT_30DBM = -17.0;
    private static final double DEFAULT_SLOPE = 40.0; // dB per decade of distance

    // Beeping and vibration both follow the SAME distance the dot uses.
    // Far (BEEP_FAR_CM or more) = slow beeps, getting faster as you come closer, fastest at
    // BEEP_NEAR_CM. Vibration starts at VIBRATE_ON_CM and stops again above VIBRATE_OFF_CM.
    private static final float BEEP_FAR_CM = 150f;
    private static final float BEEP_NEAR_CM = 15f;
    private static final float VIBRATE_ON_CM = 15f;
    private static final float VIBRATE_OFF_CM = 19f;
    private float beepLevel = 0f;       // 0..1, drives the beep speed only
    private boolean vibrating = false;

    private final float[] rotationMatrix = new float[9];
    private final float[] orientationAngles = new float[3];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowCompat.enableEdgeToEdge(getWindow());
        // Dark screen: keep the status and navigation bar icons light.
        WindowInsetsControllerCompat bars =
                new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(false);
        bars.setAppearanceLightNavigationBars(false);
        setContentView(R.layout.activity_radar);

        // Keep the content clear of the status and navigation bars (edge-to-edge is enforced
        // on new Android versions). The layout's own padding is added on top.
        final View root = findViewById(R.id.radar_root);
        final int padL = root.getPaddingLeft(), padT = root.getPaddingTop();
        final int padR = root.getPaddingRight(), padB = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(padL + sys.left, padT + sys.top, padR + sys.right, padB + sys.bottom);
            return insets;
        });

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
        tvHeading = findViewById(R.id.tv_heading);
        tvDistance = findViewById(R.id.tv_distance);

        tvFileTitle.setText(fileName);
        tvFileId.setText("File ID: " + fileId);
        tvCaseId.setText("Case: " + caseId);
        tvStorageLocation.setText("Location: " + storageLocation);

        if (targetEpc.isEmpty()) {
            tvGuidance.setText("NO RFID TAG IS REGISTERED FOR THIS FILE");
            setGuidanceColor("#C9373D");
            tvSignalReadout.setText("Register a tag for this file on the dashboard");
        }

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        btnMute.setOnClickListener(v -> {
            boolean nextMuted = !soundLocator.isMuted();
            soundLocator.setMuted(nextMuted);
            btnMute.setIconResource(nextMuted ? R.drawable.ic_volume_off : R.drawable.ic_volume_up);
            btnMute.setContentDescription(getString(nextMuted ? R.string.radar_audio_off : R.string.radar_audio_on));
            btnMute.setIconTint(ColorStateList.valueOf(Color.parseColor(nextMuted ? "#9AABC7" : "#E6C766")));
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
        currentPowerDbm = savedPower;
        loadCalibration(savedPower);
        updatePowerLabel();
        reader.setPowerLevel(savedPower);
        sbPower.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int dbm = progress + MIN_POWER_DBM;
                currentPowerDbm = dbm;
                loadCalibration(dbm);
                updatePowerLabel();
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

        // Tap the title or the file name to show or hide a hidden diagnostic panel (off by default).
        View.OnClickListener toggleDebug = v -> {
            debugVisible = !debugVisible;
            tvDebug.setVisibility(debugVisible ? View.VISIBLE : View.GONE);
            renderUI();
        };
        findViewById(R.id.tv_title).setOnClickListener(toggleDebug);
        tvFileTitle.setOnClickListener(toggleDebug);

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

        long now = System.currentTimeMillis();

        if (target != null) {
            hasSeenTarget = true;
            lastTargetSeenMs = now;

            // Process read if count changed, RSSI changed, or at least 40ms passed
            boolean countChanged = (target.count != lastTargetCount);
            boolean rssiChanged = (lastRssi != -999 && Math.abs(target.rssi - lastRssi) >= 1);
            boolean timeElapsed = (now - lastReadProcessedMs >= 40);

            if (countChanged || rssiChanged || timeElapsed || lastTargetCount == -1) {
                int prev = Math.max(lastTargetCount, 0);
                int newReads = (target.count > prev) ? (target.count - prev) : 1;

                lastTargetCount = target.count;
                lastRssi = target.rssi;
                lastReadProcessedMs = now;
                totalTargetReads += newReads;

                // Bearing & distance estimation
                estimator.addRead(currentHeading, target.rssi, newReads);
                targetBearing = estimator.bearing();

                distance.addRead(target.rssi, now);
                updateDistanceFromEstimator();
            }

            // Live signal strength (0..1)
            float rawNow = clamp01((target.rssi + 58f) / 50f, 0.06f);
            liveStrength += (rawNow - liveStrength) * 0.5f;

            // Beep speed and vibration based directly on calculated distance
            beepLevel = beepForDistance(distanceCm);

            if (distanceCm > 0f && distanceCm <= VIBRATE_ON_CM) {
                vibrating = true;
            } else if (distanceCm > VIBRATE_OFF_CM) {
                vibrating = false;
            }
        } else {
            // Target tag not seen in this poll tick
            long silence = now - lastTargetSeenMs;
            if (lastTargetSeenMs > 0 && silence > 350) {
                liveStrength *= 0.85f;
                if (liveStrength < 0.02f) liveStrength = 0f;
                beepLevel *= 0.85f;
                if (beepLevel < 0.02f) {
                    beepLevel = 0f;
                    vibrating = false;
                }
            }
        }

        soundLocator.setStrength(triggerHeld ? beepLevel : 0f);
        soundLocator.setVibrate(triggerHeld && vibrating);
        renderUI();
    }

    // ---- distance ----

    private void updateDistanceFromEstimator() {
        if (!distance.hasEstimate()) return;
        distanceCm = (float) distance.distanceCm();
        float f = (float) Math.sqrt(Math.min(1.0, distanceCm / RadarView.RANGE_CM));
        radarProximity = 1f - f;
    }

    private static String formatDistance(float cm) {
        if (cm < 100f) return String.format(Locale.US, "%d cm", Math.round(cm));
        return String.format(Locale.US, "%.1f m", cm / 100f);
    }

    private SharedPreferences appPrefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private static String calKey(int dbm, String field) {
        return "cal_" + dbm + "_" + field;
    }

    /** Uses the saved calibration for this power setting, or the rough default if there is none. */
    private void loadCalibration(int dbm) {
        SharedPreferences p = appPrefs();
        if (p.contains(calKey(dbm, "slope"))) {
            distance.configure(
                    p.getFloat(calKey(dbm, "ref_cm"), (float) DEFAULT_REF_CM),
                    p.getFloat(calKey(dbm, "ref_rssi"), (float) DEFAULT_REF_RSSI_AT_30DBM),
                    p.getFloat(calKey(dbm, "slope"), (float) DEFAULT_SLOPE),
                    true);
        } else {
            distance.configure(DEFAULT_REF_CM, DEFAULT_REF_RSSI_AT_30DBM + (dbm - 30),
                    DEFAULT_SLOPE, false);
        }
        updateDistanceFromEstimator();
    }

    private void updatePowerLabel() {
        tvPower.setText(getString(R.string.radar_power) + "  \u00B7  " + currentPowerDbm + " dBm");
    }

    /** 1 at BEEP_NEAR_CM or closer, falling smoothly (log scale) to the slowest beep at BEEP_FAR_CM. */
    private static float beepForDistance(float cm) {
        if (cm <= 0f) return 0.06f;
        if (cm <= BEEP_NEAR_CM) return 1f;
        double s = 1.0 - Math.log10(cm / BEEP_NEAR_CM) / Math.log10(BEEP_FAR_CM / BEEP_NEAR_CM);
        return (float) Math.max(0.06, Math.min(1.0, s));
    }

    private static float clamp01(float v, float min) {
        return Math.max(min, Math.min(1.0f, v));
    }

    @Override
    public void onState(boolean available, boolean reading, String message) {
        if (targetEpc.isEmpty()) return;
        boolean wasHeld = triggerHeld;
        triggerHeld = reading;
        if (reading && !wasHeld) {
            // New trigger press: reset window & counters so reading starts fresh
            distance.resetWindow();
            lastTargetCount = -1;
            lastTargetSeenMs = 0;
            lastReadProcessedMs = 0;
        }
        if (!reading) {
            // Trigger released: stop live signal, sound, and vibration
            liveStrength = 0f;
            beepLevel = 0f;
            vibrating = false;
            soundLocator.setStrength(0f);
            soundLocator.setVibrate(false);
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

    /** Tints the rounded guidance pill (setBackgroundColor would drop the rounded shape). */
    private void setGuidanceColor(String hex) {
        tvGuidance.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor(hex)));
    }

    private static final String[] POINTS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    /** Big "45° NE" readout above the dial, updated only when the whole degree changes. */
    private void renderHeading() {
        int deg = Math.round(currentHeading) % 360;
        if (deg == lastShownHeading) return;
        lastShownHeading = deg;
        String point = POINTS[Math.round(deg / 45f) % 8];
        tvHeading.setText(String.format(Locale.US, "%d\u00B0 %s", deg, point));
    }

    private void renderUI() {
        renderHeading();
        if (targetEpc.isEmpty()) {
            // no tag registered: the message from onCreate stays, the compass still turns
            radarView.update(0f, currentHeading, 0f, false, false, false);
            return;
        }
        float diff = (targetBearing - currentHeading) % 360f;
        if (diff > 180f) diff -= 360f;
        if (diff < -180f) diff += 360f;

        boolean inBeam = Math.abs(diff) <= 28f;
        boolean isPinpoint = inBeam && distanceCm > 0f && distanceCm <= PINPOINT_CM;

        if (!hasSeenTarget) {
            tvGuidance.setText(triggerHeld
                    ? "TAG NOT DETECTED - KEEP SCANNING AND MOVING"
                    : "HOLD TRIGGER & SWEEP AROUND ROOM");
            setGuidanceColor("#2A3B5C");
        } else if (isPinpoint) {
            tvGuidance.setText("EXACT FILE - PINPOINT");
            setGuidanceColor("#10A06B");
        } else if (inBeam) {
            tvGuidance.setText("DIRECT AIM - IN BEAM RANGE");
            setGuidanceColor("#1F9D61");
        } else if (diff < -28f && diff >= -120f) {
            tvGuidance.setText(String.format(Locale.US, "TURN LEFT (%d deg)", Math.round(Math.abs(diff))));
            setGuidanceColor("#C77C0E");
        } else if (diff > 28f && diff <= 120f) {
            tvGuidance.setText(String.format(Locale.US, "TURN RIGHT (%d deg)", Math.round(Math.abs(diff))));
            setGuidanceColor("#C77C0E");
        } else {
            tvGuidance.setText("BEHIND YOU - TURN AROUND");
            setGuidanceColor("#C9373D");
        }

        int pct = Math.round(liveStrength * 100f);
        pbSignal.setProgress(pct);

        if (hasSeenTarget) {
            tvSignalReadout.setText(triggerHeld
                    ? String.format(Locale.US, "Tag detected  \u00B7  %d%%", pct)
                    : "Trigger released - position held");
        }
        tvDistance.setText(distanceCm > 0f ? formatDistance(distanceCm) : "--");

        if (debugVisible) {
            tvDebug.setText(String.format(Locale.US,
                    "heading %.0f | tag bearing %.0f | diff %.0f%nlast rssi %d | window max %d | reads %d%npower %d dBm | trigger %s%ndistance %.0f cm | model %s%n%s",
                    currentHeading, targetBearing, diff, lastRssi,
                    distance.hasEstimate() ? distance.strongestRecent() : 0, totalTargetReads,
                    reader.getPower(), triggerHeld ? "held" : "released",
                    distanceCm, distance.isCalibrated() ? "calibrated" : "default", distance.modelText()));
        }

        radarView.update(radarProximity, currentHeading, targetBearing, inBeam, isPinpoint, hasSeenTarget);
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