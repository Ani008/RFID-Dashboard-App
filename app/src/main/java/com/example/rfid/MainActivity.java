package com.example.rfid;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.List;
import java.util.Locale;

/**
 * One screen: a locked WebView that shows the dashboard, plus a setup panel for entering the
 * dashboard address and checking that the reader works.
 *
 * Tags go reader -> this activity -> the page as "rfid:read" events (see DashboardBridge).
 * The app does not store or process file data.
 */
public class MainActivity extends AppCompatActivity implements UhfReader.Listener {

    private static final String TAG = "RFID";
    private static final String PREFS = "rfid";
    private static final String KEY_URL = "dashboard_url";

    private WebView web;
    private View setupPanel;
    private TextView readerStatus;
    private TextView tagList;
    private Button testScan;
    private EditText urlField;

    private UhfReader reader;
    private DashboardBridge bridge;

    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private Sensor accelSensor;
    private Sensor magSensor;
    private volatile float currentHeading = 0.0f;

    private final float[] rotationMatrix = new float[9];
    private final float[] orientationAngles = new float[3];
    private final float[] lastAccel = new float[3];
    private final float[] lastMag = new float[3];
    private boolean hasAccel = false;
    private boolean hasMag = false;

    private boolean debuggable;
    private boolean readerAvailable;
    private boolean readerReading;
    private String readerMessage = "";
    /** Shown under the reader status when the dashboard could not be opened. */
    private String dashboardNotice = "";

    /** Host of the dashboard we are allowed to show, or null if none is configured. */
    private String allowedHost;
    /** True once the dashboard page has finished loading, so events have somewhere to go. */
    private boolean pageReady;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.enableEdgeToEdge(getWindow());
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        // A handheld in a file room: do not let the screen go to sleep mid-search.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;

        web = findViewById(R.id.webview);
        setupPanel = findViewById(R.id.setup_panel);
        readerStatus = findViewById(R.id.reader_status);
        tagList = findViewById(R.id.tag_list);
        testScan = findViewById(R.id.btn_test_scan);
        urlField = findViewById(R.id.dashboard_url);

        reader = new UhfReader(this, this);
        bridge = new DashboardBridge(this, reader, versionName());

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            if (rotationSensor == null) {
                accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            }
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(true);
        web.addJavascriptInterface(bridge, DashboardBridge.JS_NAME);
        web.setWebViewClient(new DashboardClient());

        testScan.setOnClickListener(v -> reader.toggleReading());
        findViewById(R.id.btn_launch_radar).setOnClickListener(v -> showNativeSearchDialog());
        findViewById(R.id.btn_save).setOnClickListener(v -> saveUrlAndLoad());
        findViewById(R.id.btn_close).setOnClickListener(v -> closeSetup());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (setupPanel.getVisibility() == View.VISIBLE) {
                    // Setup is open: close it if there is a dashboard behind it, else leave the app.
                    if (pageReady) closeSetup(); else finish();
                } else if (web.canGoBack()) {
                    web.goBack();
                } else {
                    // First page of the dashboard: Back opens setup instead of quitting.
                    showSetup();
                }
            }
        });

        String url = savedUrl();
        urlField.setText(url);
        if (isValidUrl(url)) {
            loadDashboard(url);
        } else {
            showSetup();
        }
        renderStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reader.resume();
        if (sensorManager != null) {
            if (rotationSensor != null) {
                sensorManager.registerListener(sensorListener, rotationSensor, SensorManager.SENSOR_DELAY_UI);
            } else {
                if (accelSensor != null) sensorManager.registerListener(sensorListener, accelSensor, SensorManager.SENSOR_DELAY_UI);
                if (magSensor != null) sensorManager.registerListener(sensorListener, magSensor, SensorManager.SENSOR_DELAY_UI);
            }
        }
    }

    @Override
    protected void onPause() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(sensorListener);
        }
        reader.pause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        reader.destroy();
        web.removeJavascriptInterface(DashboardBridge.JS_NAME);
        web.destroy();
        super.onDestroy();
    }

    // ---- compass / orientation sensor ----

    private long lastHeadingDispatch = 0;
    private float lastDispatchedHeading = -999f;

    private final SensorEventListener sensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
                SensorManager.getOrientation(rotationMatrix, orientationAngles);
                float deg = (float) Math.toDegrees(orientationAngles[0]);
                if (deg < 0) deg += 360f;
                currentHeading = Math.round(deg * 10f) / 10f;
                notifyHeadingChanged();
            } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                System.arraycopy(event.values, 0, lastAccel, 0, 3);
                hasAccel = true;
                updateHeadingFromAccelMag();
            } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
                System.arraycopy(event.values, 0, lastMag, 0, 3);
                hasMag = true;
                updateHeadingFromAccelMag();
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    private void updateHeadingFromAccelMag() {
        if (rotationSensor != null) return;
        if (hasAccel && hasMag) {
            if (SensorManager.getRotationMatrix(rotationMatrix, null, lastAccel, lastMag)) {
                SensorManager.getOrientation(rotationMatrix, orientationAngles);
                float deg = (float) Math.toDegrees(orientationAngles[0]);
                if (deg < 0) deg += 360f;
                currentHeading = Math.round(deg * 10f) / 10f;
                notifyHeadingChanged();
            }
        }
    }

    private void notifyHeadingChanged() {
        if (bridge != null) {
            bridge.setHeading(currentHeading);
        }
        long now = System.currentTimeMillis();
        // Dispatch to WebView if rotated >= 1.0 deg or every 50ms for smooth live tracking
        if (pageReady && (now - lastHeadingDispatch >= 50 || Math.abs(currentHeading - lastDispatchedHeading) >= 1.0f)) {
            lastHeadingDispatch = now;
            lastDispatchedHeading = currentHeading;
            DashboardBridge.sendHeading(web, currentHeading);
        }
    }

    // ---- reader events (main thread) ----

    @Override
    public void onTags(List<UhfReader.Tag> tags) {
        if (pageReady) {
            DashboardBridge.sendTags(web, tags, currentHeading, reader.getPower());
        }
        if (debuggable) {
            for (UhfReader.Tag t : tags) {
                Log.d(TAG, "tag epc=" + t.epc + " rssi=" + t.rssi + " count=" + t.count
                        + " heading=" + currentHeading + " power=" + reader.getPower());
            }
        }
        if (setupPanel.getVisibility() == View.VISIBLE) {
            StringBuilder sb = new StringBuilder();
            for (UhfReader.Tag t : tags) {
                sb.append(String.format(Locale.US, "%s\n  rssi %d   count %d\n", t.epc, t.rssi, t.count));
            }
            sb.append(String.format(Locale.US, "\nCompass: %.1f° | Power: %d dBm\n", currentHeading, reader.getPower()));
            tagList.setText(sb.length() == 0 ? getString(R.string.no_tags_yet) : sb.toString());
        }
    }

    @Override
    public void onState(boolean available, boolean reading, String message) {
        readerAvailable = available;
        readerReading = reading;
        readerMessage = message;
        bridge.setState(available, reading);
        if (pageReady) {
            DashboardBridge.sendState(web, available, reading, message);
        }
        renderStatus();
    }

    private void renderStatus() {
        String status = readerMessage.isEmpty() ? "Starting reader..." : readerMessage;
        if (!dashboardNotice.isEmpty()) {
            status += "\n" + dashboardNotice;
        }
        readerStatus.setText(status);
        testScan.setText(readerReading ? R.string.test_scan_stop : R.string.test_scan_start);
    }

    // ---- dashboard address ----

    private String savedUrl() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        return p.getString(KEY_URL, getString(R.string.default_dashboard_url)).trim();
    }

    private static boolean isValidUrl(String url) {
        if (url == null) return false;
        Uri u = Uri.parse(url);
        String scheme = u.getScheme();
        return u.getHost() != null && ("http".equals(scheme) || "https".equals(scheme));
    }

    private void saveUrlAndLoad() {
        String url = urlField.getText().toString().trim();
        if (!isValidUrl(url)) {
            Toast.makeText(this, R.string.url_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_URL, url).apply();
        loadDashboard(url);
        closeSetup();
    }

    private void loadDashboard(String url) {
        allowedHost = Uri.parse(url).getHost();
        pageReady = false;
        dashboardNotice = "";
        web.loadUrl(url);
    }

    private void showSetup() {
        urlField.setText(savedUrl());
        setupPanel.setVisibility(View.VISIBLE);
        renderStatus();
    }

    private void closeSetup() {
        setupPanel.setVisibility(View.GONE);
    }

    private String versionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (Exception e) {
            return "";
        }
    }

    // ---- WebView client: keep the app locked to the dashboard ----

    private boolean isDashboardUrl(Uri uri) {
        return allowedHost != null && allowedHost.equalsIgnoreCase(uri.getHost());
    }

    private class DashboardClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (isDashboardUrl(request.getUrl())) {
                return false; // let the WebView load it
            }
            Toast.makeText(MainActivity.this, R.string.blocked_navigation, Toast.LENGTH_SHORT).show();
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            pageReady = false;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            // Only talk to the page if it really is our dashboard.
            pageReady = url != null && isDashboardUrl(Uri.parse(url));
            if (pageReady) {
                dashboardNotice = "";
                renderStatus();
                DashboardBridge.sendState(web, readerAvailable, readerReading, readerMessage);
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                pageReady = false;
                dashboardNotice = "Could not open the dashboard (" + error.getDescription()
                        + "). Check the address and the Wi-Fi connection.";
                showSetup();
                renderStatus();
            }
        }
    }

    // ---- native file search (see FileSearchDialog, FileApiClient and ApiConfig) ----

    private void showNativeSearchDialog() {
        new FileSearchDialog(this, savedUrl(), this::readDashboardToken, this::openRadar).show();
    }

    private void openRadar(FileApiClient.FileInfo file) {
        android.content.Intent intent = new android.content.Intent(this, RadarActivity.class);
        intent.putExtra("fileId", file.fileId);
        intent.putExtra("fileName", file.fileName);
        intent.putExtra("caseId", file.caseId);
        intent.putExtra("rfidTag", file.rfidTag);
        intent.putExtra("storageLocation", file.storageLocation);
        startActivity(intent);
    }

    /**
     * The backend requires a login, so borrow the token of the session that is already logged in
     * inside the dashboard WebView. Delivers null if the dashboard is not loaded or no token is
     * found (the request is then sent without one and the server's answer is shown to the user).
     */
    private void readDashboardToken(FileSearchDialog.TokenCallback callback) {
        if (!pageReady) {
            callback.onToken(null);
            return;
        }
        org.json.JSONArray keys = new org.json.JSONArray();
        for (String k : ApiConfig.TOKEN_STORAGE_KEYS) keys.put(k);

        String js = "(function(){var keys=" + keys + ";"
                + "var stores=[window.localStorage,window.sessionStorage];"
                + "for(var s=0;s<stores.length;s++){for(var i=0;i<keys.length;i++){"
                + "try{var v=stores[s].getItem(keys[i]);if(!v)continue;"
                + "try{var j=JSON.parse(v);"
                + "if(typeof j==='string'){v=j;}"
                + "else if(j&&typeof j==='object'){"
                + "v=j.token||j.accessToken||j.access_token||(j.state&&(j.state.token||j.state.accessToken))||'';}"
                + "}catch(e){}"
                + "if(v)return v;}catch(e){}}}"
                + "return '';})()";

        web.evaluateJavascript(js, value -> {
            String token = null;
            try {
                if (value != null && !value.equals("null")) {
                    Object parsed = new org.json.JSONTokener(value).nextValue();
                    if (parsed instanceof String && !((String) parsed).isEmpty()) {
                        token = (String) parsed;
                    }
                }
            } catch (Exception ignored) {
            }
            callback.onToken(token);
        });
    }
}
