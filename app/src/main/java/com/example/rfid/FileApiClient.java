package com.example.rfid;

import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Looks a file up on the dashboard backend. All real data comes from the server response;
 * nothing is invented here. Addresses live in {@link ApiConfig}.
 */
public final class FileApiClient {

    private FileApiClient() {}

    /** The fields the radar screen needs. An empty string means the server had no value. */
    public static final class FileInfo {
        public final String fileId;
        public final String fileName;
        public final String caseId;
        public final String rfidTag;
        public final String storageLocation;

        FileInfo(String fileId, String fileName, String caseId, String rfidTag, String storageLocation) {
            this.fileId = fileId;
            this.fileName = fileName;
            this.caseId = caseId;
            this.rfidTag = rfidTag;
            this.storageLocation = storageLocation;
        }
    }

    public enum Status { FOUND, NOT_FOUND, AMBIGUOUS, UNAUTHORIZED, SERVER_ERROR, NETWORK_ERROR }

    public static final class Result {
        public final Status status;
        public final FileInfo file;      // only when FOUND
        public final String message;     // user-facing text for every status except FOUND

        Result(Status status, FileInfo file, String message) {
            this.status = status;
            this.file = file;
            this.message = message;
        }
    }

    public interface Callback {
        /** Always delivered on the main thread. */
        void onResult(Result result);
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * @param apiBase base address from {@link ApiConfig#apiBase}
     * @param query   exactly what the user typed
     * @param token   login token read from the dashboard session, or null
     */
    public static void search(String apiBase, String query, String token, Callback callback) {
        final String cookie = safeCookie(apiBase);
        IO.execute(() -> {
            Result r = doSearch(apiBase, query, token, cookie);
            MAIN.post(() -> callback.onResult(r));
        });
    }

    private static String safeCookie(String apiBase) {
        try {
            return CookieManager.getInstance().getCookie(apiBase);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Result doSearch(String apiBase, String query, String token, String cookie) {
        String endpoint = ApiConfig.searchUrl(apiBase, query);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(ApiConfig.CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(ApiConfig.READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (cookie != null && !cookie.isEmpty()) {
                conn.setRequestProperty("Cookie", cookie);
            }

            int code = conn.getResponseCode();
            if (code == 401 || code == 403) {
                return new Result(Status.UNAUTHORIZED, null,
                        "Not authorised. Log in to the dashboard first, then search again.");
            }
            if (code < 200 || code >= 300) {
                return new Result(Status.SERVER_ERROR, null,
                        "The server returned an error (HTTP " + code + "). Please try again.");
            }

            String body = readAll(conn.getInputStream());
            JSONArray items = extractItems(body);
            return interpret(items, query);

        } catch (UnknownHostException | ConnectException e) {
            return new Result(Status.NETWORK_ERROR, null,
                    "Cannot reach the server at " + apiBase
                            + ". Check the Wi-Fi connection and that the backend is running.");
        } catch (SocketTimeoutException e) {
            return new Result(Status.NETWORK_ERROR, null,
                    "The server at " + apiBase + " did not respond in time. Please try again.");
        } catch (Throwable t) {
            return new Result(Status.SERVER_ERROR, null,
                    "The server response could not be read. Please try again.");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Accepts {items:[...]} (what the dashboard list returns) or a bare array. */
    private static JSONArray extractItems(String body) throws Exception {
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.startsWith("[")) return new JSONArray(trimmed);
        JSONObject obj = new JSONObject(trimmed);
        JSONArray items = obj.optJSONArray("items");
        return items != null ? items : new JSONArray();
    }

    private static Result interpret(JSONArray items, String query) {
        String q = query.trim();
        int count = items.length();

        if (count == 0) {
            return notFound(q);
        }

        // 1. Exact match on file id, case id or RFID tag wins.
        for (int i = 0; i < count; i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            if (equalsIgnoreCase(pick(o, "fileId", "fileNumber"), q)
                    || equalsIgnoreCase(pick(o, "caseId", "caseNumber"), q)
                    || equalsIgnoreCase(pick(o, "rfidTag", "epc"), q)) {
                return new Result(Status.FOUND, toInfo(o), null);
            }
        }

        // 2. Exactly one partial match: use it. The radar screen shows the full File ID,
        //    so the operator can see which file was selected.
        if (count == 1) {
            JSONObject o = items.optJSONObject(0);
            if (o != null) return new Result(Status.FOUND, toInfo(o), null);
        }

        // 3. Several partial matches and none exact: do not guess.
        return new Result(Status.AMBIGUOUS, null,
                count + " files match \"" + q + "\". Enter the complete File ID.");
    }

    private static Result notFound(String q) {
        return new Result(Status.NOT_FOUND, null,
                "File not found. No file matches \"" + q
                        + "\". Check the File ID and try again.");
    }

    private static FileInfo toInfo(JSONObject o) {
        return new FileInfo(
                pick(o, "fileId", "fileNumber"),
                pick(o, "fileName", "name", "title"),
                pick(o, "caseId", "caseNumber"),
                pick(o, "rfidTag", "epc"),
                pick(o, "storageLocation", "rackLocation", "location"));
    }

    /** First non-empty value among the given keys; objects are flattened to readable text. */
    private static String pick(JSONObject o, String... keys) {
        for (String k : keys) {
            if (!o.has(k) || o.isNull(k)) continue;
            Object v = o.opt(k);
            String s = null;
            if (v instanceof JSONObject) {
                s = flatten((JSONObject) v);
            } else if (v != null) {
                s = String.valueOf(v).trim();
            }
            if (s != null && !s.isEmpty()) return s;
        }
        return "";
    }

    private static String flatten(JSONObject obj) {
        StringBuilder sb = new StringBuilder();
        Iterator<String> it = obj.keys();
        while (it.hasNext()) {
            Object v = obj.opt(it.next());
            if (v == null || v == JSONObject.NULL) continue;
            String s = String.valueOf(v).trim();
            if (s.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" / ");
            sb.append(s);
        }
        return sb.toString();
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return a != null && !a.isEmpty() && a.trim().toLowerCase(Locale.ROOT).equals(b.toLowerCase(Locale.ROOT));
    }

    private static String readAll(InputStream in) throws Exception {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }
}
