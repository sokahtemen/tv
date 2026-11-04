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
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private TextView status, events, fileInfo;
    private LinearLayout channelList;
    private String version = "";
    private String shownChannels = "";
    private Button moving;          // the row being moved with the arrow keys, if any

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
        fileInfo = text("", 15, Color.rgb(200, 200, 210));
        root.addView(fileInfo);
        if (android.os.Build.VERSION.SDK_INT < 29 && checkSelfPermission(
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }

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
        Button reset = button("Reset order");
        reset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Put all channels back in the original playlist order?")
                        .setPositiveButton("Reset", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                saveOrder(new ArrayList<String>());
                                toast("Order reset. Refresh the playlist in your player.");
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
        actions.addView(add);
        actions.addView(restart);
        actions.addView(reset);
        root.addView(actions);

        root.addView(heading("Channels"));
        root.addView(text("Select a channel to move, hide or test it. The player gets this order, without groups "
                + "and without hidden channels; "
                + "refresh the playlist in the player after changes.", 14, Color.rgb(200, 200, 210)));
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
        fileInfo.setText("Same playlist as a file:  Download/" + PlaylistFile.NAME + "   (" + PlaylistFile.lastResult
                + "; updated after every change)");
        events.setText(ProxyService.recentLog());
        MtvaProxy p = ProxyService.current();
        if (p == null || moving != null) return; // don't rebuild the list while a row is being moved
        List<MtvaProxy.Entry> entries = p.entries();
        StringBuilder sig = new StringBuilder();
        for (MtvaProxy.Entry e : entries) sig.append(e.name).append('|');
        if (!sig.toString().equals(shownChannels)) { // rebuild only when the list changed (keeps focus)
            shownChannels = sig.toString();
            showChannels(p, entries);
        }
    }

    private void showChannels(MtvaProxy p, List<MtvaProxy.Entry> entries) {
        Set<String> youtubeUrls = new HashSet<String>();
        for (MtvaProxy.Channel c : p.channels()) {
            if (c.isYouTube()) youtubeUrls.add("http://127.0.0.1:" + MtvaProxy.PORT + "/" + c.key + ".m3u8");
        }
        View focused = getCurrentFocus();
        String focusedName = focused != null && focused.getTag() instanceof MtvaProxy.Entry
                ? ((MtvaProxy.Entry) focused.getTag()).name : null;
        channelList.removeAllViews();
        if (entries.isEmpty()) {
            channelList.addView(text("Loading the playlist…", 15, Color.rgb(200, 200, 210)));
            return;
        }
        for (final MtvaProxy.Entry e : entries) {
            final boolean yt = youtubeUrls.contains(e.url);
            final Button b = button("");
            b.setTag(e);
            b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (moving == b) {
                        finishMove();
                    } else if (moving == null) {
                        showChannelDialog(b, e, yt);
                    }
                }
            });
            b.setOnKeyListener(new View.OnKeyListener() {
                public boolean onKey(View v, int code, KeyEvent ev) {
                    if (moving != b) return false;
                    if (ev.getAction() != KeyEvent.ACTION_DOWN) return code == KeyEvent.KEYCODE_DPAD_UP
                            || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_BACK;
                    if (code == KeyEvent.KEYCODE_DPAD_UP) return step(b, -1);
                    if (code == KeyEvent.KEYCODE_DPAD_DOWN) return step(b, 1);
                    if (code == KeyEvent.KEYCODE_BACK) {
                        finishMove();
                        return true;
                    }
                    return code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
                }
            });
            channelList.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
            if (e.name.equals(focusedName)) b.requestFocus();
        }
        renumber();
    }

    private void showChannelDialog(final Button row, final MtvaProxy.Entry e, final boolean yt) {
        List<String> items = new ArrayList<String>();
        items.add("Move  (then ▲ ▼ on the remote, OK when done)");
        items.add("Move to the top");
        items.add("Move to the bottom");
        items.add("Test now");
        final MtvaProxy px = ProxyService.current();
        final boolean isHidden = px != null && px.isHidden(e.name);
        items.add(isHidden ? "Show in the player again" : "Hide from the player");
        if (yt) items.add("Remove this YouTube channel");
        new AlertDialog.Builder(this)
                .setTitle(e.name)
                .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            moving = row;
                            renumber();
                            row.requestFocus();
                        } else if (which == 1 || which == 2) {
                            channelList.removeView(row);
                            channelList.addView(row, which == 1 ? 0 : channelList.getChildCount());
                            row.requestFocus();
                            renumber();
                            saveCurrentOrder();
                        } else if (which == 3) {
                            test(e);
                        } else if (which == 4) {
                            Set<String> h = YouTubeStore.loadHidden(MainActivity.this);
                            if (isHidden) h.remove(e.name);
                            else h.add(e.name);
                            YouTubeStore.saveHidden(MainActivity.this, h);
                            if (px != null) px.setHidden(h);
                            PlaylistFile.saveAsync(MainActivity.this);
                            renumber();
                            toast(e.name + (isHidden ? " is shown again." : " is hidden.")
                                    + " Refresh the playlist in your player.");
                        } else {
                            MtvaProxy p = ProxyService.current();
                            if (p == null) return;
                            for (MtvaProxy.Channel c : p.channels()) {
                                if (c.isYouTube() && e.url.endsWith("/" + c.key + ".m3u8")) {
                                    YouTubeStore.remove(MainActivity.this, c.link());
                                }
                            }
                            ProxyService.reloadChannels(MainActivity.this);
                            PlaylistFile.saveAsync(MainActivity.this);
                            toast(e.name + " removed");
                        }
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    /** Moves the row one place up (-1) or down (+1) and keeps the remote's focus on it. */
    private boolean step(Button row, int dir) {
        int i = channelList.indexOfChild(row);
        int j = i + dir;
        if (j < 0 || j >= channelList.getChildCount()) return true;
        channelList.removeView(row);
        channelList.addView(row, j);
        row.requestFocus();
        renumber();
        return true;
    }

    private void finishMove() {
        Button row = moving;
        moving = null;
        renumber();
        saveCurrentOrder();
        if (row != null) row.requestFocus();
    }

    /** Row labels: position, name, and a marker on the row being moved. */
    private void renumber() {
        MtvaProxy p = ProxyService.current();
        Set<String> youtubeUrls = new HashSet<String>();
        if (p != null) {
            for (MtvaProxy.Channel c : p.channels()) {
                if (c.isYouTube()) youtubeUrls.add("http://127.0.0.1:" + MtvaProxy.PORT + "/" + c.key + ".m3u8");
            }
        }
        for (int i = 0; i < channelList.getChildCount(); i++) {
            View v = channelList.getChildAt(i);
            if (!(v.getTag() instanceof MtvaProxy.Entry)) continue;
            MtvaProxy.Entry e = (MtvaProxy.Entry) v.getTag();
            boolean hid = p != null && p.isHidden(e.name);
            String label = (i + 1) + ".   " + (youtubeUrls.contains(e.url) ? "▶ " : "") + e.name
                    + (hid ? "      (hidden)" : "");
            ((Button) v).setAlpha(hid ? 0.5f : 1f);
            ((Button) v).setText(v == moving ? "⇅   " + label + "      (▲ ▼ to move, OK when done)" : label);
        }
    }

    private void saveCurrentOrder() {
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < channelList.getChildCount(); i++) {
            View v = channelList.getChildAt(i);
            if (v.getTag() instanceof MtvaProxy.Entry) names.add(((MtvaProxy.Entry) v.getTag()).name);
        }
        saveOrder(names);
    }

    private void saveOrder(List<String> names) {
        YouTubeStore.saveOrder(this, names);
        MtvaProxy p = ProxyService.current();
        if (p != null) p.setOrder(names);
        PlaylistFile.saveAsync(this);
        StringBuilder sig = new StringBuilder();
        if (p != null) for (MtvaProxy.Entry e : p.entries()) sig.append(e.name).append('|');
        if (names.isEmpty()) shownChannels = ""; // reset: rebuild in the original order
        else shownChannels = sig.toString();
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
        PlaylistFile.saveAsync(this);
        toast(n + " added. Refresh the playlist in your player to see it.");
        MtvaProxy p = ProxyService.current();
        if (p != null) {
            for (MtvaProxy.Channel c : p.channels()) {
                if (!c.isYouTube() || !c.link().equals(normalized)) continue;
                for (MtvaProxy.Entry e : p.entries()) {
                    if (e.url.endsWith("/" + c.key + ".m3u8")) test(e);
                }
            }
        }
    }

    /** Checks the channel's stream in the background and shows the result. */
    private void test(final MtvaProxy.Entry e) {
        final MtvaProxy p = ProxyService.current();
        if (p == null) {
            toast("The proxy isn't running yet");
            return;
        }
        toast("Testing " + e.name + "…");
        new Thread(new Runnable() {
            public void run() {
                final String result = p.testEntry(e);
                handler.post(new Runnable() {
                    public void run() {
                        if (!isFinishing()) toast(e.name + ": " + result);
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
