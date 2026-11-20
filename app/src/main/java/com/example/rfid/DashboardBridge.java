package com.example.rfid;

import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * The contract between this app and the dashboard page.
 *
 * Page -> app (JavaScript, only exists inside this app):
 *   window.RFIDNative.isNative()        true
 *   window.RFIDNative.getInfo()         JSON string: {"app","version","available","reading"}
 *   window.RFIDNative.startReading()    same as holding the trigger
 *   window.RFIDNative.stopReading()
 *
 * App -> page (window events, detail is a plain object):
 *   "rfid:read"   {source, ts, tags:[{epc, rssi, count}]}   every ~100 ms while the trigger is held
 *   "rfid:state"  {available, reading, message}              reader ready / reading / error
 *
 * rssi is the raw number from the SEUIC SDK (higher = stronger signal). The dashboard converts it
 * to a distance once we know the unit.
 */
public final class DashboardBridge {

    public static final String JS_NAME = "RFIDNative";

    private static final String SOURCE = "seuic-utouch2";

    private final UhfReader reader;
    private final String versionName;

    // Written on main thread, read from the WebView's JS thread.
    private volatile boolean available;
    private volatile boolean reading;

    public DashboardBridge(UhfReader reader, String versionName) {
        this.reader = reader;
        this.versionName = versionName;
    }

    // ---- called by the page ----

    @JavascriptInterface
    public boolean isNative() {
        return true;
    }

    @JavascriptInterface
    public String getInfo() {
        try {
            return new JSONObject()
                    .put("app", "high-court-rfid")
                    .put("version", versionName)
                    .put("available", available)
                    .put("reading", reading)
                    .toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    @JavascriptInterface
    public void startReading() {
        reader.startReading();
    }

    @JavascriptInterface
    public void stopReading() {
        reader.stopReading();
    }

    // ---- called by MainActivity (main thread) ----

    public void setState(boolean available, boolean reading) {
        this.available = available;
        this.reading = reading;
    }

    public static void sendTags(WebView web, List<UhfReader.Tag> tags) {
        try {
            JSONArray arr = new JSONArray();
            for (UhfReader.Tag t : tags) {
                arr.put(new JSONObject()
                        .put("epc", t.epc)
                        .put("rssi", t.rssi)
                        .put("count", t.count));
            }
            JSONObject detail = new JSONObject()
                    .put("source", SOURCE)
                    .put("ts", System.currentTimeMillis())
                    .put("tags", arr);
            dispatch(web, "rfid:read", detail);
        } catch (JSONException ignored) {
        }
    }

    public static void sendState(WebView web, boolean available, boolean reading, String message) {
        try {
            JSONObject detail = new JSONObject()
                    .put("available", available)
                    .put("reading", reading)
                    .put("message", message);
            dispatch(web, "rfid:state", detail);
        } catch (JSONException ignored) {
        }
    }

    private static void dispatch(WebView web, String name, JSONObject detail) {
        // JSONObject output is valid JavaScript, so it can be embedded directly.
        web.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('" + name + "',{detail:" + detail + "}));",
                null);
    }
}
