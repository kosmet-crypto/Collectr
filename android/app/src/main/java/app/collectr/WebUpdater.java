package app.collectr;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Over-the-air updates for the web page, so most changes reach the app without a new APK.
 *
 * The page (index.html on the repo's main branch) is downloaded into app storage and served
 * at the same in-app URL as the bundled copy, so localStorage (the user's data) is unchanged.
 * A downloaded page is only used by the APK build it was downloaded with; a new APK starts
 * again from its own bundled copy. If a downloaded page fails to start, MainActivity calls
 * {@link #rollBack()} and the bundled copy is used again.
 */
final class WebUpdater {

    /**
     * Bridge features this APK offers. A page that declares a higher
     * {@code <meta name="collectr-native-api">} needs a newer APK and is not installed.
     */
    static final int NATIVE_API = 1;

    private static final String BUNDLED_ASSET = "www/index.html";
    private static final int MAX_PAGE_BYTES = 5 * 1024 * 1024;
    private static final Pattern NATIVE_API_META =
            Pattern.compile("<meta\\s+name=\"collectr-native-api\"\\s+content=\"(\\d+)\"");

    private final Context context;
    private final SharedPreferences prefs;
    private final File page;

    WebUpdater(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences("webupdate", Context.MODE_PRIVATE);
        this.page = new File(context.getFilesDir(), "www/index.html");
        // A page downloaded under an older APK may rely on an older bundle; start fresh.
        if (prefs.getLong("forVersion", -1) != BuildConfig.VERSION_CODE) discard();
    }

    /** True when the app is running a downloaded page instead of the bundled one. */
    boolean isActive() {
        return page.isFile();
    }

    /** Short id of the running page, shown next to the app version. */
    String activeId() {
        String hash = isActive() ? prefs.getString("hash", "") : "";
        return hash.length() >= 7 ? hash.substring(0, 7) : "";
    }

    /** Serves the downloaded page for the in-app index.html URL; null means use the bundled asset. */
    WebResourceResponse intercept(String assetPath) {
        if (!BUNDLED_ASSET.equals(assetPath) || !isActive()) return null;
        try {
            return new WebResourceResponse("text/html", "utf-8", new FileInputStream(page));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Downloads the latest page and stores it if it differs from the one in use.
     * Runs on a background thread. Returns true when a new page was stored.
     */
    boolean check() throws Exception {
        String url = "https://raw.githubusercontent.com/" + BuildConfig.UPDATE_REPO + "/main/index.html";
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setUseCaches(false);
        if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
        byte[] body;
        try (InputStream in = c.getInputStream()) {
            body = readAll(in, MAX_PAGE_BYTES);
        }
        String hash = sha256(body);
        if (hash.equals(activeHash()) || hash.equals(prefs.getString("badHash", ""))) return false;
        if (!looksValid(new String(body, StandardCharsets.UTF_8))) return false;

        File dir = page.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return false;
        File tmp = new File(dir, "index.html.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(body);
            out.getFD().sync();
        }
        if (!tmp.renameTo(page)) {
            tmp.delete();
            return false;
        }
        prefs.edit().putString("hash", hash).putLong("forVersion", BuildConfig.VERSION_CODE).apply();
        return true;
    }

    /** The downloaded page did not start: drop it and never install that exact page again. */
    void rollBack() {
        prefs.edit().putString("badHash", prefs.getString("hash", "")).apply();
        discard();
    }

    private void discard() {
        page.delete();
        prefs.edit().remove("hash").putLong("forVersion", BuildConfig.VERSION_CODE).apply();
    }

    private String activeHash() throws Exception {
        if (isActive()) return prefs.getString("hash", "");
        try (InputStream in = context.getAssets().open(BUNDLED_ASSET)) {
            return sha256(readAll(in, MAX_PAGE_BYTES));
        }
    }

    /** Basic sanity checks so a truncated or unrelated file never replaces the app. */
    private static boolean looksValid(String html) {
        if (html.length() < 20000 || !html.contains("</html>") || !html.contains("id=\"tab-home\"")) return false;
        Matcher m = NATIVE_API_META.matcher(html);
        int required = m.find() ? Integer.parseInt(m.group(1)) : 0;
        return required <= NATIVE_API;
    }

    private static byte[] readAll(InputStream in, int max) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        for (int n; (n = in.read(b)) > 0; ) {
            buf.write(b, 0, n);
            if (buf.size() > max) throw new IllegalStateException("Page too large");
        }
        return buf.toByteArray();
    }

    private static String sha256(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
