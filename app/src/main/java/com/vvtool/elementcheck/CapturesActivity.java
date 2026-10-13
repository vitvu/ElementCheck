package com.vvtool.elementcheck;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Lists saved captures. Tap to open, long-press to delete. */
public class CapturesActivity extends Activity {
    private final List<String> ids = new ArrayList<>();
    private ArrayAdapter<String> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ListView list = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        list.setAdapter(adapter);
        list.setEmptyView(null);
        list.setOnItemClickListener((p, v, pos, id) ->
                startActivity(new Intent(this, ViewerActivity.class).putExtra("id", ids.get(pos))));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            String cid = ids.get(pos);
            new AlertDialog.Builder(this)
                    .setTitle("Xoá capture?")
                    .setMessage(cid)
                    .setPositiveButton("Xoá", (d, w) -> { CaptureStore.delete(this, cid); reload(); })
                    .setNegativeButton("Huỷ", null)
                    .show();
            return true;
        });
        Ui.padForSystemBars(list);
        setContentView(list);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        ids.clear();
        ids.addAll(CaptureStore.list(this));
        List<String> labels = new ArrayList<>();
        for (String id : ids) {
            String label = id;
            try {
                JSONObject m = new JSONObject(CaptureStore.readText(new File(CaptureStore.dir(this, id), CaptureStore.META)));
                label = id + "\n" + m.optString("pkg") + " · " + m.optInt("nodes") + " nodes"
                        + (m.optBoolean("hasImage") ? "" : " · no image");
            } catch (Exception ignored) { }
            labels.add(label);
        }
        adapter.clear();
        adapter.addAll(labels);
        setTitle("Captures (" + ids.size() + ")");
    }
}
