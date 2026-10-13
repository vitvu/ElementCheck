package com.vvtool.elementcheck;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

/** Invisible activity that asks the user for screen-capture consent (Android 6-10 only). */
public class ProjectionRequestActivity extends Activity {
    private static final int REQ = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (ProjectionService.isRunning()) {
            finish();
            return;
        }
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            Intent svc = new Intent(this, ProjectionService.class)
                    .putExtra(ProjectionService.EXTRA_CODE, resultCode)
                    .putExtra(ProjectionService.EXTRA_DATA, data);
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
            else startService(svc);
        }
        finish();
    }
}
