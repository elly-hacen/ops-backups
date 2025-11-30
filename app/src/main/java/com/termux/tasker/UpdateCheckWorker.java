package com.termux.tasker;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.termux.shared.logger.Logger;
import com.termux.tasker.activities.TermuxTaskerMainActivity;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.TimeUnit;

public class UpdateCheckWorker extends Worker {

    private static final String LOG_TAG = "UpdateCheckWorker";
    private static final String CHANNEL_ID = "update_notifications";
    private static final int NOTIFICATION_ID = 2001;
    private static final String WORK_NAME = "update_check_work";
    private static final String PREFS_NAME = "update_check_prefs";
    private static final String GITHUB_API_URL = "https://api.github.com/repos/elly-hacen/ops-backups/releases/latest";

    public UpdateCheckWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Logger.logInfo(LOG_TAG, "Update check worker started");
        
        Context context = getApplicationContext();
        
        // Check if auto-update check is enabled
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.getBoolean("auto_update_check_enabled", true)) {
            Logger.logInfo(LOG_TAG, "Auto update check is disabled");
            return Result.success();
        }
        
        try {
            String latestVersion = fetchLatestVersion();
            if (latestVersion == null) {
                Logger.logError(LOG_TAG, "Failed to fetch latest version");
                return Result.retry();
            }
            
            String currentVersion = BuildConfig.VERSION_NAME;
            
            if (isNewerVersion(latestVersion, currentVersion)) {
                Logger.logInfo(LOG_TAG, "New version available: " + latestVersion);
                
                // Save the latest version info
                prefs.edit()
                    .putString("latest_version", latestVersion)
                    .putLong("last_update_found_time", System.currentTimeMillis())
                    .putBoolean("update_available", true)
                    .apply();
                
                // Show notification
                showUpdateNotification(context, latestVersion);
            } else {
                Logger.logInfo(LOG_TAG, "App is up to date");
                prefs.edit().putBoolean("update_available", false).apply();
            }
            
            // Update last check time
            prefs.edit().putLong("last_update_check_time", System.currentTimeMillis()).apply();
            
            return Result.success();
            
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Update check failed: " + e.getMessage());
            return Result.retry();
        }
    }
    
    private String fetchLatestVersion() {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(GITHUB_API_URL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            
            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                return null;
            }
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();
            
            JSONObject json = new JSONObject(response.toString());
            String tagName = json.getString("tag_name");
            
            // Remove 'v' prefix if present
            if (tagName.startsWith("v")) {
                tagName = tagName.substring(1);
            }
            
            return tagName;
            
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Error fetching version: " + e.getMessage());
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
    
    private boolean isNewerVersion(String latest, String current) {
        try {
            String[] latestParts = latest.split("\\.");
            String[] currentParts = current.split("\\.");
            
            int maxLength = Math.max(latestParts.length, currentParts.length);
            
            for (int i = 0; i < maxLength; i++) {
                int latestPart = i < latestParts.length ? Integer.parseInt(latestParts[i].replaceAll("[^0-9]", "")) : 0;
                int currentPart = i < currentParts.length ? Integer.parseInt(currentParts[i].replaceAll("[^0-9]", "")) : 0;
                
                if (latestPart > currentPart) {
                    return true;
                } else if (latestPart < currentPart) {
                    return false;
                }
            }
            
            return false;
        } catch (Exception e) {
            return false;
        }
    }
    
    private void showUpdateNotification(Context context, String newVersion) {
        createNotificationChannel(context);
        
        Intent intent = new Intent(context, TermuxTaskerMainActivity.class);
        intent.putExtra("show_update_dialog", true);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        
        PendingIntent pendingIntent = PendingIntent.getActivity(
            context, 0, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_update)
            .setContentTitle("Update Available")
            .setContentText("Ops v" + newVersion + " is now available")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_download, "Download", pendingIntent);
        
        NotificationManager notificationManager = 
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, builder.build());
        }
    }
    
    private void createNotificationChannel(Context context) {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "Update Notifications",
            NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription("Notifications for app updates");
        
        NotificationManager notificationManager = 
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        
        if (notificationManager != null) {
            notificationManager.createNotificationChannel(channel);
        }
    }
    
    // Static methods to schedule/cancel the worker
    
    public static void scheduleWeeklyCheck(Context context) {
        Constraints constraints = new Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build();
        
        PeriodicWorkRequest workRequest = new PeriodicWorkRequest.Builder(
            UpdateCheckWorker.class,
            7, TimeUnit.DAYS  // Weekly check
        )
            .setConstraints(constraints)
            .setInitialDelay(1, TimeUnit.HOURS)  // First check after 1 hour
            .build();
        
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        );
        
        Logger.logInfo(LOG_TAG, "Weekly update check scheduled");
    }
    
    public static void cancelWeeklyCheck(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
        Logger.logInfo(LOG_TAG, "Weekly update check cancelled");
    }
    
    public static boolean isAutoCheckEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean("auto_update_check_enabled", true);
    }
    
    public static void setAutoCheckEnabled(Context context, boolean enabled) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean("auto_update_check_enabled", enabled).apply();
        
        if (enabled) {
            scheduleWeeklyCheck(context);
        } else {
            cancelWeeklyCheck(context);
        }
    }
    
    public static boolean hasUpdateAvailable(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean("update_available", false);
    }
    
    public static String getLatestVersion(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString("latest_version", null);
    }
    
    public static long getLastCheckTime(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getLong("last_update_check_time", 0);
    }
    
    public static void clearUpdateFlag(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean("update_available", false).apply();
    }
}

