package hu.sokahtemen.mtva;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local HTTP proxy that serves MTVA's live channels (M1, M2, Duna, M4 Sport, M5, Duna World / M4 Sport+)
 * as ordinary HLS, so any IPTV player can play them from http://127.0.0.1:8088/<channel>.m3u8.
 *
 * MTVA's stream addresses are short-lived and tied to the viewer's IP, so a channel start looks up
 * a current one from MTVA's own web player (the same steps as Streamlink's mediaklikk plugin).
 * Playlists and segments then go through this proxy, which adds the headers MTVA's player sends.
 * Nothing is re-encoded.
 *
 * Other streams that only need a Referer header (some local TV stations) can be played through
 * it too: http://127.0.0.1:8088/p/_?u=<stream URL>&ref=<player page URL>, both URL-encoded.
 *
 * Plain Java with no Android APIs, so it also runs and is tested on a desktop JVM.
 */
public class MtvaProxy {
    public static final int PORT = 8088;
    static final String UA = "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0";

    /** Upper limit for simultaneous player connections; a player needs only a few. */
    private static final int MAX_CLIENTS = 32;
    /** A looked-up stream address is reused this long (fast channel switching, player retries). */
    private static final long STREAM_CACHE_MS = 60_000;
    /** After a failed lookup, answer from memory this long instead of asking MTVA again. */
    private static final long FAILURE_CACHE_MS = 15_000;
    private static final int BUFFER = 64 * 1024;

    public static final class Channel {
        public final String key, name, tvgId;
        final String[] pages;            // MTVA pages with a player for this channel, tried in order
        volatile String referer;         // the page that worked last; MTVA's CDN expects it
        final String linkSuffix;         // extra query for rewritten links (Referer of a plain stream)

        // Last lookup, guarded by this
        private String streamUrl;
        private long resolvedAt;
        private IOException lastError;
        private long failedAt;

        Channel(String key, String name, String tvgId, String... pages) {
            this.key = key;
            this.name = name;
            this.tvgId = tvgId;
            this.pages = pages;
            this.referer = pages[0];
            this.linkSuffix = "";
        }

        /** Any stream that just needs a Referer header: /p/_?u=<stream>&ref=<page> in a playlist. */
        private Channel(String referer) throws IOException {
            this.key = "_";
            this.name = "Stream from " + hostOf(referer);
            this.tvgId = "";
            this.pages = new String[] {referer};
            this.referer = referer;
            this.linkSuffix = "&ref=" + URLEncoder.encode(referer, "UTF-8");
        }
    }

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (IOException e) {
            return url;
        }
    }

    public interface Log {
        void line(String msg);
    }

    public static final List<Channel> CHANNELS = Collections.unmodifiableList(java.util.Arrays.asList(
            new Channel("m1", "M1", "m1.HD.hu", "https://www.mediaklikk.hu/m1-elo/", "https://hirado.hu/elo/m1"),
            new Channel("m2", "M2 / Petőfi TV", "m2.HD.hu", "https://www.mediaklikk.hu/m2-elo/", "https://hirado.hu/elo/m2"),
            new Channel("duna", "Duna", "Duna.TV.hu", "https://www.mediaklikk.hu/duna-elo/"),
            new Channel("m4sport", "M4 Sport", "m4.hu", "https://m4sport.hu/elo/"),
            new Channel("m5", "M5", "m5.hu", "https://www.mediaklikk.hu/m5-elo/"),
            // Duna World shares its channel with M4 Sport+ (weekend afternoons); one stream carries both
            new Channel("dunaworld", "Duna World / M4 Sport+", "Duna.World.hu", "https://m4sport.hu/elo/?showchannel=mtv4plus")));

    private static final Pattern LOAD_PLAYER =
            Pattern.compile("loadPlayer\\s*\\(\\s*(['\"]).+?\\1\\s*,\\s*(\\{.*?\\})\\s*\\)", Pattern.DOTALL);
    private static final Pattern PL_SETUP =
            Pattern.compile("pl\\.setup\\s*\\(\\s*(\\{.*?\\})\\s*\\)\\s*;", Pattern.DOTALL);
    private static final Pattern PLAYLIST_ITEM = Pattern.compile("\\{[^{}]*\"file\"[^{}]*\\}");
    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]*)\"");

    /** The full playlist (all channels + guide address), served locally at /all.m3u. */
    static final String MERGED = System.getProperty("mtva.merged",
            "https://raw.githubusercontent.com/sokahtemen/tv/main/intl-english-tv.m3u");
    private volatile String mergedCache;

    private final Map<String, Channel> byKey = new LinkedHashMap<String, Channel>();
    private final String playerUrl;
    private final Log log;

    private ServerSocket server;          // guarded by this
    private ThreadPoolExecutor workers;   // guarded by this

    public MtvaProxy(Log log) {
        this(System.getProperty("mtva.player", "https://player.mediaklikk.hu/playernew/player.php"), log);
    }

    MtvaProxy(String playerUrl, Log log) {
        this.playerUrl = playerUrl;
        this.log = log;
        for (Channel c : CHANNELS) {
            String override = System.getProperty("mtva.page." + c.key); // for testing
            byKey.put(c.key, override == null ? new Channel(c.key, c.name, c.tvgId, c.pages)
                    : new Channel(c.key, c.name, c.tvgId, override));
        }
    }

    // ---------------------------------------------------------------- server

    public synchronized void start() throws IOException {
        if (server != null) return;
        final ServerSocket s = new ServerSocket();
        s.setReuseAddress(true); // before bind, so a quick restart doesn't fail with "address in use"
        try {
            s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 50);
        } catch (IOException e) {
            closeQuietly(s);
            throw e;
        }
        final ThreadPoolExecutor w = new ThreadPoolExecutor(0, MAX_CLIENTS, 30, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(), new NamedThreads("mtva-client"));
        Thread acceptor = new NamedThreads("mtva-accept").newThread(new Runnable() {
            public void run() {
                acceptLoop(s, w);
            }
        });
        server = s;
        workers = w;
        acceptor.start();
        log.line("listening on http://127.0.0.1:" + PORT);
    }

    public synchronized void stop() {
        closeQuietly(server);
        server = null;
        if (workers != null) workers.shutdown(); // idle threads end; running requests finish
        workers = null;
    }

    private void acceptLoop(ServerSocket s, ThreadPoolExecutor w) {
        while (!s.isClosed()) {
            final Socket client;
            try {
                client = s.accept();
            } catch (IOException e) {
                if (s.isClosed()) break;
                log.line("accept failed: " + e);
                sleep(1000); // e.g. out of file descriptors: don't spin
                continue;
            }
            try {
                w.execute(new Runnable() {
                    public void run() {
                        handle(client);
                    }
                });
            } catch (RejectedExecutionException e) {
                closeQuietly(client); // too many connections at once, or shutting down
            }
        }
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
                // request headers are not needed
            }
            String[] parts = request.split(" ");
            if (parts.length < 2 || !(parts[0].equals("GET") || parts[0].equals("HEAD"))) {
                sendText(out, 405, "text/plain", "Method not allowed\n", false);
                return;
            }
            boolean head = parts[0].equals("HEAD");
            String target = parts[1];
            int q = target.indexOf('?');
            String path = q < 0 ? target : target.substring(0, q);
            String query = q < 0 ? "" : target.substring(q + 1);

            if (path.equals("/") || path.equals("/playlist.m3u")) {
                sendText(out, 200, "audio/x-mpegurl; charset=utf-8", playlist(), head);
            } else if (path.equals("/all.m3u")) {
                sendText(out, 200, "audio/x-mpegurl; charset=utf-8", mergedPlaylist(), head);
            } else if (path.startsWith("/p/")) {
                String key = path.substring(3);
                String url = queryParam(query, "u");
                String ref = queryParam(query, "ref");
                Channel ch = key.equals("_") && ref != null && ref.matches("(?i)https?://.+")
                        ? new Channel(ref) : byKey.get(key);
                if (ch == null || url == null || !url.matches("(?i)https?://.+")) {
                    sendText(out, 400, "text/plain", "Bad request\n", head);
                } else {
                    forward(out, ch, url, head, false);
                }
            } else {
                Channel ch = byKey.get(path.substring(1).replaceAll("\\.m3u8?$", ""));
                if (ch == null) {
                    sendText(out, 404, "text/plain", "Unknown channel\n", head);
                } else {
                    serveChannel(out, ch, head);
                }
            }
        } catch (IOException ignored) {
            // the player closed the connection
        } catch (RuntimeException e) {
            log.line("internal error: " + e);
        } finally {
            closeQuietly(sock);
        }
    }

    /** Channel start: the stream's master playlist, from a remembered address if it still works. */
    private void serveChannel(OutputStream out, Channel ch, boolean head) throws IOException {
        String url;
        try {
            url = streamUrl(ch, null);
        } catch (IOException e) {
            sendText(out, 502, "text/plain", "MTVA stream not available: " + e.getMessage() + "\n", head);
            return;
        }
        if (forward(out, ch, url, head, true)) return;
        try {
            url = streamUrl(ch, url); // the remembered address stopped working: look it up again
        } catch (IOException e) {
            sendText(out, 502, "text/plain", "MTVA stream not available: " + e.getMessage() + "\n", head);
            return;
        }
        forward(out, ch, url, head, false);
    }

    /** Latest full playlist from GitHub; the last good copy (or just the MTVA channels) if offline. */
    private String mergedPlaylist() {
        try {
            String body = getText(MERGED, null);
            if (!body.startsWith("#EXTM3U")) throw new IOException("not a playlist");
            mergedCache = body;
            return body;
        } catch (IOException e) {
            log.line("full playlist download failed: " + e.getMessage());
            String cached = mergedCache;
            return cached != null ? cached : playlist();
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

    /**
     * Fetches an upstream URL for the player: playlists are rewritten to point back here, anything
     * else is streamed through. A fully read upstream response leaves its connection open for reuse,
     * so segments don't each need a new TLS handshake.
     *
     * @param mayFail if true and the upstream fails before anything was sent, returns false and
     *                sends nothing, so the caller can retry
     */
    private boolean forward(OutputStream out, Channel ch, String url, boolean head, boolean mayFail)
            throws IOException {
        HttpURLConnection c = open(url, ch.referer);
        int code;
        try {
            code = c.getResponseCode();
        } catch (IOException e) {
            c.disconnect();
            if (mayFail) return false;
            log.line(ch.name + ": upstream error " + e);
            sendText(out, 502, "text/plain", "Upstream error: " + e.getMessage() + "\n", head);
            return true;
        }
        if (code >= 400) {
            discard(c.getErrorStream());
            if (mayFail) return false;
            log.line(ch.name + ": upstream HTTP " + code + " for " + url);
            sendText(out, 502, "text/plain", "Upstream HTTP " + code + "\n", head);
            return true;
        }
        String type = c.getContentType() == null ? "" : c.getContentType();
        InputStream raw = c.getInputStream();
        boolean drained = false;
        try {
            BufferedInputStream body = new BufferedInputStream(raw, BUFFER);
            boolean isPlaylist = type.toLowerCase().contains("mpegurl") || url.split("\\?")[0].endsWith(".m3u8")
                    || startsWith(body, "#EXTM3U");
            if (isPlaylist) {
                String text = new String(readAll(body), "UTF-8");
                drained = true;
                sendText(out, 200, "application/vnd.apple.mpegurl", rewrite(text, c.getURL(), ch), head);
            } else {
                long len = c.getContentLengthLong();
                StringBuilder h = new StringBuilder("HTTP/1.1 200 OK\r\n");
                h.append("Content-Type: ").append(type.length() > 0 ? type : "application/octet-stream").append("\r\n");
                if (len >= 0) h.append("Content-Length: ").append(len).append("\r\n");
                h.append("Cache-Control: no-cache\r\nConnection: close\r\n\r\n");
                out.write(h.toString().getBytes("UTF-8"));
                if (!head) {
                    copy(body, out);
                    drained = true;
                }
                out.flush();
            }
            return true;
        } finally {
            if (drained) closeQuietly(raw); // connection goes back to the keep-alive pool
            else c.disconnect();           // partly read (player left): drop it
        }
    }

    /** Points every URI in an HLS playlist back at this proxy (absolute, so relative paths keep working). */
    String rewrite(String text, URL base, Channel ch) throws IOException {
        StringBuilder sb = new StringBuilder(text.length() * 2);
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
        return "/p/" + ch.key + "?u=" + URLEncoder.encode(absoluteUrl, "UTF-8") + ch.linkSuffix;
    }

    // ---------------------------------------------------------------- MTVA

    /**
     * Current stream address for a channel. Reuses a recent lookup unless it equals {@code stale},
     * and remembers failures briefly so a retrying player doesn't make it ask MTVA again and again.
     * One lookup per channel at a time; simultaneous requests wait for it.
     */
    String streamUrl(Channel ch, String stale) throws IOException {
        synchronized (ch) {
            long now = System.currentTimeMillis();
            if (ch.streamUrl != null && !ch.streamUrl.equals(stale) && now - ch.resolvedAt < STREAM_CACHE_MS) {
                return ch.streamUrl;
            }
            if (ch.lastError != null && now - ch.failedAt < FAILURE_CACHE_MS) throw ch.lastError;
            try {
                String url = resolve(ch);
                ch.streamUrl = url;
                ch.resolvedAt = System.currentTimeMillis();
                ch.lastError = null;
                log.line(ch.name + ": started");
                return url;
            } catch (IOException e) {
                ch.streamUrl = null;
                ch.lastError = e;
                ch.failedAt = System.currentTimeMillis();
                log.line(ch.name + ": could not get stream from MTVA: " + e.getMessage());
                throw e;
            }
        }
    }

    /** Looks up a fresh HLS master playlist address, the way MTVA's web player does. */
    public String resolve(Channel ch) throws IOException {
        IOException last = null;
        for (String page : ch.pages) {
            try {
                String url = resolveFrom(page);
                ch.referer = page;
                return url;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last;
    }

    private String resolveFrom(String page) throws IOException {
        String html = getText(page, null);
        Matcher m = LOAD_PLAYER.matcher(html);
        if (!m.find()) throw new IOException("player data not found on " + page);
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
        String player = getText(playerUrl + q, page);
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
            if (code >= 400) {
                discard(c.getErrorStream());
                throw new IOException("HTTP " + code + " from " + url);
            }
            InputStream in = c.getInputStream();
            try {
                return new String(readAll(in), "UTF-8");
            } finally {
                closeQuietly(in);
            }
        } catch (IOException e) {
            c.disconnect();
            throw e;
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
        StringBuilder sb = new StringBuilder(s.length());
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

    /** True if the stream begins with the given ASCII text; the stream position is unchanged. */
    private static boolean startsWith(BufferedInputStream in, String prefix) throws IOException {
        byte[] want = prefix.getBytes("UTF-8");
        byte[] got = new byte[want.length];
        in.mark(want.length);
        int n = 0, r;
        while (n < got.length && (r = in.read(got, n, got.length - n)) != -1) n += r;
        in.reset();
        return n == want.length && java.util.Arrays.equals(want, got);
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
        ByteArrayOutputStream buf = new ByteArrayOutputStream(16 * 1024);
        copy(in, buf);
        return buf.toByteArray();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[BUFFER];
        int n;
        while ((n = in.read(b)) != -1) out.write(b, 0, n);
    }

    /** Reads and drops a (small) error body so the connection can be reused. */
    private static void discard(InputStream in) {
        if (in == null) return;
        try {
            byte[] b = new byte[4096];
            int total = 0, n;
            while (total < BUFFER && (n = in.read(b)) != -1) total += n;
        } catch (IOException ignored) {
        } finally {
            closeQuietly(in);
        }
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

    private static void closeQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(ServerSocket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Daemon threads with readable names, so they never keep the process alive on their own. */
    private static final class NamedThreads implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger n = new AtomicInteger();

        NamedThreads(String prefix) {
            this.prefix = prefix;
        }

        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }

    // ---------------------------------------------------------------- desktop entry point (tests, CI)

    /** java MtvaProxy             run the proxy
     *  java MtvaProxy --resolve   print the stream address each channel resolves to
     *  java MtvaProxy --selftest  play every channel through the proxy once, like a player */
    public static void main(String[] args) throws Exception {
        MtvaProxy proxy = new MtvaProxy(new Log() {
            public void line(String msg) {
                System.err.println(msg);
            }
        });
        String mode = args.length > 0 ? args[0] : "";
        if (mode.equals("--resolve")) {
            for (Channel c : proxy.byKey.values()) {
                try {
                    String url = proxy.resolve(c);
                    HttpURLConnection conn = open(url, c.referer);
                    System.out.println("OK   " + c.name + ": " + url + "  (playlist HTTP " + conn.getResponseCode() + ")");
                    conn.disconnect();
                } catch (Exception e) {
                    System.out.println("FAIL " + c.name + ": " + e.getMessage());
                }
            }
            return;
        }
        proxy.start();
        if (mode.equals("--selftest")) {
            String all = fetchLocal("/all.m3u");
            boolean hasMtva = all.contains("http://127.0.0.1:" + PORT + "/m1.m3u8");
            System.out.println((hasMtva ? "OK   " : "FAIL ") + "/all.m3u: " + (all.split("#EXTINF", -1).length - 1)
                    + " channels" + (hasMtva ? ", includes the MTVA channels" : ", MTVA channels missing"));
            for (Channel c : proxy.byKey.values()) {
                try {
                    long t0 = System.nanoTime();
                    String master = fetchLocal("/" + c.key + ".m3u8");
                    long first = (System.nanoTime() - t0) / 1000000;
                    String media = master.contains("#EXTINF") ? master : fetchLocal(firstUri(master));
                    int bytes = fetchLocalBytes(lastUri(media)).length;
                    t0 = System.nanoTime();
                    fetchLocal("/" + c.key + ".m3u8");
                    long again = (System.nanoTime() - t0) / 1000000;
                    System.out.println("OK   " + c.name + ": playlists + newest segment (" + bytes + " bytes); start "
                            + first + " ms, restart " + again + " ms");
                } catch (Exception e) {
                    System.out.println("FAIL " + c.name + ": " + e.getMessage());
                }
            }
            // Referer streams listed in the full playlist (local stations), played the same way
            String[] lines = all.split("\n");
            for (int i = 1; i < lines.length; i++) {
                String u = lines[i].trim();
                if (!u.startsWith("http://127.0.0.1:" + PORT + "/p/_?")) continue;
                String name = lines[i - 1].contains(",") ? lines[i - 1].substring(lines[i - 1].lastIndexOf(',') + 1) : u;
                try {
                    String path = u.substring(("http://127.0.0.1:" + PORT).length());
                    String master = fetchLocal(path);
                    String media = master.contains("#EXTINF") ? master : fetchLocal(firstUri(master));
                    int bytes = fetchLocalBytes(lastUri(media)).length;
                    System.out.println("OK   " + name + ": playlists + newest segment (" + bytes + " bytes)");
                } catch (Exception e) {
                    System.out.println("FAIL " + name + ": " + e.getMessage());
                }
            }
            proxy.stop();
            System.exit(0);
        }
        Thread.currentThread().join();
    }

    private static byte[] fetchLocalBytes(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + PORT + path).openConnection();
        c.setReadTimeout(30000);
        int code = c.getResponseCode();
        if (code != 200) {
            InputStream err = c.getErrorStream();
            String msg = err == null ? "" : new String(readAll(err), "UTF-8").trim();
            throw new IOException("HTTP " + code + " for " + path + " " + msg);
        }
        return readAll(c.getInputStream());
    }

    private static String fetchLocal(String path) throws IOException {
        return new String(fetchLocalBytes(path), "UTF-8");
    }

    private static String firstUri(String playlist) throws IOException {
        List<String> u = uris(playlist);
        return u.get(0);
    }

    private static String lastUri(String playlist) throws IOException {
        List<String> u = uris(playlist);
        return u.get(u.size() - 1);
    }

    private static List<String> uris(String playlist) throws IOException {
        List<String> out = new ArrayList<String>();
        for (String line : playlist.split("\n")) {
            String t = line.trim();
            if (t.length() > 0 && !t.startsWith("#")) out.add(t);
        }
        if (out.isEmpty()) throw new IOException("empty playlist:\n" + playlist);
        return out;
    }
}
