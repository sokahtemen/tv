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
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Keeps the MTVA proxy running in the background as a foreground service. */
public class ProxyService extends Service {
    private static final String TAG = "MtvaProxy";
    private static final String CHANNEL_ID = "proxy";
    private static final int MAX_EVENTS = 12;
    private static final int START_ATTEMPTS = 30; // 2 s apart: rides out a port still held after a restart

    // Shared between the service, its starter thread and the screen; guarded by ProxyService.class
    private static MtvaProxy proxy;
    private static Thread starter;
    private static final ArrayDeque<String> recent = new ArrayDeque<String>();
    static volatile String status = "Starting…";

    private static final MtvaProxy.Log LOG = new MtvaProxy.Log() {
        public void line(String msg) {
            Log.i(TAG, msg);
            remember(msg);
        }
    };

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
            if (proxy == null && starter == null) {
                starter = new Thread(new Runnable() {
                    public void run() {
                        startWithRetry();
                    }
                }, "mtva-start");
                starter.start();
            }
        }
        return START_STICKY;
    }

    /** Runs off the main thread; binding can briefly fail right after a restart. */
    private static void startWithRetry() {
        MtvaProxy p = new MtvaProxy(LOG);
        for (int attempt = 1; attempt <= START_ATTEMPTS; attempt++) {
            try {
                p.start();
                synchronized (ProxyService.class) {
                    if (starter != Thread.currentThread()) { // service stopped meanwhile
                        p.stop();
                        return;
                    }
                    proxy = p;
                    starter = null;
                }
                status = "Running on http://127.0.0.1:" + MtvaProxy.PORT;
                return;
            } catch (IOException e) {
                status = "Waiting for port " + MtvaProxy.PORT + " (" + e.getMessage() + ")…";
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }
        synchronized (ProxyService.class) {
            if (starter == Thread.currentThread()) starter = null;
        }
        status = "Could not start: port " + MtvaProxy.PORT + " is in use. Restart the stick.";
    }

    @Override
    public void onDestroy() {
        synchronized (ProxyService.class) {
            if (starter != null) starter.interrupt();
            starter = null;
            if (proxy != null) proxy.stop();
            proxy = null;
        }
        status = "Stopped";
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Last proxy events, newest first, for the app screen. */
    static String recentLog() {
        synchronized (recent) {
            if (recent.isEmpty()) return "(nothing yet: start a Hungarian channel in the player)";
            StringBuilder sb = new StringBuilder();
            for (String s : recent) sb.append(s).append('\n');
            return sb.toString();
        }
    }

    private static void remember(String msg) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + msg;
        synchronized (recent) {
            recent.addFirst(line);
            while (recent.size() > MAX_EVENTS) recent.removeLast();
        }
    }
}
