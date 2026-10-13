package com.vvtool.elementcheck;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
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
    private TextView status, address;
    private Switch overlaySwitch;
    private LinearLayout projectionCard;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        col.setPadding(pad, pad, pad, pad);

        // Service
        LinearLayout c1 = Ui.card(this);
        c1.addView(Ui.text(this, "Dịch vụ Trợ năng", 17, 0xFF0F172A));
        status = Ui.text(this, "", 14, 0xFF475569);
        c1.addView(status);
        c1.addView(Ui.button(this, "Mở cài đặt Trợ năng", v -> startActivity(
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))));
        if (Build.VERSION.SDK_INT >= 33) {
            c1.addView(Ui.text(this, "Android 13+: nếu dịch vụ bị mờ (\"Restricted setting\"), mở Thông tin ứng dụng → menu ⋮ → \"Allow restricted settings\" rồi bật lại.",
                    12, 0xFF64748B));
            c1.addView(Ui.button(this, "Thông tin ứng dụng", v -> startActivity(
                    new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())))));
        }
        overlaySwitch = new Switch(this);
        overlaySwitch.setText("Hiện overlay nổi (nút chụp)");
        overlaySwitch.setPadding(0, Ui.dp(this, 10), 0, 0);
        overlaySwitch.setOnCheckedChangeListener((b, on) -> {
            Prefs.setOverlayEnabled(this, on);
            ECAccessibilityService s = ECAccessibilityService.get();
            if (s != null) s.setOverlayVisible(on);
        });
        c1.addView(overlaySwitch);
        col.addView(c1);

        // Screen capture permission (Android 6-10 only)
        projectionCard = Ui.card(this);
        projectionCard.addView(Ui.text(this, "Quyền chụp màn hình (Android ≤ 10)", 17, 0xFF0F172A));
        projectionCard.addView(Ui.text(this, "Android 10 trở xuống cần cấp MediaProjection một lần mỗi lần khởi động dịch vụ.", 12, 0xFF64748B));
        projectionCard.addView(Ui.button(this, "Cấp quyền chụp màn hình", v ->
                startActivity(new Intent(this, ProjectionRequestActivity.class))));
        col.addView(projectionCard);

        // Web
        LinearLayout c2 = Ui.card(this);
        c2.addView(Ui.text(this, "Web Inspector (cổng " + ECAccessibilityService.PORT + ")", 17, 0xFF0F172A));
        address = Ui.text(this, "", 14, 0xFF2563EB);
        address.setTextIsSelectable(true);
        c2.addView(address);
        c2.addView(Ui.text(this, "Qua USB: adb forward tcp:" + ECAccessibilityService.PORT + " tcp:" + ECAccessibilityService.PORT
                + "  →  http://localhost:" + ECAccessibilityService.PORT, 12, 0xFF64748B));
        col.addView(c2);

        LinearLayout c3 = Ui.card(this);
        c3.addView(Ui.text(this, "Ảnh chụp đã lưu", 17, 0xFF0F172A));
        c3.addView(Ui.button(this, "Xem captures", v -> startActivity(new Intent(this, CapturesActivity.class))));
        col.addView(c3);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFFF1F5F9);
        sv.addView(col);
        Ui.padForSystemBars(sv);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = ECAccessibilityService.get() != null;
        status.setText(on ? "● Đang chạy — server đã mở" : "○ Chưa bật. Bật \"ElementCheck\" trong Cài đặt → Trợ năng.");
        status.setTextColor(on ? 0xFF16A34A : 0xFFDC2626);
        overlaySwitch.setChecked(Prefs.overlayEnabled(this));
        projectionCard.setVisibility(Build.VERSION.SDK_INT < 30 ? android.view.View.VISIBLE : android.view.View.GONE);

        List<String> ips = localIps();
        StringBuilder sb = new StringBuilder();
        for (String ip : ips) sb.append("http://").append(ip).append(':').append(ECAccessibilityService.PORT).append('\n');
        if (sb.length() == 0) sb.append("Không có mạng — dùng adb forward bên dưới\n");
        address.setText(sb.toString().trim());
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
