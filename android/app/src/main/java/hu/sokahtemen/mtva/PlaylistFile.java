package hu.sokahtemen.mtva;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Keeps a copy of the full playlist (in the user's order, without hidden channels) in the device's
 * Download folder as tv.m3u, so a player can load it as a local file.
 */
final class PlaylistFile {
    static final String NAME = "tv.m3u";
    static volatile String lastResult = "not written yet";

    private PlaylistFile() {
    }

    /** Writes the file in the background. */
    static void saveAsync(final Context context) {
        final Context app = context.getApplicationContext();
        Thread t = new Thread(new Runnable() {
            public void run() {
                MtvaProxy p = ProxyService.current();
                if (p == null) return;
                try {
                    write(app, p.fullPlaylist());
                    lastResult = "saved " + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
                            .format(new java.util.Date());
                } catch (Exception e) {
                    lastResult = "could not save: " + e.getMessage();
                    Log.w("MtvaProxy", "playlist file", e);
                }
            }
        }, "mtva-file");
        t.setDaemon(true);
        t.start();
    }

    private static void write(Context context, String text) throws IOException {
        byte[] bytes = text.getBytes("UTF-8");
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = context.getContentResolver();
            Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            Uri uri = null;
            Cursor c = cr.query(collection, new String[] {MediaStore.Downloads._ID},
                    MediaStore.Downloads.DISPLAY_NAME + "=?", new String[] {NAME}, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) uri = Uri.withAppendedPath(collection, String.valueOf(c.getLong(0)));
                } finally {
                    c.close();
                }
            }
            if (uri == null) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, NAME);
                v.put(MediaStore.Downloads.MIME_TYPE, "audio/x-mpegurl");
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                uri = cr.insert(collection, v);
                if (uri == null) throw new IOException("the Download folder is not available");
            }
            OutputStream out = cr.openOutputStream(uri, "wt");
            if (out == null) throw new IOException("cannot open " + NAME);
            try {
                out.write(bytes);
            } finally {
                out.close();
            }
        } else {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
            FileOutputStream out = new FileOutputStream(new File(dir, NAME));
            try {
                out.write(bytes);
            } finally {
                out.close();
            }
        }
    }
}
