package com.example.rfid;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
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

    // ---- readings log (REC button) ----
    private static final long REC_WINDOW_MS = 3000;   // a record uses the last 3 seconds of reads
    private static final int REC_MIN_SAMPLES = 5;
    private static final String KEY_LOG = "reading_log";
    private static final String KEY_LAST_DIST = "log_last_dist";
    private static final String KEY_LAST_NOTE = "log_last_note";
    private final ArrayList<long[]> recSamples = new ArrayList<>(); // {time ms, window max, raw rssi}
    private TextView btnLog;

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

    // Distance: estimated from the signal of the most recent reads (see DistanceEstimator).
    private final DistanceEstimator distance = new DistanceEstimator();
    private int currentPowerDbm = DEFAULT_POWER_DBM;
    private static final float PINPOINT_CM = 15f;

    // Used when no calibration exists for the current power. The reference is a rough value at
    // 30 dBm; the signal is assumed to move 1:1 with transmit power. Calibrate for real numbers.
    private static final double DEFAULT_REF_CM = 15.0;
    private static final double DEFAULT_REF_RSSI_AT_30DBM = -17.0;
    private static final double DEFAULT_SLOPE = 40.0; // dB per decade of distance

    // Two-point calibration
    private static final int CAL_NEAR_CM = 20;
    private static final int CAL_FAR_CM = 100;
    private static final int CAL_MIN_SAMPLES = 30;
    private boolean calCapturing = false;
    private int calSession = 0;
    private final ArrayList<Integer> calSamples = new ArrayList<>();

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

        findViewById(R.id.btn_calibrate).setOnClickListener(v -> startCalibration());

        // Tap the title OR the file name to show or hide the diagnostic panel (on the radar).
        View.OnClickListener toggleDebug = v -> {
            debugVisible = !debugVisible;
            tvDebug.setVisibility(debugVisible ? View.VISIBLE : View.GONE);
            renderUI();
        };
        findViewById(R.id.tv_title).setOnClickListener(toggleDebug);
        tvFileTitle.setOnClickListener(toggleDebug);

        // REC = save one reading (median of the last 3 s). LOG = see / copy all saved readings.
        btnLog = findViewById(R.id.btn_log);
        findViewById(R.id.btn_rec).setOnClickListener(v -> onRecordTapped());
        btnLog.setOnClickListener(v -> showLogDialog());
        btnLog.setOnLongClickListener(v -> {
            confirmClearLog();
            return true;
        });
        updateLogButton();

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

            // Scale RSSI: -58 dBm = 0%, -8 dBm = 100%  (signal bar and sound only)
            float rawNow = clamp01((target.rssi + 58f) / 50f, 0.06f);
            liveStrength += (rawNow - liveStrength) * 0.6f;

            // Distance follows the signal of the last few fresh reads, so the dot moves toward the
            // reader as you move closer and away as you back off. Stale polls never reach here.
            distance.addRead(target.rssi);
            updateDistanceFromEstimator();
            if (calCapturing) calSamples.add(distance.strongestRecent());

            long nowMs = SystemClock.elapsedRealtime();
            recSamples.add(new long[]{nowMs, distance.strongestRecent(), target.rssi});
            while (!recSamples.isEmpty() && nowMs - recSamples.get(0)[0] > 10000) recSamples.remove(0);
        } else {
            // No new read: the tag is out of the beam right now. Fade the live signal only.
            liveStrength *= 0.9f;
            if (liveStrength < 0.02f) liveStrength = 0f;
        }

        soundLocator.setStrength(liveStrength);
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
        tvPower.setText("Reader power: " + currentPowerDbm + " dBm  |  distance: "
                + (distance.isCalibrated() ? "calibrated" : "not calibrated"));
    }

    // ---- two-point distance calibration ----

    private void startCalibration() {
        if (targetEpc.isEmpty()) {
            Toast.makeText(this, "This file has no RFID tag registered.", Toast.LENGTH_LONG).show();
            return;
        }
        calibrationStep(1, CAL_NEAR_CM, 0);
    }

    private void calibrationStep(final int step, final int atCm, final int nearRssi) {
        final int session = ++calSession;
        calSamples.clear();
        distance.resetWindow();
        calCapturing = true;

        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(this)
                .setTitle("Calibrate distance (step " + step + " of 2)")
                .setMessage("Place the reader " + atCm + " cm from the tag and aim it straight at the tag."
                        + "\n\nHold the trigger for about 3 seconds, then tap Save."
                        + "\n\nPower: " + currentPowerDbm + " dBm. Calibration is stored for this power setting.")
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null);
        if (step == 1) {
            b.setNeutralButton("Use default", (d, w) -> {
                SharedPreferences.Editor e = appPrefs().edit();
                e.remove(calKey(currentPowerDbm, "ref_cm"));
                e.remove(calKey(currentPowerDbm, "ref_rssi"));
                e.remove(calKey(currentPowerDbm, "slope"));
                e.apply();
                loadCalibration(currentPowerDbm);
                updatePowerLabel();
                Toast.makeText(this, "Distance calibration cleared.", Toast.LENGTH_SHORT).show();
            });
        }
        final androidx.appcompat.app.AlertDialog dialog = b.create();
        dialog.setOnDismissListener(d -> {
            // Dismissal is delivered late, so only the dialog of the CURRENT step may stop capturing.
            if (calSession == session) calCapturing = false;
        });
        dialog.show();

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (calSamples.size() < CAL_MIN_SAMPLES) {
                Toast.makeText(this, "Not enough reads yet. Aim at the tag and hold the trigger a few seconds, then tap Save.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            ArrayList<Integer> sorted = new ArrayList<>(calSamples);
            Collections.sort(sorted);
            int captured = sorted.get(sorted.size() / 2); // median of the same signal used at run time
            dialog.dismiss();
            Toast.makeText(this, "Step " + step + ": captured signal " + captured + " at " + atCm
                    + " cm (" + sorted.size() + " reads)", Toast.LENGTH_LONG).show();
            appendLog("CAL step " + step + ": " + atCm + " cm -> window max " + captured
                    + " (" + sorted.size() + " reads, " + currentPowerDbm + " dBm)");
            if (step == 1) {
                calibrationStep(2, CAL_FAR_CM, captured);
            } else {
                finishCalibration(nearRssi, captured);
            }
        });
    }

    private void finishCalibration(int nearRssi, int farRssi) {
        double[] fit = DistanceEstimator.fit(CAL_NEAR_CM, nearRssi, CAL_FAR_CM, farRssi);
        if (fit == null) {
            Toast.makeText(this, "Calibration failed: the signal was not clearly weaker at "
                    + CAL_FAR_CM + " cm than at " + CAL_NEAR_CM
                    + " cm. Aim straight at the tag at both distances and try again.", Toast.LENGTH_LONG).show();
            return;
        }
        appPrefs().edit()
                .putFloat(calKey(currentPowerDbm, "ref_cm"), (float) fit[0])
                .putFloat(calKey(currentPowerDbm, "ref_rssi"), (float) fit[1])
                .putFloat(calKey(currentPowerDbm, "slope"), (float) fit[2])
                .apply();
        loadCalibration(currentPowerDbm);
        updatePowerLabel();
        appendLog("CAL saved at " + currentPowerDbm + " dBm: " + distance.modelText());
        Toast.makeText(this, "Distance calibrated for " + currentPowerDbm + " dBm. " + distance.modelText(),
                Toast.LENGTH_LONG).show();
    }


    // ---- readings log ----

    private String readLog() {
        return appPrefs().getString(KEY_LOG, "");
    }

    private int logCount() {
        String s = readLog();
        return s.isEmpty() ? 0 : s.split("\n").length;
    }

    private void appendLog(String line) {
        String s = readLog();
        appPrefs().edit().putString(KEY_LOG, s.isEmpty() ? line : s + "\n" + line).apply();
        updateLogButton();
    }

    private void updateLogButton() {
        if (btnLog != null) btnLog.setText("LOG\n" + logCount());
    }

    /** Snapshot the last 3 s of reads, then ask for the real distance and an optional note. */
    private void onRecordTapped() {
        if (targetEpc.isEmpty()) {
            Toast.makeText(this, "This file has no RFID tag registered.", Toast.LENGTH_LONG).show();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        ArrayList<Integer> wm = new ArrayList<>();
        ArrayList<Integer> raw = new ArrayList<>();
        for (long[] s : recSamples) {
            if (now - s[0] <= REC_WINDOW_MS) {
                wm.add((int) s[1]);
                raw.add((int) s[2]);
            }
        }
        if (wm.size() < REC_MIN_SAMPLES) {
            Toast.makeText(this, "Only " + wm.size() + " reads in the last 3 s. Aim at the tag, hold the trigger, "
                    + "and tap REC while it is still reading.", Toast.LENGTH_LONG).show();
            return;
        }
        Collections.sort(wm);
        Collections.sort(raw);
        final int n = wm.size();
        final int medWm = wm.get(n / 2);
        final int minWm = wm.get(0);
        final int maxWm = wm.get(n - 1);
        final int medRaw = raw.get(n / 2);
        final int estCm = (int) Math.round(distance.distanceForRssi(medWm));
        final int power = currentPowerDbm;
        final String model = distance.isCalibrated() ? "cal" : "default";

        final EditText etDist = new EditText(this);
        etDist.setHint("Real distance in cm (measure it)");
        etDist.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etDist.setText(appPrefs().getString(KEY_LAST_DIST, ""));
        etDist.selectAll();

        final EditText etNote = new EditText(this);
        etNote.setHint("Note (e.g. on rack, metal shelf, open table)");
        etNote.setInputType(InputType.TYPE_CLASS_TEXT);
        etNote.setText(appPrefs().getString(KEY_LAST_NOTE, ""));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(etDist);
        box.addView(etNote);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Save reading")
                .setMessage("window max " + medWm + "  (range " + minWm + " to " + maxWm + ")\n"
                        + "raw " + medRaw + "   app says ~" + estCm + " cm\n"
                        + n + " reads, " + power + " dBm")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    String dist = etDist.getText().toString().trim();
                    String note = etNote.getText().toString().trim();
                    if (dist.isEmpty()) dist = "?";
                    appPrefs().edit().putString(KEY_LAST_DIST, dist).putString(KEY_LAST_NOTE, note).apply();
                    appendLog(String.format(Locale.US,
                            "#%d  real %s cm | wmax %d [%d..%d] | raw %d | app %d cm | n=%d | %d dBm %s%s",
                            logCount() + 1, dist, medWm, minWm, maxWm, medRaw, estCm, n, power, model,
                            note.isEmpty() ? "" : " | " + note));
                    Toast.makeText(this, "Saved reading #" + logCount(), Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void showLogDialog() {
        String text = readLog();
        final TextView tv = new TextView(this);
        tv.setText(text.isEmpty()
                ? "No readings yet.\n\nHold the trigger aimed at the tag, then tap the red REC circle."
                : text);
        tv.setTextIsSelectable(true);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextSize(12f);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Saved readings (" + logCount() + ")")
                .setView(sv)
                .setPositiveButton("Copy all", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("RFID readings", readLog()));
                    Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("Delete last", (d, w) -> {
                    String s = readLog();
                    int i = s.lastIndexOf('\n');
                    appPrefs().edit().putString(KEY_LOG, i < 0 ? "" : s.substring(0, i)).apply();
                    updateLogButton();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void confirmClearLog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Clear all saved readings?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (d, w) -> {
                    appPrefs().edit().remove(KEY_LOG).apply();
                    updateLogButton();
                })
                .show();
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
        boolean isPinpoint = inBeam && distanceCm > 0f && distanceCm <= PINPOINT_CM;

        if (!hasSeenTarget) {
            tvGuidance.setText(triggerHeld
                    ? "TAG NOT DETECTED - KEEP SCANNING AND MOVING"
                    : "HOLD TRIGGER & SWEEP AROUND ROOM");
            tvGuidance.setBackgroundColor(Color.parseColor("#334155"));
        } else if (isPinpoint) {
            tvGuidance.setText("EXACT FILE - PINPOINT (under 15 cm)");
            tvGuidance.setBackgroundColor(Color.parseColor("#059669"));
        } else if (inBeam) {
            String distText = distanceCm > 0 ? " (~" + formatDistance(distanceCm) + ")" : "";
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
            tvSignalReadout.setText(String.format(Locale.US, "%d%% Signal  |  %d dBm  |  ~%s  |  %d reads",
                    pct, lastRssi, distanceCm > 0 ? formatDistance(distanceCm) : "--", totalTargetReads));
        }

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