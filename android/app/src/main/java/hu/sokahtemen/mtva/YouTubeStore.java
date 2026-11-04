package hu.sokahtemen.mtva;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;

/** The user's YouTube channels ({name, link} pairs) and channel order, kept on the device. */
final class YouTubeStore {
    private static final String PREFS = "channels";
    private static final String KEY = "youtube";

    private YouTubeStore() {
    }

    static List<String[]> load(Context context) {
        List<String[]> out = new ArrayList<String[]>();
        String raw = prefs(context).getString(KEY, "");
        for (String line : raw.split("\n")) {
            String[] parts = line.split("\t", 2);
            if (parts.length == 2 && parts[1].length() > 0) out.add(parts);
        }
        return out;
    }

    static void save(Context context, List<String[]> entries) {
        StringBuilder sb = new StringBuilder();
        for (String[] e : entries) {
            sb.append(clean(e[0])).append('\t').append(clean(e[1])).append('\n');
        }
        prefs(context).edit().putString(KEY, sb.toString()).apply();
    }

    static void add(Context context, String name, String link) {
        List<String[]> all = load(context);
        all.add(new String[] {name, link});
        save(context, all);
    }

    /** Removes the entry whose link normalizes to the same page. */
    static void remove(Context context, String normalizedLink) {
        List<String[]> all = load(context);
        List<String[]> keep = new ArrayList<String[]>();
        for (String[] e : all) {
            String n;
            try {
                n = MtvaProxy.normalizeYouTube(e[1]);
            } catch (IllegalArgumentException ex) {
                n = e[1];
            }
            if (!n.equals(normalizedLink)) keep.add(e);
        }
        save(context, keep);
    }

    /** Channel names in the user's order (one per line). */
    static List<String> loadOrder(Context context) {
        List<String> out = new ArrayList<String>();
        for (String n : prefs(context).getString("order", "").split("\n")) {
            if (n.length() > 0) out.add(n);
        }
        return out;
    }

    static void saveOrder(Context context, List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (String n : names) sb.append(clean(n)).append('\n');
        prefs(context).edit().putString("order", sb.toString()).apply();
    }

    /** Names of the channels the user hid from the player. */
    static Set<String> loadHidden(Context context) {
        return new HashSet<String>(prefs(context).getStringSet("hidden", new HashSet<String>()));
    }

    static void saveHidden(Context context, Set<String> names) {
        prefs(context).edit().putStringSet("hidden", new HashSet<String>(names)).apply();
    }

    private static String clean(String s) {
        return s.replace('\t', ' ').replace('\n', ' ').trim();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
