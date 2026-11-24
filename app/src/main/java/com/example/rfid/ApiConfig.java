package com.example.rfid;

import android.net.Uri;

/**
 * SINGLE PLACE for every backend address and request setting used by the native app.
 *
 * If a search fails with "Cannot reach the server", the address in this file is the first
 * thing to check. Change it here, rebuild, and reinstall.
 *
 * How the final request URL is built:
 *
 *   http://<dashboard host>:<API_PORT><FILES_PATH>?<SEARCH_PARAM>=<what the user typed>&limit=<SEARCH_LIMIT>
 *
 * Example, with the dashboard saved as http://173.20.10.3:5173 and the user typing 001:
 *
 *   http://173.20.10.3:4000/api/files?search=001&limit=50
 *
 * The host is taken from the dashboard address saved in the app (the setup screen), so when
 * the laptop's IP changes you only change it there. Only the port is fixed below.
 */
public final class ApiConfig {

    private ApiConfig() {}

    /** Port the Node/Express backend listens on (the dashboard itself runs on 5173). */
    public static final int API_PORT = 4000;

    /**
     * Leave empty to build the address from the dashboard host + API_PORT (normal case).
     * Set a full base such as "http://173.20.10.3:4000" to force one fixed backend address.
     */
    public static final String API_BASE_OVERRIDE = "";

    /** Route of the files list on the backend (see backend/routes/fileRoutes.js, GET "/"). */
    public static final String FILES_PATH = "/api/files/locate";

    /** Query parameter the files list uses for free-text search. */
    public static final String SEARCH_PARAM = "q";

    /** How many results to ask for, so an exact match is not pushed off the first page. */
    public static final int SEARCH_LIMIT = 50;

    public static final int CONNECT_TIMEOUT_MS = 5000;
    public static final int READ_TIMEOUT_MS = 8000;

    /**
     * Where the logged-in dashboard keeps its login token in the WebView's browser storage.
     * The backend routes require authentication, so the app borrows the token of the session
     * already logged in inside the dashboard. If your frontend stores it under another name,
     * add that name here.
     */
    public static final String[] TOKEN_STORAGE_KEYS = {
            "token", "authToken", "accessToken", "access_token", "jwt", "auth-storage", "auth"
    };

    /** Base address of the backend, or null if no valid dashboard address is available. */
    public static String apiBase(String dashboardUrl) {
        if (API_BASE_OVERRIDE != null && !API_BASE_OVERRIDE.trim().isEmpty()) {
            return stripTrailingSlash(API_BASE_OVERRIDE.trim());
        }
        if (dashboardUrl == null || dashboardUrl.trim().isEmpty()) return null;
        Uri u = Uri.parse(dashboardUrl.trim());
        String host = u.getHost();
        String scheme = u.getScheme();
        if (host == null || host.isEmpty() || scheme == null) return null;
        return scheme + "://" + host + ":" + API_PORT;
    }

    /** Full URL used to look a file up. */
    public static String searchUrl(String apiBase, String query) {
        return apiBase + FILES_PATH
                + "?" + SEARCH_PARAM + "=" + Uri.encode(query)
                + "&limit=" + SEARCH_LIMIT;
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
