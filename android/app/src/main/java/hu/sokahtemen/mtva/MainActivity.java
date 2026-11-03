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
    private TextView text;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startForegroundService(new Intent(this, ProxyService.class));
        text = new TextView(this);
        text.setTextColor(Color.WHITE);
        text.setBackgroundColor(Color.rgb(20, 24, 32));
        text.setTextSize(22);
        text.setGravity(Gravity.CENTER);
        text.setPadding(80, 60, 80, 60);
        setContentView(text);
        refresh();
    }

    private void refresh() {
        text.setText("MTVA Proxy\n\n" + ProxyService.status
                + "\n\nChannel list:  http://127.0.0.1:" + MtvaProxy.PORT + "/playlist.m3u"
                + "\n\nThe proxy keeps running in the background and starts automatically"
                + " when the stick boots. You can close this screen.");
        handler.postDelayed(new Runnable() {
            public void run() {
                refresh();
            }
        }, 1000);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
