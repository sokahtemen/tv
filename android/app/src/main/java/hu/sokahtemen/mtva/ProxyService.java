package hu.sokahtemen.mtva;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.IOException;

/** Keeps the MTVA proxy running in the background as a foreground service. */
public class ProxyService extends Service {
    private static final String TAG = "MtvaProxy";
    private static final String CHANNEL_ID = "proxy";
    private static MtvaProxy proxy;
    static volatile String status = "Starting…";
    private static final java.util.LinkedList<String> recent = new java.util.LinkedList<String>();

    /** Last proxy events, newest first, shown in the app screen for troubleshooting. */
    static String recentLog() {
        synchronized (recent) {
            StringBuilder sb = new StringBuilder();
            for (String s : recent) sb.append(s).append('\n');
            return sb.length() == 0 ? "(nothing yet: start a Hungarian channel in the player)" : sb.toString();
        }
    }

    private static void remember(String msg) {
        String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(new java.util.Date());
        synchronized (recent) {
            recent.addFirst(time + "  " + msg);
            while (recent.size() > 12) recent.removeLast();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "MTVA Proxy", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("MTVA Proxy")
                .setContentText("M1, M2, Duna, M4 Sport, M5, Duna World on 127.0.0.1:" + MtvaProxy.PORT)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(1, n);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        synchronized (ProxyService.class) {
            if (proxy == null) {
                proxy = new MtvaProxy(new MtvaProxy.Log() {
                    public void line(String msg) {
                        Log.i(TAG, msg);
                        remember(msg);
                    }
                });
                try {
                    proxy.start();
                    status = "Running on http://127.0.0.1:" + MtvaProxy.PORT;
                } catch (IOException e) {
                    proxy = null;
                    status = "Could not start: " + e.getMessage();
                    Log.e(TAG, "start failed", e);
                }
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        synchronized (ProxyService.class) {
            if (proxy != null) proxy.stop();
            proxy = null;
            status = "Stopped";
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
