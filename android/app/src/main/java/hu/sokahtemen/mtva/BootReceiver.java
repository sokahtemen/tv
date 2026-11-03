package hu.sokahtemen.mtva;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the proxy when the TV stick boots, so it is always there for the player. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        context.startForegroundService(new Intent(context, ProxyService.class));
    }
}
