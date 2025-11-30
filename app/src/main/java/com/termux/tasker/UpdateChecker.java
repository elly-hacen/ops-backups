package com.termux.tasker;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.core.content.FileProvider;

import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class UpdateChecker {

    private static final String LOG_TAG = "UpdateChecker";
    private static final String GITHUB_API_URL = "https://api.github.com/repos/elly-hacen/ops-backups/releases/latest";
    private static final String APK_FILE_NAME = "ops-backups-update.apk";

    private final Context context;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private volatile boolean cancelRequested = false;
    private Future<?> downloadFuture;
    private DownloadProgressListener progressListener;
    private DownloadCompleteListener completeListener;

    public UpdateChecker(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public interface UpdateCheckListener {
        void onUpdateAvailable(String latestVersion,
                               String currentVersion,
                               String releaseNotes,
                               String downloadUrl,
                               long fileSize,
                               String releaseUrl);

        void onNoUpdateAvailable(String currentVersion);

        void onError(String error);
    }

    public interface DownloadProgressListener {
        void onProgressUpdate(int progress, long downloadedBytes, long totalBytes);
    }

    public interface DownloadCompleteListener {
        void onDownloadComplete(File apkFile);

        void onDownloadFailed(String error);
    }

    public interface ReleaseNotesListener {
        void onSuccess(String version, String releaseNotes, String releaseUrl);

        void onError(String error);
    }

    private static class ReleaseInfo {
        final String version;
        final String notes;
        final String downloadUrl;
        final long size;
        final String releaseUrl;

        ReleaseInfo(String version, String notes, String downloadUrl, long size, String releaseUrl) {
            this.version = version;
            this.notes = notes;
            this.downloadUrl = downloadUrl;
            this.size = size;
            this.releaseUrl = releaseUrl;
        }
    }

    private interface ReleaseInfoCallback {
        void onSuccess(ReleaseInfo info);

        void onError(String error);
    }

    private void fetchLatestRelease(ReleaseInfoCallback callback) {
        executor.execute(() -> {
            try {
                URL url = new URL(GITHUB_API_URL);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);

                int responseCode = connection.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    String message = "Server returned " + responseCode;
                    mainHandler.post(() -> callback.onError(message));
                    connection.disconnect();
                    return;
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                connection.disconnect();

                JSONObject release = new JSONObject(response.toString());
                String tagName = release.getString("tag_name");
                String latestVersion = tagName.startsWith("v") ? tagName.substring(1) : tagName;
                String releaseNotes = release.optString("body", "");
                String releaseUrl = release.optString("html_url", "");

                String downloadUrl = null;
                long fileSize = 0L;
                JSONArray assets = release.getJSONArray("assets");
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    String assetName = asset.getString("name");
                    if (assetName.startsWith("ops-backups") && assetName.endsWith(".apk")) {
                        downloadUrl = asset.optString("browser_download_url", null);
                        fileSize = asset.optLong("size", 0L);
                        if (assetName.contains("release")) {
                            break;
                        }
                    }
                }

                ReleaseInfo info = new ReleaseInfo(latestVersion, releaseNotes, downloadUrl, fileSize, releaseUrl);
                mainHandler.post(() -> callback.onSuccess(info));
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Release fetch failed: " + e.getMessage());
                String msg = "Failed to load release info";
                mainHandler.post(() -> callback.onError(msg));
            }
        });
    }

    public void checkForUpdates(UpdateCheckListener listener) {
        fetchLatestRelease(new ReleaseInfoCallback() {
            @Override
            public void onSuccess(ReleaseInfo info) {
                String currentVersion = BuildConfig.VERSION_NAME;
                boolean hasUpdate = isNewerVersion(info.version, currentVersion);
                if (hasUpdate && info.downloadUrl != null) {
                    listener.onUpdateAvailable(info.version, currentVersion, info.notes, info.downloadUrl, info.size, info.releaseUrl);
                } else if (!hasUpdate) {
                    listener.onNoUpdateAvailable(currentVersion);
                } else {
                    listener.onError("Update available but no APK found in release");
                }
            }

            @Override
            public void onError(String error) {
                listener.onError(error);
            }
        });
    }

    public void fetchLatestReleaseNotes(ReleaseNotesListener listener) {
        fetchLatestRelease(new ReleaseInfoCallback() {
            @Override
            public void onSuccess(ReleaseInfo info) {
                listener.onSuccess(info.version, info.notes, info.releaseUrl);
            }

            @Override
            public void onError(String error) {
                listener.onError(error);
            }
        });
    }

    private boolean isNewerVersion(String latest, String current) {
        try {
            String latestClean = latest.split("-")[0];
            String currentClean = current.split("-")[0];
            String[] latestParts = latestClean.split("\\.");
            String[] currentParts = currentClean.split("\\.");
            int maxLength = Math.max(latestParts.length, currentParts.length);
            for (int i = 0; i < maxLength; i++) {
                int latestPart = i < latestParts.length ? Integer.parseInt(latestParts[i]) : 0;
                int currentPart = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
                if (latestPart > currentPart) {
                    return true;
                } else if (latestPart < currentPart) {
                    return false;
                }
            }
            return false;
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Version comparison failed: " + e.getMessage());
            return false;
        }
    }

    public void downloadUpdate(String downloadUrl, DownloadProgressListener progressListener, DownloadCompleteListener completeListener) {
        cancelDownload();
        this.progressListener = progressListener;
        this.completeListener = completeListener;
        this.cancelRequested = false;
        downloadFuture = executor.submit(() -> performDownload(downloadUrl));
    }

    private void performDownload(String downloadUrl) {
        HttpURLConnection connection = null;
        BufferedInputStream input = null;
        FileOutputStream output = null;
        File apkFile = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_FILE_NAME);
        try {
            if (apkFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                apkFile.delete();
            }
            URL url = new URL(downloadUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("Accept", "application/octet-stream");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.connect();
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("HTTP " + connection.getResponseCode());
            }
            long totalBytes = connection.getContentLengthLong();
            input = new BufferedInputStream(connection.getInputStream());
            output = new FileOutputStream(apkFile);
            byte[] buffer = new byte[8192];
            long downloadedBytes = 0;
            int read;
            while (!cancelRequested && (read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                downloadedBytes += read;
                postProgress(downloadedBytes, totalBytes, false);
            }
            output.flush();
            if (cancelRequested) {
                apkFile.delete();
                return;
            }
            postProgress(downloadedBytes, totalBytes, true);
            mainHandler.post(() -> {
                if (completeListener != null) {
                    completeListener.onDownloadComplete(apkFile);
                }
            });
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Download failed: " + e.getMessage());
            apkFile.delete();
            mainHandler.post(() -> {
                if (completeListener != null) {
                    completeListener.onDownloadFailed("Download failed: " + e.getMessage());
                }
            });
        } finally {
            try {
                if (output != null) output.close();
            } catch (Exception ignored) {}
            try {
                if (input != null) input.close();
            } catch (Exception ignored) {}
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private int lastProgress = -1;
    private long lastProgressUpdateMs = 0L;

    private void postProgress(long downloadedBytes, long totalBytes, boolean forceFinal) {
        if (progressListener == null) return;
        int progress = (totalBytes > 0) ? (int) ((downloadedBytes * 100L) / totalBytes) : 0;
        long now = SystemClock.uptimeMillis();
        if (forceFinal || (progress != lastProgress && (now - lastProgressUpdateMs) >= 80)) {
            lastProgress = progress;
            lastProgressUpdateMs = now;
            long finalDownloaded = downloadedBytes;
            long finalTotal = totalBytes;
            mainHandler.post(() -> progressListener.onProgressUpdate(progress, finalDownloaded, finalTotal));
        }
    }

    public void installApk(File apkFile) {
        // Verify APK signature before installing
        if (!verifyApkSignature(apkFile)) {
            Logger.logError(LOG_TAG, "APK signature verification failed - refusing to install");
            return;
        }
        
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri apkUri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                apkUri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", apkFile);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                apkUri = Uri.fromFile(apkFile);
            }
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            Logger.logInfo(LOG_TAG, "Install intent started for: " + apkFile.getAbsolutePath());
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to start install: " + e.getMessage());
        }
    }
    
    /**
     * Verifies that the downloaded APK is signed with the same certificate as the currently installed app.
     * This prevents MITM attacks where an attacker substitutes a malicious APK.
     */
    private boolean verifyApkSignature(File apkFile) {
        try {
            android.content.pm.PackageManager pm = context.getPackageManager();
            
            // Get current app's signing certificate
            android.content.pm.PackageInfo currentInfo;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                currentInfo = pm.getPackageInfo(context.getPackageName(), 
                    android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
            } else {
                currentInfo = pm.getPackageInfo(context.getPackageName(), 
                    android.content.pm.PackageManager.GET_SIGNATURES);
            }
            
            // Get downloaded APK's signing certificate
            android.content.pm.PackageInfo apkInfo;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                apkInfo = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(),
                    android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
            } else {
                apkInfo = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(),
                    android.content.pm.PackageManager.GET_SIGNATURES);
            }
            
            if (apkInfo == null) {
                Logger.logError(LOG_TAG, "Failed to parse downloaded APK");
                return false;
            }
            
            // Compare signatures
            android.content.pm.Signature[] currentSigs;
            android.content.pm.Signature[] apkSigs;
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                currentSigs = currentInfo.signingInfo.getApkContentsSigners();
                apkSigs = apkInfo.signingInfo != null ? apkInfo.signingInfo.getApkContentsSigners() : null;
            } else {
                currentSigs = currentInfo.signatures;
                apkSigs = apkInfo.signatures;
            }
            
            if (apkSigs == null || apkSigs.length == 0) {
                Logger.logError(LOG_TAG, "Downloaded APK has no signatures");
                return false;
            }
            
            // Check if any signature matches
            for (android.content.pm.Signature currentSig : currentSigs) {
                for (android.content.pm.Signature apkSig : apkSigs) {
                    if (currentSig.equals(apkSig)) {
                        Logger.logInfo(LOG_TAG, "APK signature verified successfully");
                        return true;
                    }
                }
            }
            
            Logger.logError(LOG_TAG, "APK signature does not match installed app");
            return false;
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Signature verification error: " + e.getMessage());
            return false;
        }
    }

    public void cancelDownload() {
        cancelRequested = true;
        if (downloadFuture != null) {
            downloadFuture.cancel(true);
        }
    }

    public void cleanup() {
        cancelDownload();
        executor.shutdown();
        try {
            // Wait up to 2 seconds for tasks to complete
            if (!executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    @Override
    protected void finalize() throws Throwable {
        // Safety net: ensure executor is shut down even if cleanup() wasn't called
        if (!executor.isShutdown()) {
            Logger.logError(LOG_TAG, "UpdateChecker finalized without cleanup() - forcing shutdown");
            executor.shutdownNow();
        }
        super.finalize();
    }

    public static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024f);
        return String.format("%.1f MB", bytes / (1024f * 1024f));
    }
}
