package hu.sokahtemen.mtva;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.TextView;

/** Opening the app once starts the background service; after that it also starts on every boot. */
public class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };
    private TextView text;
    private String version = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startForegroundService(new Intent(this, ProxyService.class));
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        text = new TextView(this);
        text.setTextColor(Color.WHITE);
        text.setBackgroundColor(Color.rgb(20, 24, 32));
        text.setTextSize(18);
        text.setGravity(Gravity.CENTER_HORIZONTAL);
        text.setPadding(80, 60, 80, 60);
        setContentView(text);
    }

    // Update the screen only while it is visible
    @Override
    protected void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    private void refresh() {
        text.setText("MTVA Proxy " + version + "\n\n" + ProxyService.status
                + "\n\nPlaylist for the player (all channels + guide):  http://127.0.0.1:" + MtvaProxy.PORT + "/all.m3u"
                + "\n\nIt keeps running in the background and starts when the stick boots."
                + " You can close this screen.\n\nRecent events:\n" + ProxyService.recentLog());
    }
}
