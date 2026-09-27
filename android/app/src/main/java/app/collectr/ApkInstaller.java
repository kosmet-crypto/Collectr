package app.collectr;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Downloads the newest APK inside the app and hands it to Android's installer, so updating
 * is one confirmation tap instead of browser download + file manager.
 *
 * Android always asks the user the first time. On Android 12+, once Collectr has installed
 * itself this way, later updates may go through without a prompt (the system decides).
 */
final class ApkInstaller {

    /** Action of the intent Android sends back to MainActivity with the install status. */
    static final String ACTION_STATUS = "app.collectr.INSTALL_STATUS";

    interface Listener {
        void onMessage(String text);
    }

    private ApkInstaller() {
    }

    /**
     * False when the user first has to allow Collectr to install apps; the matching settings
     * screen is opened and the caller should retry when the user comes back.
     */
    static boolean ensureAllowed(Activity activity) {
        if (activity.getPackageManager().canRequestPackageInstalls()) return true;
        try {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
        } catch (Exception ignored) {
        }
        return false;
    }

    /** Extra on the status intent: whether the attempt asked Android to skip the confirmation. */
    private static final String EXTRA_QUIET = "quiet";

    /** Downloads the latest release APK and starts the install. Call from a background thread. */
    static void downloadAndInstall(Activity activity, Listener listener) {
        downloadAndInstall(activity, listener, true);
    }

    /**
     * quiet: ask Android to install without a confirmation screen (Android 12+, app updating itself).
     * When a phone refuses that, the same download is retried with Android's normal install screen.
     */
    static void downloadAndInstall(Activity activity, Listener listener, boolean quiet) {
        PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
        int sessionId = -1;
        try {
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(activity.getPackageName());
            if (quiet && Build.VERSION.SDK_INT >= 31) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            }
            sessionId = installer.createSession(params);

            URL url = new URL("https://github.com/" + BuildConfig.UPDATE_REPO
                    + "/releases/latest/download/collectr.apk");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            long length = c.getContentLengthLong();

            try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                try (InputStream in = c.getInputStream();
                     OutputStream out = session.openWrite("collectr.apk", 0, length)) {
                    byte[] b = new byte[65536];
                    for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
                    session.fsync(out);
                }
                Intent status = new Intent(activity, MainActivity.class)
                        .setAction(ACTION_STATUS)
                        .putExtra(EXTRA_QUIET, quiet)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent pending = PendingIntent.getActivity(activity, sessionId, status, flags);
                session.commit(pending.getIntentSender());
            }
        } catch (Exception e) {
            if (sessionId != -1) {
                try {
                    installer.abandonSession(sessionId);
                } catch (Exception ignored) {
                }
            }
            listener.onMessage("Could not download the update. Are you online?");
        }
    }

    /** Handles the status intent from Android; returns true if it was one. */
    static boolean handleStatus(Activity activity, Intent intent, Listener listener) {
        if (intent == null || !ACTION_STATUS.equals(intent.getAction())) return false;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    try {
                        activity.startActivity(confirm);
                    } catch (Exception e) {
                        listener.onMessage("Could not open the installer");
                    }
                }
                break;
            case PackageInstaller.STATUS_SUCCESS:
                listener.onMessage("Update installed");
                break;
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                // The user cancelled; nothing to report.
                break;
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                listener.onMessage("This update cannot replace the installed app (different signature)");
                break;
            default:
                if (intent.getBooleanExtra(EXTRA_QUIET, false)) {
                    // The quiet install was refused on this phone: try again with the install screen.
                    listener.onMessage("Opening the installer…");
                    new Thread(() -> downloadAndInstall(activity, listener, false)).start();
                } else {
                    listener.onMessage("Update failed. Try again later.");
                }
        }
        return true;
    }
}
