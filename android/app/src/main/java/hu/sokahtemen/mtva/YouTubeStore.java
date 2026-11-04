package hu.sokahtemen.mtva;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** The user's YouTube channels ({name, link} pairs), kept on the device. */
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

    private static String clean(String s) {
        return s.replace('\t', ' ').replace('\n', ' ').trim();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
