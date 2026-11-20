package com.example.rfid;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
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
        bridge = new DashboardBridge(reader, versionName());

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
    }

    @Override
    protected void onPause() {
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

    // ---- reader events (main thread) ----

    @Override
    public void onTags(List<UhfReader.Tag> tags) {
        if (pageReady) {
            DashboardBridge.sendTags(web, tags);
        }
        if (debuggable) {
            for (UhfReader.Tag t : tags) {
                Log.d(TAG, "tag epc=" + t.epc + " rssi=" + t.rssi + " count=" + t.count);
            }
        }
        if (setupPanel.getVisibility() == View.VISIBLE) {
            StringBuilder sb = new StringBuilder();
            for (UhfReader.Tag t : tags) {
                sb.append(String.format(Locale.US, "%s\n  rssi %d   count %d\n", t.epc, t.rssi, t.count));
            }
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
}
