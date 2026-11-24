package com.example.rfid;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * The "Locate File" search box. The user types a File ID, the request goes to the dashboard
 * backend (addresses in {@link ApiConfig}), and the dialog either opens the radar with the
 * real record or shows the reason it could not.
 */
public final class FileSearchDialog {

    /** Supplies the dashboard login token (read from the WebView). May return null. */
    public interface TokenSource {
        void get(TokenCallback callback);
    }

    public interface TokenCallback {
        void onToken(String tokenOrNull);
    }

    public interface OnFileFound {
        void onFileFound(FileApiClient.FileInfo file);
    }

    private final Activity activity;
    private final String dashboardUrl;
    private final TokenSource tokenSource;
    private final OnFileFound onFileFound;

    private AlertDialog dialog;
    private EditText input;
    private TextView message;
    private View progress;
    private View submit;
    private View cancel;
    private boolean busy;

    public FileSearchDialog(Activity activity, String dashboardUrl,
                            TokenSource tokenSource, OnFileFound onFileFound) {
        this.activity = activity;
        this.dashboardUrl = dashboardUrl;
        this.tokenSource = tokenSource;
        this.onFileFound = onFileFound;
    }

    public void show() {
        View content = LayoutInflater.from(activity).inflate(R.layout.dialog_file_search, null);
        input = content.findViewById(R.id.search_input);
        message = content.findViewById(R.id.search_message);
        progress = content.findViewById(R.id.search_progress);
        submit = content.findViewById(R.id.search_submit);
        cancel = content.findViewById(R.id.search_cancel);

        dialog = new MaterialAlertDialogBuilder(activity)
                .setView(content)
                .create();

        submit.setOnClickListener(v -> runSearch());
        cancel.setOnClickListener(v -> dialog.dismiss());
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch();
                return true;
            }
            return false;
        });

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                            | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        input.requestFocus();
    }

    private void runSearch() {
        if (busy) return;

        final String query = input.getText().toString().trim();
        if (query.isEmpty()) {
            showMessage(activity.getString(R.string.search_empty));
            return;
        }

        final String apiBase = ApiConfig.apiBase(dashboardUrl);
        if (apiBase == null) {
            showMessage(activity.getString(R.string.search_no_address));
            return;
        }

        setBusy(true);
        tokenSource.get(token ->
                FileApiClient.search(apiBase, query, token, result -> {
                    if (!dialog.isShowing()) return; // user cancelled while waiting
                    setBusy(false);
                    if (result.status == FileApiClient.Status.FOUND) {
                        dialog.dismiss();
                        onFileFound.onFileFound(result.file);
                    } else {
                        showMessage(result.message);
                        input.requestFocus();
                        input.selectAll();
                    }
                }));
    }

    private void setBusy(boolean value) {
        busy = value;
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        submit.setEnabled(!value);
        input.setEnabled(!value);
        if (value) message.setVisibility(View.GONE);
    }

    private void showMessage(String text) {
        message.setText(text);
        message.setVisibility(View.VISIBLE);
    }
}
