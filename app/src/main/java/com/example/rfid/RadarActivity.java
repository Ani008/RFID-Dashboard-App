package com.example.rfid;

import android.content.Context;
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

    private float currentHeading = 0f;
    private float targetBearing = 0f;
    private float smoothStrength = 0f;
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
        if (fileName == null || fileName.isEmpty()) fileName = "File " + fileId;
        if (caseId == null) caseId = "—";
        if (storageLocation == null || storageLocation.isEmpty()) storageLocation = "Location not recorded";

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

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        btnMute.setOnClickListener(v -> {
            boolean nextMuted = !soundLocator.isMuted();
            soundLocator.setMuted(nextMuted);
            btnMute.setText(nextMuted ? "🔇 Muted" : "🔊 Audio");
        });

        reader = new UhfReader(this, this);
        soundLocator = new SoundLocator(this);

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
        if (tags == null || tags.isEmpty()) return;

        for (UhfReader.Tag t : tags) {
            if (t == null || t.epc == null) continue;
            String normalized = normalizeTag(t.epc);

            // Match only the target file's tag
            if (targetEpc.isEmpty() || normalized.equals(targetEpc)) {
                hasSeenTarget = true;
                totalTargetReads++;
                lastRssi = t.rssi;

                // Scale RSSI: -58 dBm = 0%, -8 dBm = 100%
                float rawTarget = (t.rssi - (-58f)) / (-8f - (-58f));
                rawTarget = Math.max(0.06f, Math.min(1.0f, rawTarget));

                // Log-distance physical estimation
                estDistanceMeters = (float) Math.pow(10.0, (-8.0 - t.rssi) / 22.0);
                estDistanceMeters = Math.max(0.1f, Math.min(8.0f, Math.round(estDistanceMeters * 10f) / 10f));

                // Instant rise on detection
                smoothStrength += (rawTarget - smoothStrength) * 0.92f;
                targetBearing = currentHeading; // Lock peak bearing
                break;
            }
        }

        soundLocator.setStrength(smoothStrength);
        renderUI();
    }

    @Override
    public void onState(boolean available, boolean reading, String message) {
        if (!hasSeenTarget) {
            tvSignalReadout.setText(reading ? "Scanning shelf tags..." : "Hold trigger to scan");
        }
    }

    // ---- Compass / Gyroscope Sensor (60 Hz) ----

    private final SensorEventListener sensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
                SensorManager.getOrientation(rotationMatrix, orientationAngles);
                float deg = (float) Math.toDegrees(orientationAngles[0]);
                if (deg < 0) deg += 360f;
                currentHeading = deg;
            } else if (event.sensor.getType() == Sensor.TYPE_ORIENTATION) {
                float deg = event.values[0];
                if (deg < 0) deg += 360f;
                currentHeading = deg;
            }
            renderUI();
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    private void renderUI() {
        float diff = (targetBearing - currentHeading) % 360f;
        if (diff > 180f) diff -= 360f;
        if (diff < -180f) diff += 360f;

        boolean inBeam = Math.abs(diff) <= 28f;
        boolean isPinpoint = inBeam && smoothStrength >= 0.82f;

        if (!hasSeenTarget) {
            tvGuidance.setText("HOLD TRIGGER & SWEEP AROUND ROOM");
            tvGuidance.setBackgroundColor(Color.parseColor("#334155"));
        } else if (isPinpoint) {
            tvGuidance.setText("🔥 EXACT FILE • PINPOINT (<15CM)!");
            tvGuidance.setBackgroundColor(Color.parseColor("#059669"));
        } else if (inBeam) {
            String distText = estDistanceMeters > 0 ? String.format(Locale.US, " (~%.1fm)", estDistanceMeters) : "";
            tvGuidance.setText("🎯 DIRECT AIM • IN BEAM RANGE!" + distText);
            tvGuidance.setBackgroundColor(Color.parseColor("#16A34A"));
        } else if (diff < -28f && diff >= -120f) {
            tvGuidance.setText(String.format(Locale.US, "👈 TURN LEFT (%d°)", Math.round(Math.abs(diff))));
            tvGuidance.setBackgroundColor(Color.parseColor("#B45309"));
        } else if (diff > 28f && diff <= 120f) {
            tvGuidance.setText(String.format(Locale.US, "TURN RIGHT (%d°) 👉", Math.round(Math.abs(diff))));
            tvGuidance.setBackgroundColor(Color.parseColor("#B45309"));
        } else {
            tvGuidance.setText("🔄 BEHIND YOU • TURN AROUND");
            tvGuidance.setBackgroundColor(Color.parseColor("#DC2626"));
        }

        int pct = Math.round(smoothStrength * 100f);
        pbSignal.setProgress(pct);

        if (hasSeenTarget) {
            tvSignalReadout.setText(String.format(Locale.US, "%d%% Signal  |  %d dBm  |  ~%.1fm  |  %d reads",
                    pct, lastRssi, estDistanceMeters, totalTargetReads));
        }

        radarView.update(smoothStrength, currentHeading, targetBearing, inBeam, isPinpoint, hasSeenTarget);
    }

    private static String normalizeTag(String s) {
        if (s == null) return "";
        return s.replaceAll("[^0-9a-fA-F]", "").toUpperCase(Locale.US);
    }
}
