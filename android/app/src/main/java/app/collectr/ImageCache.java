package app.collectr;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Keeps catalog pictures on the phone so they show without internet.
 *
 * The page asks for https://appassets.androidplatform.net/imgcache/KEY?u=URL1&u=URL2.
 * A stored picture for KEY is served right away; otherwise each URL is tried in turn,
 * the first real image is saved under KEY and served. Runs on WebView's background
 * threads, so downloading here does not block the page.
 */
final class ImageCache {

    static final String PATH = "/imgcache/";

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._-]{1,100}");
    private static final int MAX_BYTES = 3 * 1024 * 1024;
    /** Keys that failed this session; not retried until the app restarts (saves data when offline). */
    private final Set<String> failed = Collections.synchronizedSet(new HashSet<>());
    private final File dir;

    ImageCache(Context context) {
        dir = new File(context.getFilesDir(), "img");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    WebResourceResponse serve(Uri uri) {
        String key = uri.getLastPathSegment();
        if (key == null || !KEY.matcher(key).matches()) return notFound();
        File file = new File(dir, key);
        if (!file.isFile() && !failed.contains(key)) {
            List<String> sources = uri.getQueryParameters("u");
            for (String src : sources) {
                if (src.startsWith("https://") && download(src, file)) break;
            }
            if (!file.isFile()) failed.add(key);
        }
        if (!file.isFile()) return notFound();
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "max-age=31536000");
            return new WebResourceResponse(mimeOf(file), null, 200, "OK", headers, new FileInputStream(file));
        } catch (Exception e) {
            return notFound();
        }
    }

    /** Removes stored pictures: one key, or all of them when key is empty. */
    void clear(String key) {
        failed.clear();
        if (key != null && !key.isEmpty()) {
            if (KEY.matcher(key).matches()) //noinspection ResultOfMethodCallIgnored
                new File(dir, key).delete();
            return;
        }
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    /** Size of all stored pictures in bytes. */
    long size() {
        long total = 0;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) total += f.length();
        return total;
    }

    private boolean download(String src, File target) {
        File tmp = new File(dir, target.getName() + ".tmp");
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(src).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Collectr");
            if (c.getResponseCode() != 200) return false;
            byte[] head = new byte[12];
            int total = 0;
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] b = new byte[16384];
                for (int n; (n = in.read(b)) > 0; ) {
                    if (total < head.length) System.arraycopy(b, 0, head, total, Math.min(n, head.length - total));
                    total += n;
                    if (total > MAX_BYTES) throw new IllegalStateException("too large");
                    out.write(b, 0, n);
                }
            }
            // Only keep real pictures (some servers answer missing images with an HTML page).
            if (total < 200 || imageType(head) == null || !tmp.renameTo(target)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return false;
            }
            return true;
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
    }

    private static String mimeOf(File f) {
        byte[] head = new byte[12];
        try (InputStream in = new FileInputStream(f)) {
            //noinspection ResultOfMethodCallIgnored
            in.read(head);
        } catch (Exception ignored) {
        }
        String t = imageType(head);
        return t != null ? t : "image/jpeg";
    }

    private static String imageType(byte[] h) {
        if ((h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8) return "image/jpeg";
        if ((h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') return "image/png";
        if (h[0] == 'G' && h[1] == 'I' && h[2] == 'F') return "image/gif";
        if (h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'E') return "image/webp";
        return null;
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                new HashMap<>(), new ByteArrayInputStream(new byte[0]));
    }
}
