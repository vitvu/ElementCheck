package com.vvtool.elementcheck;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CapturesActivity extends Activity {
    private final List<Item> items = new ArrayList<>();
    private final LruCache<String, Bitmap> thumbs = new LruCache<>(48);
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private Adapter adapter;
    private TextView title, empty;

    private static final class Item {
        String id, name, sub;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        TextView[] t = new TextView[1];
        root.addView(Ui.header(this, "Captures", t));
        title = t[0];

        empty = Ui.text(this, "No captures yet", 15, Ui.TEXT_2, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, Ui.dp(this, 64), 0, 0);
        root.addView(empty);

        ListView list = new ListView(this);
        list.setDivider(new android.graphics.drawable.ColorDrawable(0));
        list.setDividerHeight(Ui.dp(this, 10));
        list.setSelector(android.R.color.transparent);
        int p = Ui.dp(this, 16);
        list.setPadding(p, 0, p, p);
        list.setClipToPadding(false);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((a, v, pos, id) ->
                startActivity(new Intent(this, ViewerActivity.class).putExtra("id", items.get(pos).id)));
        root.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        Ui.edgeToEdge(this, root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    protected void onDestroy() {
        loader.shutdownNow();
        super.onDestroy();
    }

    private void reload() {
        items.clear();
        SimpleDateFormat fmt = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault());
        for (String id : CaptureStore.list(this)) {
            Item it = new Item();
            it.id = id;
            it.name = id;
            it.sub = "";
            try {
                JSONObject m = new JSONObject(CaptureStore.readText(new File(CaptureStore.dir(this, id), CaptureStore.META)));
                it.name = CaptureStore.displayName(m, id);
                it.sub = fmt.format(new Date(m.optLong("time"))) + " · " + m.optInt("nodes") + " nodes";
            } catch (Exception ignored) { }
            items.add(it);
        }
        title.setText("Captures · " + items.size());
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    private void rename(Item it) {
        EditText input = new EditText(this);
        input.setText(it.name);
        input.setSelectAllOnFocus(true);
        input.setSingleLine(true);
        LinearLayout box = new LinearLayout(this);
        int p = Ui.dp(this, 20);
        box.setPadding(p, Ui.dp(this, 8), p, 0);
        box.addView(input, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Rename")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> { CaptureStore.rename(this, it.id, input.getText().toString()); reload(); })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void delete(Item it) {
        new AlertDialog.Builder(this)
                .setTitle("Delete capture?")
                .setMessage(it.name)
                .setPositiveButton("Delete", (d, w) -> { CaptureStore.delete(this, it.id); thumbs.remove(it.id); reload(); })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            Activity a = CapturesActivity.this;
            LinearLayout row = (LinearLayout) convert;
            if (row == null) {
                row = new LinearLayout(a);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int p = Ui.dp(a, 12);
                row.setPadding(p, p, Ui.dp(a, 4), p);
                row.setBackground(Ui.rounded(Ui.SURFACE, Ui.dp(a, 20)));
                ImageView iv = new ImageView(a);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackground(Ui.rounded(Ui.PRIMARY_SOFT, Ui.dp(a, 12)));
                iv.setClipToOutline(true);
                row.addView(iv, new LinearLayout.LayoutParams(Ui.dp(a, 48), Ui.dp(a, 84)));
                LinearLayout texts = new LinearLayout(a);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 4), 0);
                TextView name = Ui.text(a, "", 16, Ui.TEXT, true);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                texts.addView(name);
                texts.addView(Ui.text(a, "", 13, Ui.TEXT_2, false));
                row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                int s = Ui.dp(a, 44);
                ImageView edit = Ui.iconButton(a, R.drawable.ic_edit, Ui.TEXT_2, 0x00000000, null);
                ImageView del = Ui.iconButton(a, R.drawable.ic_delete, Ui.RED, 0x00000000, null);
                edit.setFocusable(false);
                del.setFocusable(false);
                row.addView(edit, new LinearLayout.LayoutParams(s, s));
                row.addView(del, new LinearLayout.LayoutParams(s, s));
            }
            Item it = items.get(pos);
            LinearLayout texts = (LinearLayout) row.getChildAt(1);
            ((TextView) texts.getChildAt(0)).setText(it.name);
            ((TextView) texts.getChildAt(1)).setText(it.sub);
            row.getChildAt(2).setOnClickListener(v -> rename(it));
            row.getChildAt(3).setOnClickListener(v -> delete(it));
            bindThumb((ImageView) row.getChildAt(0), it.id);
            return row;
        }
    }

    private void bindThumb(ImageView iv, String id) {
        iv.setTag(id);
        Bitmap cached = thumbs.get(id);
        iv.setImageBitmap(cached);
        if (cached != null) return;
        loader.execute(() -> {
            byte[] data = CaptureStore.thumb(this, id);
            if (data == null) return;
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (b == null) return;
            thumbs.put(id, b);
            runOnUiThread(() -> { if (id.equals(iv.getTag())) iv.setImageBitmap(b); });
        });
    }
}
