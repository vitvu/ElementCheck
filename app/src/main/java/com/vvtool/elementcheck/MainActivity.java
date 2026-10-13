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
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {
    private LinearLayout hero;
    private TextView statusTitle, statusSub;
    private Row a11yRow, serverRow, overlayRow, streamRow;
    private boolean binding;

    private static final class Row {
        Switch sw;
        TextView sub;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = Ui.dp(this, 20);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
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
        hero.addView(statusTitle);
        hero.addView(statusSub, Ui.matchWrap(this, 6));
        col.addView(hero, Ui.matchWrap(this, 24));

        col.addView(section("Permissions"));
        LinearLayout perms = Ui.card(this);
        perms.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        a11yRow = row(perms, R.drawable.ic_info, "Accessibility service", this::onA11y);
        divider(perms);
        serverRow = row(perms, R.drawable.ic_tree, "Web server", this::onServer);
        divider(perms);
        overlayRow = row(perms, R.drawable.ic_drag, "Floating overlay", this::onOverlay);
        divider(perms);
        streamRow = row(perms, R.drawable.ic_camera, "Screen stream", this::onStream);
        col.addView(perms, Ui.matchWrap(this, 8));

        col.addView(section("Data"));
        LinearLayout cc = Ui.card(this);
        cc.setOrientation(LinearLayout.HORIZONTAL);
        cc.setGravity(Gravity.CENTER_VERTICAL);
        cc.addView(leading(R.drawable.ic_folder));
        LinearLayout ct = new LinearLayout(this);
        ct.setOrientation(LinearLayout.VERTICAL);
        ct.addView(Ui.text(this, "Captures", 16, Ui.TEXT, true));
        ct.addView(Ui.text(this, "Saved screenshots and hierarchies", 13, Ui.TEXT_2, false));
        cc.addView(ct, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        cc.addView(Ui.icon(this, R.drawable.ic_chevron_right, Ui.TEXT_2));
        cc.setBackground(Ui.ripple(Ui.rounded(Ui.SURFACE, Ui.dp(this, 24)), 0x141A56DB));
        cc.setOnClickListener(v -> startActivity(new Intent(this, CapturesActivity.class)));
        col.addView(cc, Ui.matchWrap(this, 8));

        if (Build.VERSION.SDK_INT >= 33) {
            TextView restricted = Ui.text(this, "Accessibility greyed out? App info → ⋮ → Allow restricted settings",
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
        refresh();
    }

    private void refresh() {
        ECAccessibilityService svc = ECAccessibilityService.get();
        boolean a11y = svc != null;
        boolean server = a11y && svc.isServerRunning();
        boolean stream = ProjectionService.isRunning();

        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                server ? new int[]{0xFF1A56DB, 0xFF3B82F6} : new int[]{0xFF475569, 0xFF64748B});
        bg.setCornerRadius(Ui.dp(this, 28));
        hero.setBackground(bg);
        if (server) {
            statusTitle.setText("Running");
            StringBuilder sb = new StringBuilder();
            for (String ip : localIps()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("http://").append(ip).append(':').append(ECAccessibilityService.PORT);
            }
            statusSub.setText(sb.length() > 0 ? sb : "http://localhost:" + ECAccessibilityService.PORT);
        } else if (a11y) {
            statusTitle.setText("Server off");
            statusSub.setText("Turn on the web server below");
        } else {
            statusTitle.setText("Service off");
            statusSub.setText("Turn on the accessibility service below");
        }

        binding = true;
        a11yRow.sw.setChecked(a11y);
        a11yRow.sub.setText(a11y ? "On · reads UI, captures screen, gestures" : "Off · required for all features");

        serverRow.sw.setChecked(server);
        serverRow.sw.setEnabled(a11y);
        serverRow.sub.setText(server ? "On · port " + ECAccessibilityService.PORT + " on local network" : "Off");

        boolean overlay = Prefs.overlayEnabled(this);
        overlayRow.sw.setChecked(overlay);
        overlayRow.sw.setEnabled(a11y);
        overlayRow.sub.setText(overlay ? "On · capture button over apps" : "Off");

        streamRow.sw.setChecked(stream);
        streamRow.sw.setEnabled(a11y);
        streamRow.sub.setText(stream ? "On · stops after 5 min idle"
                : Build.VERSION.SDK_INT < 30 ? "Off · needed for screenshots on Android 10 and below"
                : "Off · optional, smoother live control");
        binding = false;
    }

    private void onA11y(CompoundButton b, boolean on) {
        ECAccessibilityService svc = ECAccessibilityService.get();
        if (on || svc == null || !svc.turnOff()) {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        b.postDelayed(this::refresh, 400);
    }

    private void onServer(CompoundButton b, boolean on) {
        ECAccessibilityService svc = ECAccessibilityService.get();
        if (svc != null) svc.setServerEnabled(on);
        b.postDelayed(this::refresh, 200);
    }

    private void onOverlay(CompoundButton b, boolean on) {
        Prefs.setOverlayEnabled(this, on);
        ECAccessibilityService svc = ECAccessibilityService.get();
        if (svc != null) svc.setOverlayVisible(on);
        refresh();
    }

    private void onStream(CompoundButton b, boolean on) {
        if (on) startActivity(new Intent(this, ProjectionRequestActivity.class));
        else ProjectionService.stop(this);
        b.postDelayed(this::refresh, 300);
    }

    private Row row(LinearLayout parent, int icon, String title, CompoundButton.OnCheckedChangeListener l) {
        Row r = new Row();
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        line.addView(leading(icon));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(this, title, 16, Ui.TEXT, true));
        r.sub = Ui.text(this, "", 13, Ui.TEXT_2, false);
        texts.addView(r.sub);
        line.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.sw = new Switch(this);
        int[][] states = {{android.R.attr.state_checked}, {}};
        r.sw.setThumbTintList(new ColorStateList(states, new int[]{Ui.PRIMARY, 0xFFFFFFFF}));
        r.sw.setTrackTintList(new ColorStateList(states, new int[]{0xFF9DB8F2, 0xFFCBD5E1}));
        r.sw.setOnCheckedChangeListener((b, on) -> { if (!binding) l.onCheckedChanged(b, on); });
        line.addView(r.sw);
        line.setOnClickListener(v -> { if (r.sw.isEnabled()) r.sw.toggle(); });
        parent.addView(line);
        return r;
    }

    private void divider(LinearLayout parent) {
        View v = new View(this);
        v.setBackgroundColor(Ui.OUTLINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.leftMargin = Ui.dp(this, 76);
        parent.addView(v, lp);
    }

    private TextView section(String title) {
        TextView t = Ui.text(this, title, 13, Ui.TEXT_2, true);
        t.setPadding(Ui.dp(this, 8), Ui.dp(this, 24), 0, 0);
        return t;
    }

    private View leading(int icon) {
        ImageView iv = Ui.iconButton(this, icon, Ui.PRIMARY, Ui.PRIMARY_SOFT, null);
        iv.setClickable(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44));
        lp.rightMargin = Ui.dp(this, 16);
        iv.setLayoutParams(lp);
        return iv;
    }

    private static List<String> localIps() {
        List<String> out = new ArrayList<>();
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
