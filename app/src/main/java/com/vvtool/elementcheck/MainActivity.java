package com.vvtool.elementcheck;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

public class MainActivity extends Activity {
    private LinearLayout hero;
    private TextView statusTitle, statusSub, heroAction;
    private Switch overlaySwitch;
    private View projectionCard;
    private boolean running;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        col.setPadding(pad, Ui.dp(this, 24), pad, pad);

        col.addView(Ui.text(this, "ElementCheck", 30, Ui.TEXT, true));
        col.addView(Ui.text(this, "UI inspector · port " + ECAccessibilityService.PORT, 14, Ui.TEXT_2, false));

        hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(pad, pad, pad, pad);
        statusTitle = Ui.text(this, "", 22, 0xFFFFFFFF, true);
        statusSub = Ui.text(this, "", 15, 0xE6FFFFFF, false);
        statusSub.setTextIsSelectable(true);
        statusSub.setLineSpacing(0, 1.2f);
        heroAction = Ui.text(this, "", 15, Ui.PRIMARY, true);
        heroAction.setGravity(Gravity.CENTER);
        heroAction.setMinHeight(Ui.dp(this, 48));
        heroAction.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), 0);
        heroAction.setBackground(Ui.ripple(Ui.rounded(0xFFFFFFFF, Ui.dp(this, 24)), 0x221A56DB));
        heroAction.setOnClickListener(v -> startActivity(
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        hero.addView(statusTitle);
        hero.addView(statusSub, Ui.matchWrap(this, 6));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ap.topMargin = Ui.dp(this, 18);
        hero.addView(heroAction, ap);
        col.addView(hero, Ui.matchWrap(this, 24));

        LinearLayout overlayCard = Ui.card(this);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(this, "Floating overlay", 17, Ui.TEXT, true));
        texts.addView(Ui.text(this, "Capture button over any app", 13, Ui.TEXT_2, false));
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        overlaySwitch = new Switch(this);
        int[][] states = {{android.R.attr.state_checked}, {}};
        overlaySwitch.setThumbTintList(new ColorStateList(states, new int[]{Ui.PRIMARY, 0xFFFFFFFF}));
        overlaySwitch.setTrackTintList(new ColorStateList(states, new int[]{0xFF9DB8F2, 0xFFCBD5E1}));
        overlaySwitch.setOnCheckedChangeListener((b, on) -> {
            Prefs.setOverlayEnabled(this, on);
            ECAccessibilityService s = ECAccessibilityService.get();
            if (s != null) s.setOverlayVisible(on);
        });
        row.addView(overlaySwitch);
        overlayCard.addView(row);
        col.addView(overlayCard, Ui.matchWrap(this, 16));

        LinearLayout pc = Ui.card(this);
        pc.addView(Ui.text(this, "Screen capture", 17, Ui.TEXT, true));
        pc.addView(Ui.text(this, "Required on Android 10 and below", 13, Ui.TEXT_2, false));
        pc.addView(Ui.pill(this, "Grant", false, v ->
                startActivity(new Intent(this, ProjectionRequestActivity.class))), Ui.matchWrap(this, 14));
        projectionCard = pc;
        col.addView(pc, Ui.matchWrap(this, 16));

        LinearLayout cc = Ui.card(this);
        cc.setOrientation(LinearLayout.HORIZONTAL);
        cc.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout ct = new LinearLayout(this);
        ct.setOrientation(LinearLayout.VERTICAL);
        ct.addView(Ui.text(this, "Captures", 17, Ui.TEXT, true));
        ct.addView(Ui.text(this, "Saved screenshots and hierarchies", 13, Ui.TEXT_2, false));
        cc.addView(ct, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        cc.addView(Ui.text(this, "›", 28, Ui.TEXT_2, false));
        cc.setBackground(Ui.ripple(Ui.rounded(Ui.SURFACE, Ui.dp(this, 24)), 0x141A56DB));
        cc.setOnClickListener(v -> startActivity(new Intent(this, CapturesActivity.class)));
        col.addView(cc, Ui.matchWrap(this, 16));

        if (Build.VERSION.SDK_INT >= 33) {
            TextView restricted = Ui.text(this, "Service greyed out? App info → ⋮ → Allow restricted settings",
                    13, Ui.PRIMARY, false);
            restricted.setPadding(Ui.dp(this, 4), Ui.dp(this, 20), Ui.dp(this, 4), 0);
            restricted.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()))));
            col.addView(restricted);
        }

        TextView adb = Ui.text(this, "adb forward tcp:" + ECAccessibilityService.PORT + " tcp:" + ECAccessibilityService.PORT,
                12, Ui.TEXT_2, false);
        adb.setTypeface(android.graphics.Typeface.MONOSPACE);
        adb.setTextIsSelectable(true);
        adb.setPadding(Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4), 0);
        col.addView(adb);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.setClipToPadding(false);
        sv.addView(col);
        setContentView(sv);
        Ui.edgeToEdge(this, sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        running = ECAccessibilityService.get() != null;
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                running ? new int[]{0xFF1A56DB, 0xFF3B82F6} : new int[]{0xFF475569, 0xFF64748B});
        bg.setCornerRadius(Ui.dp(this, 28));
        hero.setBackground(bg);
        if (running) {
            statusTitle.setText("Running");
            StringBuilder sb = new StringBuilder();
            for (String ip : localIps()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("http://").append(ip).append(':').append(ECAccessibilityService.PORT);
            }
            statusSub.setText(sb.length() > 0 ? sb : "http://localhost:" + ECAccessibilityService.PORT);
            heroAction.setText("Settings");
        } else {
            statusTitle.setText("Service off");
            statusSub.setText("Enable ElementCheck in Accessibility");
            heroAction.setText("Enable");
        }
        heroAction.setTextColor(running ? Ui.PRIMARY : 0xFF475569);
        overlaySwitch.setChecked(Prefs.overlayEnabled(this));
        projectionCard.setVisibility(Build.VERSION.SDK_INT < 30 && !ProjectionService.isRunning() ? View.VISIBLE : View.GONE);
    }

    private static java.util.List<String> localIps() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) out.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) { }
        return out;
    }
}
