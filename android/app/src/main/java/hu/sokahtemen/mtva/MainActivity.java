package hu.sokahtemen.mtva;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * The app screen, made for the remote: every action is a button reached with the arrow keys.
 * Opening the app once starts the background service; after that it also starts on every boot.
 */
public class MainActivity extends Activity {
    private static final int BG = Color.rgb(20, 24, 32);
    private static final int ACCENT = Color.rgb(196, 30, 58);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        public void run() {
            refreshStatus();
            handler.postDelayed(this, 1000);
        }
    };
    private TextView status, events;
    private LinearLayout channelList;
    private String version = "";
    private String shownChannels = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startForegroundService(new Intent(this, ProxyService.class));
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(48), dp(28), dp(48), dp(28));

        TextView title = text("MTVA Proxy " + version, 26, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        status = text("", 16, Color.rgb(170, 220, 170));
        root.addView(status);
        root.addView(text("Playlist for the player (all channels + guide):  http://127.0.0.1:"
                + MtvaProxy.PORT + "/all.m3u", 15, Color.rgb(200, 200, 210)));

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(16), 0, dp(8));
        Button add = button("+  Add YouTube channel");
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showAddDialog();
            }
        });
        Button restart = button("Restart proxy");
        restart.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ProxyService.restart(MainActivity.this);
                toast("Proxy restarting…");
            }
        });
        actions.addView(add);
        actions.addView(restart);
        root.addView(actions);

        root.addView(heading("Channels  (select one to test it)"));
        channelList = new LinearLayout(this);
        channelList.setOrientation(LinearLayout.VERTICAL);
        root.addView(channelList);

        root.addView(heading("Recent events"));
        events = text("", 14, Color.rgb(200, 200, 210));
        root.addView(events);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.addView(root);
        setContentView(scroll);
        add.requestFocus();
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

    private void refreshStatus() {
        status.setText(ProxyService.status);
        events.setText(ProxyService.recentLog());
        MtvaProxy p = ProxyService.current();
        if (p == null) return;
        List<MtvaProxy.Channel> channels = p.channels();
        StringBuilder sig = new StringBuilder();
        for (MtvaProxy.Channel c : channels) sig.append(c.key).append(c.name).append('|');
        if (!sig.toString().equals(shownChannels)) { // rebuild only when the list changed (keeps focus)
            shownChannels = sig.toString();
            showChannels(channels);
        }
    }

    private void showChannels(List<MtvaProxy.Channel> channels) {
        channelList.removeAllViews();
        for (final MtvaProxy.Channel c : channels) {
            Button b = button((c.isYouTube() ? "▶  YouTube:  " : "") + c.name);
            b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    showChannelDialog(c);
                }
            });
            channelList.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        }
    }

    private void showChannelDialog(final MtvaProxy.Channel c) {
        String[] items = c.isYouTube()
                ? new String[] {"Test now", "Remove this channel"}
                : new String[] {"Test now"};
        new AlertDialog.Builder(this)
                .setTitle(c.name + (c.isYouTube() ? "\n" + c.link() : ""))
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            test(c);
                        } else {
                            YouTubeStore.remove(MainActivity.this, c.link());
                            ProxyService.reloadChannels(MainActivity.this);
                            toast(c.name + " removed");
                        }
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showAddDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), 0);
        final EditText link = new EditText(this);
        link.setHint("YouTube link, @channel or video ID");
        link.setSingleLine(true);
        link.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        final EditText name = new EditText(this);
        name.setHint("Channel name (optional)");
        name.setSingleLine(true);
        form.addView(link);
        form.addView(name);
        new AlertDialog.Builder(this)
                .setTitle("Add a YouTube live channel")
                .setMessage("Tip: type with the Google TV app on your phone. A channel link plays whatever "
                        + "that channel is streaming live; a video link plays that live stream.")
                .setView(form)
                .setPositiveButton("Add", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        add(link.getText().toString(), name.getText().toString());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
        link.requestFocus();
    }

    private void add(String rawLink, String rawName) {
        final String normalized;
        try {
            normalized = MtvaProxy.normalizeYouTube(rawLink);
        } catch (IllegalArgumentException e) {
            toast(e.getMessage());
            return;
        }
        String n = rawName.trim();
        if (n.length() == 0) {
            int at = normalized.indexOf("/@");
            n = at >= 0 ? normalized.substring(at + 2, normalized.indexOf('/', at + 2)) : "YouTube live";
        }
        YouTubeStore.add(this, n, rawLink.trim());
        ProxyService.reloadChannels(this);
        toast(n + " added. Refresh the playlist in your player to see it.");
        MtvaProxy p = ProxyService.current();
        if (p != null) {
            for (MtvaProxy.Channel c : p.channels()) {
                if (c.isYouTube() && c.link().equals(normalized)) test(c);
            }
        }
    }

    /** Looks the stream up in the background and shows the result. */
    private void test(final MtvaProxy.Channel c) {
        final MtvaProxy p = ProxyService.current();
        if (p == null) {
            toast("The proxy isn't running yet");
            return;
        }
        toast("Testing " + c.name + "…");
        new Thread(new Runnable() {
            public void run() {
                final String result = p.test(c.key);
                handler.post(new Runnable() {
                    public void run() {
                        if (!isFinishing()) toast(c.name + ": " + result);
                    }
                });
            }
        }, "mtva-test").start();
    }

    // ---------------------------------------------------------------- small view helpers

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private TextView heading(String s) {
        TextView t = text(s, 18, Color.WHITE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(18), 0, dp(6));
        return t;
    }

    /** A button with a clearly visible highlight when the remote's focus is on it. */
    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setTextColor(Color.WHITE);
        b.setFocusable(true);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, box(ACCENT, Color.WHITE));
        bg.addState(new int[] {android.R.attr.state_pressed}, box(ACCENT, ACCENT));
        bg.addState(new int[] {}, box(Color.rgb(44, 50, 64), Color.rgb(44, 50, 64)));
        b.setBackground(bg);
        b.setPadding(dp(20), 0, dp(20), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(52));
        lp.setMargins(0, dp(4), dp(12), dp(4));
        b.setLayoutParams(lp);
        return b;
    }

    private GradientDrawable box(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(8));
        g.setStroke(dp(2), stroke);
        return g;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
