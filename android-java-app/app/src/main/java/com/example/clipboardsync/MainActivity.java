package com.example.clipboardsync;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String PREFS_NAME = "clipboardmig";
    private static final String KEY_TRANSPORT_MODE = "transport_mode";
    private static final String KEY_WS_URL = "ws_url";
    private static final String KEY_FIREBASE_DB_URL = "firebase_db_url";
    private static final String KEY_FIREBASE_ROOM = "firebase_room";
    private static final String DEFAULT_WS_URL = "ws://192.168.60.109:8080?room=demo&token=changeme";
    private static final String DEFAULT_FIREBASE_DB_URL = "https://clipboardmig-default-rtdb.firebaseio.com";
    private static final String DEFAULT_FIREBASE_ROOM = "demo";

    private static final int COLOR_BG = Color.rgb(238, 242, 247);
    private static final int COLOR_PANEL = Color.WHITE;
    private static final int COLOR_TEXT = Color.rgb(17, 24, 39);
    private static final int COLOR_MUTED = Color.rgb(100, 116, 139);
    private static final int COLOR_BLUE = Color.rgb(37, 99, 235);
    private static final int COLOR_BLUE_SOFT = Color.rgb(219, 234, 254);
    private static final int COLOR_GREEN = Color.rgb(22, 163, 74);
    private static final int COLOR_GREEN_SOFT = Color.rgb(220, 252, 231);
    private static final int COLOR_RED = Color.rgb(220, 38, 38);
    private static final int COLOR_BORDER = Color.rgb(219, 227, 239);

    private Spinner transportModeInput;
    private EditText wsUrlInput;
    private EditText firebaseDbUrlInput;
    private EditText firebaseRoomInput;
    private LinearLayout firebaseFields;
    private LinearLayout websocketFields;
    private View syncBeam;
    private TextView statusText;
    private TextView savedCountText;
    private TextView statusBadge;
    private SharedPreferences prefs;
    private ClipboardHistoryStore historyStore;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        historyStore = new ClipboardHistoryStore(this);

        requestNotificationPermission();
        setSystemBars();
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSavedCount();
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(COLOR_BG);

        LinearLayout root = verticalLayout();
        root.setPadding(dp(18), dp(22), dp(18), dp(22));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        root.addView(createHeader(), matchWrap());
        root.addView(createMotionPanel(), matchWrapWithTopMargin(12));
        root.addView(createStatusPanel(), matchWrapWithTopMargin(18));
        root.addView(createConnectionPanel(), matchWrapWithTopMargin(12));
        root.addView(createActionsPanel(), matchWrapWithTopMargin(12));

        setContentView(scrollView);
        refreshSavedCount();
        runEntryAnimations(root);
        startAmbientAnimations();
    }

    private LinearLayout createHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleBlock = verticalLayout();

        TextView title = text("ClipboardMig", 28, COLOR_TEXT, Typeface.BOLD);
        titleBlock.addView(title, matchWrap());

        TextView subtitle = text("Phone to PC clipboard handoff", 13, COLOR_MUTED, Typeface.NORMAL);
        subtitle.setPadding(0, dp(2), 0, 0);
        titleBlock.addView(subtitle, matchWrap());

        header.addView(titleBlock, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        statusBadge = text("Ready", 12, COLOR_GREEN, Typeface.BOLD);
        statusBadge.setGravity(Gravity.CENTER);
        statusBadge.setPadding(dp(12), dp(7), dp(12), dp(7));
        statusBadge.setBackground(roundedStroke(COLOR_GREEN_SOFT, COLOR_GREEN, 999));
        header.addView(statusBadge, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        return header;
    }

    private LinearLayout createMotionPanel() {
        LinearLayout panel = panel();
        panel.addView(label("CLOUD SYNC"), matchWrap());

        TextView title = text("Fast handoff, no extra steps", 18, COLOR_TEXT, Typeface.BOLD);
        title.setPadding(0, dp(8), 0, 0);
        panel.addView(title, matchWrap());

        TextView hint = text("Keep the default cloud relay, use the same Sync Code on both devices, and start copying.", 13, COLOR_MUTED, Typeface.NORMAL);
        hint.setPadding(0, dp(8), 0, 0);
        panel.addView(hint, matchWrap());

        FrameLayout track = new FrameLayout(this);
        track.setPadding(0, 0, 0, 0);
        track.setBackground(rounded(Color.rgb(231, 237, 247), 999));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(12)
        );
        params.topMargin = dp(14);

        syncBeam = new View(this);
        GradientDrawable beam = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] { COLOR_BLUE, Color.rgb(245, 140, 74) }
        );
        beam.setCornerRadius(dp(999));
        syncBeam.setBackground(beam);
        FrameLayout.LayoutParams beamParams = new FrameLayout.LayoutParams(dp(84), FrameLayout.LayoutParams.MATCH_PARENT);
        track.addView(syncBeam, beamParams);

        panel.addView(track, params);
        return panel;
    }

    private LinearLayout createStatusPanel() {
        LinearLayout panel = panel();

        TextView label = label("SAVED ON PHONE");
        panel.addView(label, matchWrap());

        savedCountText = text("0 items saved", 24, COLOR_TEXT, Typeface.BOLD);
        savedCountText.setPadding(0, dp(6), 0, 0);
        panel.addView(savedCountText, matchWrap());

        statusText = text("Idle", 14, COLOR_MUTED, Typeface.NORMAL);
        statusText.setPadding(0, dp(8), 0, 0);
        panel.addView(statusText, matchWrap());

        return panel;
    }

    private LinearLayout createConnectionPanel() {
        LinearLayout panel = panel();

        panel.addView(label("SYNC MODE"), matchWrap());

        transportModeInput = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                new String[] { "Cloud Sync", "Local Network" }
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        transportModeInput.setAdapter(adapter);
        transportModeInput.setBackground(roundedStroke(Color.WHITE, Color.rgb(203, 213, 225), 8));
        transportModeInput.setPadding(dp(8), 0, dp(8), 0);
        panel.addView(transportModeInput, matchWrapWithTopMargin(8));

        firebaseFields = verticalLayout();
        firebaseFields.addView(label("CLOUD RELAY URL"), matchWrapWithTopMargin(12));

        firebaseDbUrlInput = new EditText(this);
        firebaseDbUrlInput.setSingleLine(true);
        firebaseDbUrlInput.setText(prefOrDefault(KEY_FIREBASE_DB_URL, DEFAULT_FIREBASE_DB_URL));
        firebaseDbUrlInput.setHint("https://your-project-default-rtdb.firebaseio.com");
        styleInput(firebaseDbUrlInput);
        firebaseFields.addView(firebaseDbUrlInput, matchWrapWithTopMargin(8));

        TextView relayHint = text("You usually only need to change the Sync Code below.", 12, COLOR_MUTED, Typeface.NORMAL);
        relayHint.setPadding(0, dp(8), 0, 0);
        firebaseFields.addView(relayHint, matchWrap());

        firebaseFields.addView(label("SYNC CODE"), matchWrapWithTopMargin(12));

        firebaseRoomInput = new EditText(this);
        firebaseRoomInput.setSingleLine(true);
        firebaseRoomInput.setText(prefOrDefault(KEY_FIREBASE_ROOM, DEFAULT_FIREBASE_ROOM));
        firebaseRoomInput.setHint("demo");
        styleInput(firebaseRoomInput);
        firebaseFields.addView(firebaseRoomInput, matchWrapWithTopMargin(8));

        panel.addView(firebaseFields, matchWrap());

        websocketFields = verticalLayout();
        websocketFields.addView(label("LOCAL SERVER URL"), matchWrapWithTopMargin(12));

        wsUrlInput = new EditText(this);
        wsUrlInput.setSingleLine(true);
        wsUrlInput.setText(prefOrDefault(KEY_WS_URL, DEFAULT_WS_URL));
        wsUrlInput.setHint("ws://PC_IP:8080?room=demo&token=changeme");
        styleInput(wsUrlInput);
        websocketFields.addView(wsUrlInput, matchWrapWithTopMargin(8));
        panel.addView(websocketFields, matchWrap());

        String mode = prefs.getString(KEY_TRANSPORT_MODE, ClipboardSyncService.TRANSPORT_FIREBASE);
        transportModeInput.setSelection(ClipboardSyncService.TRANSPORT_WEBSOCKET.equals(mode) ? 1 : 0);
        transportModeInput.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateRelayFields();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        updateRelayFields();

        return panel;
    }

    private LinearLayout createActionsPanel() {
        LinearLayout panel = panel();
        panel.addView(label("ACTIONS"), matchWrap());

        Button startButton = new Button(this);
        styleButton(startButton, "Start Capture", COLOR_GREEN, Color.WHITE);
        startButton.setOnClickListener(view -> startSyncService(ClipboardSyncService.ACTION_START));
        panel.addView(startButton, matchWrapWithTopMargin(10));

        Button syncHistoryButton = new Button(this);
        styleButton(syncHistoryButton, "Sync Saved History", COLOR_BLUE, Color.WHITE);
        syncHistoryButton.setOnClickListener(view -> startSyncService(ClipboardSyncService.ACTION_SYNC_HISTORY));
        panel.addView(syncHistoryButton, matchWrapWithTopMargin(8));

        Button sendNowButton = new Button(this);
        styleButton(sendNowButton, "Send Current Clipboard", COLOR_BLUE_SOFT, COLOR_BLUE);
        sendNowButton.setOnClickListener(view -> startSyncService(ClipboardSyncService.ACTION_SEND_NOW));
        panel.addView(sendNowButton, matchWrapWithTopMargin(8));

        Button stopButton = new Button(this);
        styleButton(stopButton, "Stop Capture", Color.rgb(254, 226, 226), COLOR_RED);
        stopButton.setOnClickListener(view -> stopSyncService());
        panel.addView(stopButton, matchWrapWithTopMargin(8));

        return panel;
    }

    private void startSyncService(String action) {
        String mode = selectedTransportMode();
        String wsUrl = wsUrlInput.getText().toString().trim();
        String firebaseDbUrl = trimTrailingSlash(firebaseDbUrlInput.getText().toString());
        String firebaseRoom = sanitizeRoom(firebaseRoomInput.getText().toString());

        if (ClipboardSyncService.TRANSPORT_FIREBASE.equals(mode)) {
            if (!firebaseDbUrl.startsWith("https://")) {
                Toast.makeText(this, "Cloud Relay URL must start with https://", Toast.LENGTH_LONG).show();
                return;
            }
        } else if (!wsUrl.startsWith("ws://") && !wsUrl.startsWith("wss://")) {
            Toast.makeText(this, "WebSocket URL must start with ws:// or wss://", Toast.LENGTH_LONG).show();
            return;
        }

        prefs.edit()
                .putString(KEY_TRANSPORT_MODE, mode)
                .putString(KEY_WS_URL, wsUrl)
                .putString(KEY_FIREBASE_DB_URL, firebaseDbUrl)
                .putString(KEY_FIREBASE_ROOM, firebaseRoom)
                .apply();

        Intent intent = new Intent(this, ClipboardSyncService.class);
        intent.setAction(action);
        intent.putExtra(ClipboardSyncService.EXTRA_TRANSPORT_MODE, mode);
        intent.putExtra(ClipboardSyncService.EXTRA_WS_URL, wsUrl);
        intent.putExtra(ClipboardSyncService.EXTRA_FIREBASE_DB_URL, firebaseDbUrl);
        intent.putExtra(ClipboardSyncService.EXTRA_FIREBASE_ROOM, firebaseRoom);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        if (ClipboardSyncService.ACTION_SEND_NOW.equals(action)) {
            updateStatus("Sending current clipboard", "Sending", COLOR_BLUE, COLOR_BLUE_SOFT);
        } else if (ClipboardSyncService.ACTION_SYNC_HISTORY.equals(action)) {
            updateStatus("Syncing saved phone history", "Syncing", COLOR_BLUE, COLOR_BLUE_SOFT);
        } else {
            updateStatus("Capture running via " + relayLabel(), "Active", COLOR_GREEN, COLOR_GREEN_SOFT);
        }

        refreshSavedCount();
    }

    private void stopSyncService() {
        Intent intent = new Intent(this, ClipboardSyncService.class);
        intent.setAction(ClipboardSyncService.ACTION_STOP);
        startService(intent);
        updateStatus("Stopped", "Stopped", COLOR_RED, Color.rgb(254, 226, 226));
        refreshSavedCount();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 100);
        }
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams matchWrapWithTopMargin(int topMarginDp) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(topMarginDp);
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void refreshSavedCount() {
        if (savedCountText == null || historyStore == null) {
            return;
        }

        int count = historyStore.count();
        savedCountText.setText(count + " saved item" + (count == 1 ? "" : "s"));
    }

    private void updateStatus(String status, String badge, int badgeTextColor, int badgeBgColor) {
        if (statusText != null) {
            statusText.setText(status);
        }

        if (statusBadge != null) {
            statusBadge.setText(badge);
            statusBadge.setTextColor(badgeTextColor);
            statusBadge.setBackground(roundedStroke(badgeBgColor, badgeTextColor, 999));
        }
    }

    private void setSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(COLOR_BG);
            getWindow().setNavigationBarColor(COLOR_BG);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    private LinearLayout verticalLayout() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout panel() {
        LinearLayout panel = verticalLayout();
        panel.setPadding(dp(14), dp(14), dp(14), dp(14));
        panel.setBackground(roundedStroke(COLOR_PANEL, COLOR_BORDER, 10));
        return panel;
    }

    private TextView label(String value) {
        TextView textView = text(value, 12, COLOR_MUTED, Typeface.BOLD);
        textView.setAllCaps(false);
        return textView;
    }

    private TextView text(String value, int sizeSp, int color, int style) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(sizeSp);
        textView.setTextColor(color);
        textView.setTypeface(Typeface.DEFAULT, style);
        textView.setIncludeFontPadding(true);
        return textView;
    }

    private void styleButton(Button button, String label, int bgColor, int textColor) {
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(textColor);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(46));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(rounded(bgColor, 9));
    }

    private void styleInput(EditText input) {
        input.setTextSize(14);
        input.setTextColor(COLOR_TEXT);
        input.setHintTextColor(COLOR_MUTED);
        input.setPadding(dp(12), dp(9), dp(12), dp(9));
        input.setBackground(roundedStroke(Color.WHITE, Color.rgb(203, 213, 225), 8));
    }

    private void updateRelayFields() {
        boolean firebaseMode = ClipboardSyncService.TRANSPORT_FIREBASE.equals(selectedTransportMode());
        firebaseFields.setVisibility(firebaseMode ? View.VISIBLE : View.GONE);
        websocketFields.setVisibility(firebaseMode ? View.GONE : View.VISIBLE);
    }

    private String selectedTransportMode() {
        return transportModeInput != null && transportModeInput.getSelectedItemPosition() == 1
                ? ClipboardSyncService.TRANSPORT_WEBSOCKET
                : ClipboardSyncService.TRANSPORT_FIREBASE;
    }

    private void runEntryAnimations(LinearLayout root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            child.setAlpha(0f);
            child.setTranslationY(dp(18));
            child.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(420)
                    .setStartDelay(i * 80L)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    private void startAmbientAnimations() {
        if (syncBeam != null) {
            syncBeam.post(() -> {
                View parent = (View) syncBeam.getParent();
                float start = -syncBeam.getWidth();
                float end = Math.max(start, parent.getWidth() - syncBeam.getWidth() / 3f);
                ObjectAnimator beamAnimator = ObjectAnimator.ofFloat(syncBeam, View.TRANSLATION_X, start, end);
                beamAnimator.setDuration(1450);
                beamAnimator.setRepeatCount(ValueAnimator.INFINITE);
                beamAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
                beamAnimator.start();
            });
        }

        if (statusBadge != null) {
            ObjectAnimator scaleX = ObjectAnimator.ofFloat(statusBadge, View.SCALE_X, 1f, 1.05f, 1f);
            ObjectAnimator scaleY = ObjectAnimator.ofFloat(statusBadge, View.SCALE_Y, 1f, 1.05f, 1f);
            scaleX.setRepeatCount(ValueAnimator.INFINITE);
            scaleY.setRepeatCount(ValueAnimator.INFINITE);
            AnimatorSet badgeAnimator = new AnimatorSet();
            badgeAnimator.playTogether(scaleX, scaleY);
            badgeAnimator.setDuration(1500);
            badgeAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
            badgeAnimator.start();
        }
    }

    private String relayLabel() {
        return ClipboardSyncService.TRANSPORT_WEBSOCKET.equals(selectedTransportMode()) ? "Local Network" : "Cloud Sync";
    }

    private String trimTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String sanitizeRoom(String value) {
        String result = value == null ? "" : value.trim();
        result = result.replaceAll("[.#$\\[\\]/]", "-");
        return result.isEmpty() ? DEFAULT_FIREBASE_ROOM : result;
    }

    private String prefOrDefault(String key, String fallback) {
        String value = prefs.getString(key, fallback);
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable roundedStroke(int color, int strokeColor, int radiusDp) {
        GradientDrawable drawable = rounded(color, radiusDp);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }
}
