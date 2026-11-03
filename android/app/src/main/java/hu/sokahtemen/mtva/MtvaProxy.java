package hu.sokahtemen.mtva;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local HTTP proxy that serves MTVA's live channels (M1, M2, Duna, M4 Sport, M5, Duna World)
 * as ordinary HLS, so any IPTV player can play them from http://127.0.0.1:8088/<channel>.m3u8.
 *
 * MTVA's stream addresses are short-lived, so each channel start fetches a fresh one from
 * MTVA's own web player (the same steps as Streamlink's mediaklikk plugin). Every playlist and
 * segment request then goes through this proxy, which adds the headers MTVA's player sends.
 * Nothing is re-encoded.
 *
 * Plain Java with no Android APIs, so it can also run and be tested on a desktop JVM.
 */
public class MtvaProxy {
    public static final int PORT = 8088;
    static final String UA = "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0";

    public static final class Channel {
        public final String key, name, tvgId, page;

        Channel(String key, String name, String tvgId, String page) {
            this.key = key;
            this.name = name;
            this.tvgId = tvgId;
            this.page = page;
        }
    }

    public interface Log {
        void line(String msg);
    }

    public static final List<Channel> CHANNELS = new ArrayList<Channel>();

    static {
        CHANNELS.add(new Channel("m1", "M1", "m1.HD.hu", "https://www.mediaklikk.hu/m1-elo/"));
        CHANNELS.add(new Channel("m2", "M2 / Petőfi TV", "m2.HD.hu", "https://www.mediaklikk.hu/m2-elo/"));
        CHANNELS.add(new Channel("duna", "Duna", "Duna.TV.hu", "https://www.mediaklikk.hu/duna-elo/"));
        CHANNELS.add(new Channel("m4sport", "M4 Sport", "m4.hu", "https://m4sport.hu/elo/"));
        CHANNELS.add(new Channel("m4sportplus", "M4 Sport+", "m4sport+.hu", "https://m4sport.hu/elo/?showchannel=mtv4plus"));
        CHANNELS.add(new Channel("m5", "M5", "m5.hu", "https://www.mediaklikk.hu/m5-elo/"));
        CHANNELS.add(new Channel("dunaworld", "Duna World", "Duna.World.hu", "https://www.mediaklikk.hu/duna-world-elo/"));
    }

    private static final Pattern LOAD_PLAYER =
            Pattern.compile("loadPlayer\\s*\\(\\s*(['\"]).+?\\1\\s*,\\s*(\\{.*?\\})\\s*\\)", Pattern.DOTALL);
    private static final Pattern PL_SETUP =
            Pattern.compile("pl\\.setup\\s*\\(\\s*(\\{.*?\\})\\s*\\)\\s*;", Pattern.DOTALL);
    private static final Pattern PLAYLIST_ITEM = Pattern.compile("\\{[^{}]*\"file\"[^{}]*\\}");
    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]*)\"");

    private final Map<String, Channel> byKey = new LinkedHashMap<String, Channel>();
    private final String playerUrl;
    private final Log log;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile ServerSocket server;

    public MtvaProxy(Log log) {
        this(System.getProperty("mtva.player", "https://player.mediaklikk.hu/playernew/player.php"), log);
    }

    MtvaProxy(String playerUrl, Log log) {
        this.playerUrl = playerUrl;
        this.log = log;
        for (Channel c : CHANNELS) {
            String override = System.getProperty("mtva.page." + c.key); // for testing
            byKey.put(c.key, override == null ? c : new Channel(c.key, c.name, c.tvgId, override));
        }
    }

    // ---------------------------------------------------------------- server

    public synchronized void start() throws IOException {
        if (server != null) return;
        server = new ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1"));
        server.setReuseAddress(true);
        final ServerSocket s = server;
        pool.execute(new Runnable() {
            public void run() {
                while (!s.isClosed()) {
                    try {
                        final Socket client = s.accept();
                        pool.execute(new Runnable() {
                            public void run() {
                                handle(client);
                            }
                        });
                    } catch (IOException e) {
                        if (!s.isClosed()) log.line("accept failed: " + e);
                    }
                }
            }
        });
        log.line("listening on http://127.0.0.1:" + PORT + "/playlist.m3u");
    }

    public synchronized void stop() {
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        server = null;
    }

    private void handle(Socket sock) {
        try {
            sock.setSoTimeout(30000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();
            String request = readLine(in);
            if (request == null) return;
            String header;
            while ((header = readLine(in)) != null && header.length() > 0) {
                // headers are not needed
            }
            String[] parts = request.split(" ");
            if (parts.length < 2 || !(parts[0].equals("GET") || parts[0].equals("HEAD"))) {
                sendText(out, 405, "text/plain", "Method not allowed\n", false);
                return;
            }
            boolean head = parts[0].equals("HEAD");
            String target = parts[1];
            String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
            String query = target.contains("?") ? target.substring(target.indexOf('?') + 1) : "";

            if (path.equals("/") || path.equals("/playlist.m3u")) {
                sendText(out, 200, "audio/x-mpegurl; charset=utf-8", playlist(), head);
            } else if (path.startsWith("/p/")) {
                Channel ch = byKey.get(path.substring(3));
                String url = queryParam(query, "u");
                if (ch == null || url == null || !url.matches("(?i)https?://.+")) {
                    sendText(out, 400, "text/plain", "Bad request\n", head);
                } else {
                    forward(out, ch, url, head);
                }
            } else {
                String key = path.substring(1).replaceAll("\\.m3u8?$", "");
                Channel ch = byKey.get(key);
                if (ch == null) {
                    sendText(out, 404, "text/plain", "Unknown channel\n", head);
                    return;
                }
                String master;
                try {
                    master = resolve(ch);
                } catch (IOException e) {
                    log.line(ch.name + ": could not get stream from MTVA: " + e.getMessage());
                    sendText(out, 502, "text/plain", "MTVA stream not available: " + e.getMessage() + "\n", head);
                    return;
                }
                log.line(ch.name + ": started");
                forward(out, ch, master, head);
            }
        } catch (IOException ignored) {
            // player closed the connection
        } finally {
            try {
                sock.close();
            } catch (IOException ignored) {
            }
        }
    }

    private String playlist() {
        StringBuilder sb = new StringBuilder("#EXTM3U\n");
        for (Channel c : byKey.values()) {
            sb.append("#EXTINF:-1 tvg-name=\"").append(c.name).append("\" tvg-id=\"").append(c.tvgId)
                    .append("\" group-title=\"Magyar közszolgálati\",").append(c.name).append('\n')
                    .append("http://127.0.0.1:").append(PORT).append('/').append(c.key).append(".m3u8\n");
        }
        return sb.toString();
    }

    /** Fetches an upstream URL; playlists are rewritten to point back here, everything else is streamed. */
    private void forward(OutputStream out, Channel ch, String url, boolean head) throws IOException {
        HttpURLConnection c = open(url, ch.page);
        int code;
        try {
            code = c.getResponseCode();
        } catch (IOException e) {
            sendText(out, 502, "text/plain", "Upstream error: " + e.getMessage() + "\n", head);
            return;
        }
        if (code >= 400) {
            log.line(ch.name + ": upstream HTTP " + code + " for " + url);
            sendText(out, 502, "text/plain", "Upstream HTTP " + code + "\n", head);
            c.disconnect();
            return;
        }
        String type = c.getContentType() == null ? "" : c.getContentType();
        BufferedInputStream body = new BufferedInputStream(c.getInputStream());
        body.mark(16);
        byte[] magic = new byte[7];
        int n = body.read(magic);
        body.reset();
        boolean isPlaylist = type.toLowerCase().contains("mpegurl") || url.split("\\?")[0].endsWith(".m3u8")
                || (n == 7 && new String(magic, "UTF-8").equals("#EXTM3U"));
        try {
            if (isPlaylist) {
                String text = new String(readAll(body), "UTF-8");
                sendText(out, 200, "application/vnd.apple.mpegurl", rewrite(text, c.getURL(), ch), head);
            } else {
                long len = c.getContentLengthLong();
                StringBuilder h = new StringBuilder("HTTP/1.1 200 OK\r\n");
                h.append("Content-Type: ").append(type.length() > 0 ? type : "application/octet-stream").append("\r\n");
                if (len >= 0) h.append("Content-Length: ").append(len).append("\r\n");
                h.append("Cache-Control: no-cache\r\nConnection: close\r\n\r\n");
                out.write(h.toString().getBytes("UTF-8"));
                if (!head) copy(body, out);
                out.flush();
            }
        } finally {
            body.close();
            c.disconnect();
        }
    }

    /** Points every URI in an HLS playlist back at this proxy (absolute, so relative paths keep working). */
    String rewrite(String text, URL base, Channel ch) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\r?\n")) {
            String t = line.trim();
            if (t.startsWith("#")) {
                Matcher m = URI_ATTR.matcher(line);
                StringBuffer lb = new StringBuffer();
                while (m.find()) {
                    String abs = new URL(base, m.group(1)).toString();
                    m.appendReplacement(lb, Matcher.quoteReplacement("URI=\"" + proxied(ch, abs) + "\""));
                }
                m.appendTail(lb);
                sb.append(lb);
            } else if (t.length() > 0) {
                sb.append(proxied(ch, new URL(base, t).toString()));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String proxied(Channel ch, String absoluteUrl) throws IOException {
        return "/p/" + ch.key + "?u=" + URLEncoder.encode(absoluteUrl, "UTF-8");
    }

    // ---------------------------------------------------------------- MTVA

    /** Returns a fresh HLS master playlist URL for a channel, the way MTVA's web player gets it. */
    public String resolve(Channel ch) throws IOException {
        String html = getText(ch.page, null);
        Matcher m = LOAD_PLAYER.matcher(html);
        if (!m.find()) throw new IOException("player data not found on " + ch.page);
        String json = m.group(2);

        Map<String, String> params = new LinkedHashMap<String, String>();
        String contentId = jsonValue(json, "contentId");
        if (contentId == null) throw new IOException("contentId missing in player data");
        params.put("contentId", contentId);
        String streamId = jsonValue(json, "streamId");
        String idec = jsonValue(json, "idec");
        String token = jsonValue(json, "token");
        if (idec != null) params.put("idec", idec);
        if (token != null) {
            if (streamId != null) params.put("streamId", streamId);
            params.put("video", percentDecode(token));
        } else {
            params.put("video", streamId == null ? "" : streamId);
        }
        params.put("noflash", "yes");
        params.put("embedded", "0");

        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            q.append(q.length() == 0 ? "?" : "&").append(URLEncoder.encode(e.getKey(), "UTF-8"))
                    .append('=').append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        String player = getText(playerUrl + q, ch.page);
        Matcher s = PL_SETUP.matcher(player);
        if (!s.find()) throw new IOException("stream list not found in MTVA player response");

        String fallback = null;
        Matcher item = PLAYLIST_ITEM.matcher(s.group(1));
        while (item.find()) {
            String obj = item.group();
            if (!"hls".equals(jsonValue(obj, "type"))) continue;
            String file = jsonValue(obj, "file");
            if (file == null) continue;
            if (file.startsWith("//")) file = "https:" + file;
            if (!file.contains("vod")) return file;
            if (fallback == null) fallback = file;
        }
        if (fallback != null) return fallback;
        throw new IOException("no live HLS stream in MTVA player response");
    }

    // ---------------------------------------------------------------- helpers

    private static HttpURLConnection open(String url, String referer) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", UA);
        if (referer != null) c.setRequestProperty("Referer", referer);
        return c;
    }

    private static String getText(String url, String referer) throws IOException {
        HttpURLConnection c = open(url, referer);
        try {
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("HTTP " + code + " from " + url);
            return new String(readAll(c.getInputStream()), "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** Value of a top-level "key": "string" or "key": number in a small JSON object. */
    static String jsonValue(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key)
                + "\"\\s*:\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([^,}\\s\"]+))").matcher(json);
        if (!m.find()) return null;
        if (m.group(1) != null) return unescape(m.group(1));
        String raw = m.group(2);
        return raw.equals("null") ? null : raw;
    }

    static String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++i);
            if (n == 'u' && i + 4 < s.length()) {
                sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                i += 4;
            } else if (n == 'n') {
                sb.append('\n');
            } else if (n == 't') {
                sb.append('\t');
            } else {
                sb.append(n); // \/ \" \\
            }
        }
        return sb.toString();
    }

    /** Like Python's urllib.parse.unquote: decodes %XX only, leaves '+' alone. */
    static String percentDecode(String s) throws IOException {
        return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
    }

    private static String queryParam(String query, String name) throws IOException {
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals(name)) return URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
        }
        return null;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') sb.append((char) b);
            if (sb.length() > 8192) throw new IOException("header too long");
        }
        return (b == -1 && sb.length() == 0) ? null : sb.toString();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        copy(in, buf);
        return buf.toByteArray();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) != -1) out.write(b, 0, n);
    }

    private static void sendText(OutputStream out, int code, String type, String body, boolean head)
            throws IOException {
        byte[] bytes = body.getBytes("UTF-8");
        String reason = code == 200 ? "OK" : code == 404 ? "Not Found" : code == 400 ? "Bad Request"
                : code == 405 ? "Method Not Allowed" : "Bad Gateway";
        String h = "HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: " + type + "\r\nContent-Length: "
                + bytes.length + "\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes("UTF-8"));
        if (!head) out.write(bytes);
        out.flush();
    }

    // ---------------------------------------------------------------- desktop entry point

    /** java MtvaProxy            run the proxy
     *  java MtvaProxy --resolve  just print the stream address each channel resolves to */
    public static void main(String[] args) throws Exception {
        Log stderr = new Log() {
            public void line(String msg) {
                System.err.println(msg);
            }
        };
        MtvaProxy proxy = new MtvaProxy(stderr);
        if (args.length > 0 && args[0].equals("--resolve")) {
            for (Channel c : proxy.byKey.values()) {
                try {
                    String url = proxy.resolve(c);
                    HttpURLConnection conn = open(url, c.page);
                    System.out.println("OK   " + c.name + ": " + url + "  (playlist HTTP " + conn.getResponseCode() + ")");
                    conn.disconnect();
                } catch (Exception e) {
                    System.out.println("FAIL " + c.name + ": " + e.getMessage());
                }
            }
            return;
        }
        proxy.start();
        Thread.currentThread().join();
    }
}
