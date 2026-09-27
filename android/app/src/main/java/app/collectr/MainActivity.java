package app.collectr;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hosts the Collectr web app (bundled in assets/www) in a full-screen WebView.
 * Pages are served from https://appassets.androidplatform.net so localStorage and
 * IndexedDB behave like on a normal https site.
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/www/index.html";
    private static final int REQ_PICK_FILE = 1;
    private static final int REQ_SAVE_FILE = 2;

    /** How long a downloaded page gets to report it started before the bundled page is used again. */
    private static final long PAGE_START_TIMEOUT = 15000;
    /** A page update downloaded in the background is applied when returning after this long. */
    private static final long RELOAD_AFTER_AWAY = 10 * 60 * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private WebUpdater web;
    private ImageCache images;
    private ValueCallback<Uri[]> pendingPick;
    private File pendingPhoto;
    private byte[] pendingSaveBytes;
    private boolean pageReady;
    private boolean pageUpdatePending;
    private boolean installAfterPermission;
    private long pausedAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebUpdater(this);
        images = new ImageCache(this);
        // A downloaded page (see WebUpdater) is served at the same URL as the bundled one.
        final WebViewAssetLoader.AssetsPathHandler assets = new WebViewAssetLoader.AssetsPathHandler(this);
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", path -> {
                    WebResourceResponse page = web.intercept(path);
                    return page != null ? page : assets.handle(path);
                })
                .build();

        webView = new WebView(this);
        webView.setBackgroundColor(0xFF101114);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        webView.addJavascriptInterface(new Bridge(), "CollectrAndroid");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                String path = url.getPath();
                if (HOST.equals(url.getHost()) && path != null && path.startsWith(ImageCache.PATH)) {
                    return images.serve(url);
                }
                return loader.shouldInterceptRequest(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (HOST.equals(url.getHost())) return false;
                // Anything outside the app opens in the browser.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, url));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (pendingPick != null) pendingPick.onReceiveValue(null);
                pendingPick = callback;
                pendingPhoto = null;
                boolean wantsImage = false;
                for (String t : params.getAcceptTypes()) if (t != null && t.startsWith("image")) wantsImage = true;
                Intent i;
                if (wantsImage) {
                    Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                    pick.addCategory(Intent.CATEGORY_OPENABLE);
                    pick.setType("image/*");
                    i = Intent.createChooser(pick, tr("Слика", "Picture"));
                    Intent camera = cameraIntent();
                    if (camera != null) i.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camera});
                } else {
                    i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    // Backups (JSON) and Excel/CSV: MIME types vary by file manager, so allow any
                    // file and let the page validate it.
                    i.setType("*/*");
                }
                try {
                    startActivityForResult(i, REQ_PICK_FILE);
                } catch (ActivityNotFoundException e) {
                    pendingPick = null;
                    return false;
                }
                return true;
            }
        });

        if (savedInstanceState != null) webView.restoreState(savedInstanceState);
        else webView.loadUrl(START_URL);
        watchPageStart();

        ApkInstaller.handleStatus(this, getIntent(), this::toast);
        if (savedInstanceState == null) checkForUpdate(false);
    }

    /** Camera app writes the photo into our cache; null when there is no camera app. */
    private Intent cameraIntent() {
        try {
            File dir = new File(getCacheDir(), "photos");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(dir, "photo-" + System.currentTimeMillis() + ".jpg");
            Uri out = FileProvider.getUriForFile(this, "app.collectr.files", f);
            Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            cam.putExtra(MediaStore.EXTRA_OUTPUT, out);
            cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            pendingPhoto = f;
            return cam;
        } catch (Exception e) {
            return null;
        }
    }

    /** If a downloaded page never reports that it started, fall back to the bundled page. */
    private void watchPageStart() {
        pageReady = false;
        handler.removeCallbacks(pageStartCheck);
        if (web.isActive()) handler.postDelayed(pageStartCheck, PAGE_START_TIMEOUT);
    }

    private final Runnable pageStartCheck = () -> {
        if (pageReady || !web.isActive()) return;
        web.rollBack();
        webView.loadUrl(START_URL);
        pageReady = false;
    };

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        ApkInstaller.handleStatus(this, intent, this::toast);
    }

    @Override
    protected void onPause() {
        super.onPause();
        pausedAt = System.currentTimeMillis();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (installAfterPermission && getPackageManager().canRequestPackageInstalls()) {
            installAfterPermission = false;
            startApkInstall();
        } else if (pageUpdatePending && pausedAt > 0
                && System.currentTimeMillis() - pausedAt > RELOAD_AFTER_AWAY) {
            pageUpdatePending = false;
            webView.loadUrl(START_URL);
            watchPageStart();
        }
    }

    /* ---------- language for native messages ---------- */

    private boolean serbian() {
        return !"en".equals(getSharedPreferences("ui", MODE_PRIVATE).getString("lang", "sr"));
    }

    private String tr(String sr, String en) {
        return serbian() ? sr : en;
    }

    /** Serbian versions of the English messages ApkInstaller reports. */
    private static final Map<String, String> SR = new HashMap<>();

    static {
        SR.put("Could not download the update. Are you online?", "Ажурирање није преузето. Има ли интернета?");
        SR.put("Could not open the installer", "Инсталер није могао да се отвори");
        SR.put("Update installed", "Ажурирање је инсталирано");
        SR.put("This update cannot replace the installed app (different signature)",
                "Ово ажурирање не може да замени инсталирану апликацију (други потпис)");
        SR.put("Opening the installer…", "Отварам инсталер…");
        SR.put("Update failed. Try again later.", "Ажурирање није успело. Пробај касније.");
    }

    /* ---------- update check ---------- */

    private static final long UPDATE_CHECK_INTERVAL = 12 * 60 * 60 * 1000L;

    /**
     * Two kinds of updates:
     * 1. The web page (WebUpdater): downloaded silently and used from the next start.
     * 2. The APK: the latest GitHub Release (tagged v1.0.<versionCode>); offered in a dialog
     *    and installed from inside the app (ApkInstaller).
     * On launch the page is checked every time and the APK at most every 12 hours, silently;
     * the "Check for updates" button checks both right away and reports the result.
     */
    private void checkForUpdate(final boolean manual) {
        final SharedPreferences prefs = getSharedPreferences("update", MODE_PRIVATE);
        long now = System.currentTimeMillis();
        final boolean checkApk = manual || now - prefs.getLong("lastCheck", 0) >= UPDATE_CHECK_INTERVAL;
        if (checkApk) prefs.edit().putLong("lastCheck", now).apply();
        if (manual) toast(tr("Проверавам ажурирања…", "Checking for updates…"));

        new Thread(() -> {
            boolean newPage = false, pageChecked = false;
            try {
                newPage = web.check();
                pageChecked = true;
            } catch (Exception ignored) {
                // Offline or GitHub unreachable: keep the current page.
            }
            final boolean pageUpdated = newPage;
            if (pageUpdated && !manual) runOnUiThread(() -> pageUpdatePending = true);
            if (!checkApk) return;
            try {
                URL api = new URL("https://api.github.com/repos/" + BuildConfig.UPDATE_REPO + "/releases/latest");
                HttpURLConnection c = (HttpURLConnection) api.openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
                String body;
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] b = new byte[8192];
                    for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
                    body = buf.toString("UTF-8");
                }
                String tag = new JSONObject(body).optString("tag_name", "");
                final long latest = Long.parseLong(tag.substring(tag.lastIndexOf('.') + 1));
                final String name = tag.startsWith("v") ? tag.substring(1) : tag;
                // A newer release with the same Android part as this app only has web changes, which
                // WebUpdater already brings in quietly; only offer the APK when android/ changed.
                Matcher nat = Pattern.compile("native: ([0-9a-f]{12})").matcher(new JSONObject(body).optString("body", ""));
                boolean sameNative = nat.find() && nat.group(1).equals(BuildConfig.NATIVE_HASH);
                if (latest > installedVersionCode() && !sameNative) runOnUiThread(() -> showUpdateDialog(name));
                else if (manual && pageUpdated) runOnUiThread(this::showPageUpdatedDialog);
                else if (manual) toast(tr("Имаш најновију верзију", "You have the latest version"));
            } catch (Exception e) {
                // No network, rate limit or unexpected response: the automatic check tries again later.
                if (manual && pageUpdated) runOnUiThread(this::showPageUpdatedDialog);
                else if (manual && pageChecked) toast(tr("Имаш најновију верзију", "You have the latest version"));
                else if (manual) toast(tr("Провера није успела. Има ли интернета?", "Could not check. Are you online?"));
            }
        }).start();
    }

    private void showPageUpdatedDialog() {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle(tr("Ажурирање преузето", "Update downloaded"))
                .setMessage(tr("Нова верзија Collectr-а је спремна. Поново покренути сада? Подаци остају.",
                        "A new version of Collectr is ready. Restart now to use it? Your data stays in place."))
                .setPositiveButton(tr("Покрени", "Restart"), (d, w) -> {
                    pageUpdatePending = false;
                    webView.loadUrl(START_URL);
                    watchPageStart();
                })
                .setNegativeButton(tr("Касније", "Later"), (d, w) -> pageUpdatePending = true)
                .show();
    }

    private void startApkInstall() {
        if (!ApkInstaller.ensureAllowed(this)) {
            installAfterPermission = true;
            Toast.makeText(this, tr("Дозволи Collectr-у да инсталира ажурирања, па се врати",
                    "Allow Collectr to install updates, then go back"), Toast.LENGTH_LONG).show();
            return;
        }
        toast(tr("Преузимам ажурирање…", "Downloading update…"));
        new Thread(() -> ApkInstaller.downloadAndInstall(this, this::toast)).start();
    }

    private void toast(final String msg) {
        final String text = serbian() && SR.containsKey(msg) ? SR.get(msg) : msg;
        runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show());
    }

    private long installedVersionCode() throws Exception {
        PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private void showUpdateDialog(String version) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle(tr("Ажурирање доступно", "Update available"))
                .setMessage(tr("Collectr " + version + " је спреман. Инсталирати сада? Подаци остају.",
                        "Collectr " + version + " is ready. Install it now? Your data stays in place."))
                .setPositiveButton(tr("Ажурирај", "Update"), (d, w) -> startApkInstall())
                .setNegativeButton(tr("Касније", "Later"), null)
                .show();
    }

    /** Methods index.html can call as window.CollectrAndroid.*. */
    private class Bridge {
        @JavascriptInterface
        public String getVersion() {
            String page = web.activeId();
            return page.isEmpty() ? BuildConfig.VERSION_NAME : BuildConfig.VERSION_NAME + " · page " + page;
        }

        /** Called by index.html once it has rendered; proves a downloaded page works. */
        @JavascriptInterface
        public void ready() {
            runOnUiThread(() -> pageReady = true);
        }

        @JavascriptInterface
        public void checkForUpdate() {
            runOnUiThread(() -> MainActivity.this.checkForUpdate(true));
        }

        /** "sr" or "en": language of native dialogs and messages. */
        @JavascriptInterface
        public void setLang(String lang) {
            getSharedPreferences("ui", MODE_PRIVATE).edit().putString("lang", lang).apply();
        }

        /** Saves a file the user names; WebView cannot download blob: URLs. base64: data is base64. */
        @JavascriptInterface
        public void saveFile(final String name, final String mime, final String data, final boolean base64) {
            runOnUiThread(() -> {
                pendingSaveBytes = base64 ? Base64.decode(data, Base64.DEFAULT) : data.getBytes(StandardCharsets.UTF_8);
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(mime);
                i.putExtra(Intent.EXTRA_TITLE, name);
                try {
                    startActivityForResult(i, REQ_SAVE_FILE);
                } catch (ActivityNotFoundException e) {
                    pendingSaveBytes = null;
                    Toast.makeText(MainActivity.this, tr("Нема апликације за чување фајлова",
                            "No app available to save files"), Toast.LENGTH_LONG).show();
                }
            });
        }

        /** Forgets stored catalog pictures (one key, or all when empty) so they download again. */
        @JavascriptInterface
        public void clearImages(String key) {
            images.clear(key);
        }

        @JavascriptInterface
        public double imagesSize() {
            return images.size();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri uri = (resultCode == RESULT_OK && data != null) ? data.getData() : null;

        if (requestCode == REQ_PICK_FILE && pendingPick != null) {
            // The camera returns no data; its photo is in the file we gave it.
            if (uri == null && resultCode == RESULT_OK && pendingPhoto != null && pendingPhoto.length() > 0) {
                uri = FileProvider.getUriForFile(this, "app.collectr.files", pendingPhoto);
            }
            pendingPick.onReceiveValue(uri != null ? new Uri[]{uri} : null);
            pendingPick = null;
            pendingPhoto = null;
        } else if (requestCode == REQ_SAVE_FILE) {
            byte[] bytes = pendingSaveBytes;
            pendingSaveBytes = null;
            if (uri == null || bytes == null) return;
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                out.write(bytes);
                Toast.makeText(this, tr("Сачувано", "Saved"), Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, tr("Чување није успело", "Could not save"), Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onBackPressed() {
        // Let the page close an open sheet or go back to the overview first.
        webView.evaluateJavascript("window.collectrBack ? window.collectrBack() : false", result -> {
            if ("true".equals(result)) return;
            if (webView.canGoBack()) webView.goBack();
            else MainActivity.super.onBackPressed();
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }
}
