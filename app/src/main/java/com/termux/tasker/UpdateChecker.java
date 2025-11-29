package com.termux.tasker;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.FileProvider;

import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UpdateChecker {

    private static final String LOG_TAG = "UpdateChecker";
    private static final String GITHUB_API_URL = "https://api.github.com/repos/elly-hacen/ops-backups/releases/latest";
    private static final String APK_FILE_NAME = "ops-backups-update.apk";
    
    private final Context context;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private DownloadManager downloadManager;
    private long downloadId = -1;
    private BroadcastReceiver downloadReceiver;
    private DownloadProgressListener progressListener;
    private DownloadCompleteListener completeListener;

    public interface UpdateCheckListener {
        void onUpdateAvailable(String latestVersion, String currentVersion, String releaseNotes, String downloadUrl, long fileSize);
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

    public UpdateChecker(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
    }

    public void checkForUpdates(UpdateCheckListener listener) {
        executor.execute(() -> {
            try {
                URL url = new URL(GITHUB_API_URL);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    JSONObject release = new JSONObject(response.toString());
                    String tagName = release.getString("tag_name");
                    String latestVersion = tagName.startsWith("v") ? tagName.substring(1) : tagName;
                    String releaseNotes = release.optString("body", "No release notes available");
                    
                    // Get APK download URL from assets
                    // APK naming convention: ops-backups_v{version}+release.apk or ops-backups_{tag}.apk
                    String downloadUrl = null;
                    long fileSize = 0;
                    JSONArray assets = release.getJSONArray("assets");
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        String assetName = asset.getString("name");
                        // Look for release APK (prefer release over debug)
                        if (assetName.startsWith("ops-backups") && assetName.endsWith(".apk")) {
                            // Prefer release build if multiple APKs exist
                            if (assetName.contains("release") || downloadUrl == null) {
                                downloadUrl = asset.getString("browser_download_url");
                                fileSize = asset.getLong("size");
                                if (assetName.contains("release")) {
                                    break; // Found release APK, stop searching
                                }
                            }
                        }
                    }

                    String currentVersion = BuildConfig.VERSION_NAME;
                    boolean hasUpdate = isNewerVersion(latestVersion, currentVersion);

                    String finalDownloadUrl = downloadUrl;
                    long finalFileSize = fileSize;
                    mainHandler.post(() -> {
                        if (hasUpdate && finalDownloadUrl != null) {
                            listener.onUpdateAvailable(latestVersion, currentVersion, releaseNotes, finalDownloadUrl, finalFileSize);
                        } else if (finalDownloadUrl == null && hasUpdate) {
                            listener.onError("Update available but no APK found in release");
                        } else {
                            listener.onNoUpdateAvailable(currentVersion);
                        }
                    });
                } else {
                    mainHandler.post(() -> listener.onError("Server returned error: " + responseCode));
                }
                connection.disconnect();
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Failed to check for updates: " + e.getMessage());
                mainHandler.post(() -> listener.onError("Failed to check for updates: " + e.getMessage()));
            }
        });
    }

    private boolean isNewerVersion(String latest, String current) {
        try {
            // Remove any pre-release suffixes for comparison
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
        this.progressListener = progressListener;
        this.completeListener = completeListener;

        // Delete old APK if exists
        File oldApk = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_FILE_NAME);
        if (oldApk.exists()) {
            oldApk.delete();
        }

        // Setup download request
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl));
        request.setTitle("Ops Update");
        request.setDescription("Downloading update...");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
        request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, APK_FILE_NAME);
        request.setMimeType("application/vnd.android.package-archive");

        // Register receiver for download complete
        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == downloadId) {
                    handleDownloadComplete();
                }
            }
        };
        
        context.registerReceiver(downloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED);

        // Start download
        downloadId = downloadManager.enqueue(request);
        Logger.logInfo(LOG_TAG, "Download started with ID: " + downloadId);

        // Start progress monitoring
        startProgressMonitoring();
    }

    private void startProgressMonitoring() {
        executor.execute(() -> {
            boolean downloading = true;
            while (downloading) {
                DownloadManager.Query query = new DownloadManager.Query();
                query.setFilterById(downloadId);
                
                Cursor cursor = downloadManager.query(query);
                if (cursor != null && cursor.moveToFirst()) {
                    int statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                    int bytesDownloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                    int bytesTotalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                    
                    if (statusIndex >= 0 && bytesDownloadedIndex >= 0 && bytesTotalIndex >= 0) {
                        int status = cursor.getInt(statusIndex);
                        long bytesDownloaded = cursor.getLong(bytesDownloadedIndex);
                        long bytesTotal = cursor.getLong(bytesTotalIndex);
                        
                        if (status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING) {
                            int progress = bytesTotal > 0 ? (int) ((bytesDownloaded * 100) / bytesTotal) : 0;
                            if (progressListener != null) {
                                mainHandler.post(() -> progressListener.onProgressUpdate(progress, bytesDownloaded, bytesTotal));
                            }
                        } else if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                            downloading = false;
                        }
                    }
                    cursor.close();
                } else {
                    downloading = false;
                }
                
                try {
                    Thread.sleep(200); // Update every 200ms
                } catch (InterruptedException e) {
                    downloading = false;
                }
            }
        });
    }

    private void handleDownloadComplete() {
        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterById(downloadId);
        
        Cursor cursor = downloadManager.query(query);
        if (cursor != null && cursor.moveToFirst()) {
            int statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            int reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON);
            
            if (statusIndex >= 0) {
                int status = cursor.getInt(statusIndex);
                
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    File apkFile = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_FILE_NAME);
                    if (apkFile.exists() && completeListener != null) {
                        mainHandler.post(() -> completeListener.onDownloadComplete(apkFile));
                    } else if (completeListener != null) {
                        mainHandler.post(() -> completeListener.onDownloadFailed("Downloaded file not found"));
                    }
                } else if (status == DownloadManager.STATUS_FAILED) {
                    int reason = reasonIndex >= 0 ? cursor.getInt(reasonIndex) : -1;
                    String error = getDownloadErrorMessage(reason);
                    if (completeListener != null) {
                        mainHandler.post(() -> completeListener.onDownloadFailed(error));
                    }
                }
            }
            cursor.close();
        }
        
        // Cleanup receiver
        try {
            context.unregisterReceiver(downloadReceiver);
        } catch (Exception e) {
            // Ignore if already unregistered
        }
    }

    private String getDownloadErrorMessage(int reason) {
        switch (reason) {
            case DownloadManager.ERROR_CANNOT_RESUME:
                return "Download cannot be resumed";
            case DownloadManager.ERROR_DEVICE_NOT_FOUND:
                return "Storage device not found";
            case DownloadManager.ERROR_FILE_ALREADY_EXISTS:
                return "File already exists";
            case DownloadManager.ERROR_FILE_ERROR:
                return "File error";
            case DownloadManager.ERROR_HTTP_DATA_ERROR:
                return "HTTP data error";
            case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                return "Insufficient storage space";
            case DownloadManager.ERROR_TOO_MANY_REDIRECTS:
                return "Too many redirects";
            case DownloadManager.ERROR_UNHANDLED_HTTP_CODE:
                return "Unhandled HTTP code";
            case DownloadManager.ERROR_UNKNOWN:
            default:
                return "Unknown download error";
        }
    }

    public void installApk(File apkFile) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri apkUri;
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                apkUri = FileProvider.getUriForFile(context, 
                    context.getPackageName() + ".fileprovider", apkFile);
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

    public void cancelDownload() {
        if (downloadId != -1) {
            downloadManager.remove(downloadId);
            downloadId = -1;
        }
        try {
            if (downloadReceiver != null) {
                context.unregisterReceiver(downloadReceiver);
            }
        } catch (Exception e) {
            // Ignore
        }
    }

    public void cleanup() {
        cancelDownload();
        executor.shutdown();
    }

    public static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}

