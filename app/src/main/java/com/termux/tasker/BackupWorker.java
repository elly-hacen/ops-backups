package com.termux.tasker;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.tasker.R;
import com.termux.tasker.activities.TermuxTaskerMainActivity;

public class BackupWorker extends Worker {

    private static final String LOG_TAG = "BackupWorker";
    private static final String CHANNEL_ID = "backup_notifications";
    private static final int NOTIFICATION_ID = 1001;
    private static final String STATUS_CHANNEL_ID = "backup_status";
    private static final int STATUS_NOTIFICATION_ID = 1000;

    public BackupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Logger.logInfo(LOG_TAG, "AUTO BACKUP STARTED - Scheduled backup worker executing");

        Context context = getApplicationContext();

        // Get enabled scripts
        java.util.List<String> enabledScripts = getEnabledScripts(context);
        if (enabledScripts.isEmpty()) {
            Logger.logError(LOG_TAG, "No enabled scripts configured");
            showNotification(context, "Backup Failed", "No enabled scripts configured");
            logBackupResult(context, "Failed", "No enabled scripts configured");
            return Result.failure();
        }

        // Result file for detecting actual success/failure
        String resultFile = "/sdcard/ops-auto-backup-result-" + System.currentTimeMillis() + ".txt";
        
        // Build combined script command that writes result
        StringBuilder combinedScript = new StringBuilder();
        combinedScript.append("rm -f ").append(resultFile).append(" && (");
        combinedScript.append("echo '[AUTO BACKUP TRIGGERED]' >> ~/.termux/tasker/run-command.log");
        for (String scriptPath : enabledScripts) {
            combinedScript.append(" && if [ -f ").append(scriptPath).append(" ]; then bash ").append(scriptPath)
                    .append("; else echo 'Script not found: ").append(scriptPath).append("' >> ~/.termux/tasker/run-command.log && exit 1; fi");
        }
        combinedScript.append(" && echo 'SUCCESS' > ").append(resultFile);
        combinedScript.append(") || echo 'FAILED' > ").append(resultFile);

        // Run the backup scripts
        Intent intent = new Intent("com.termux.RUN_COMMAND");
        intent.setClassName(TermuxConstants.TERMUX_PACKAGE_NAME, "com.termux.app.RunCommandService");
        intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash");
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-lc", combinedScript.toString()});
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home");
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);

        try {
            // Android 12+ always uses foreground service
            ContextCompat.startForegroundService(context, intent);
            Logger.logInfo(LOG_TAG, "Backup command sent to Termux successfully");
            
            // Wait for result file (poll up to 30 seconds)
            int resultCode = waitForResult(resultFile, 30);
            
            // Save last backup time for any success
            if (resultCode == RESULT_PUSHED || resultCode == RESULT_NO_CHANGES) {
                context.getSharedPreferences("BackupSettings", Context.MODE_PRIVATE)
                       .edit()
                       .putLong("last_backup_time", System.currentTimeMillis())
                       .apply();
            }
            
            switch (resultCode) {
                case RESULT_PUSHED:
                    showNotification(context, "Backup Complete", "Changes pushed to GitHub");
                    logBackupResult(context, "Completed", "Successfully pushed to GitHub");
                    return Result.success();
                case RESULT_NO_CHANGES:
                    showNotification(context, "Backup Complete", "No changes to push");
                    logBackupResult(context, "No Changes", "Repository already up to date");
                    return Result.success();
                case RESULT_FAILED:
                    showNotification(context, "Backup Failed", "Script execution failed");
                    logBackupResult(context, "Failed", "Script execution failed");
                    return Result.failure();
                case RESULT_TIMEOUT:
                default:
                    showNotification(context, "Backup Failed", "Script timed out");
                    logBackupResult(context, "Timeout", "Script execution timed out");
                    return Result.failure();
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to trigger backup: " + e.getMessage());
            showNotification(context, "Backup Failed", e.getMessage());
            logBackupResult(context, "Failed", e.getMessage());
            return Result.failure();
        }
    }
    
    // Result codes for waitForResult
    private static final int RESULT_PUSHED = 1;
    private static final int RESULT_NO_CHANGES = 2;
    private static final int RESULT_FAILED = -1;
    private static final int RESULT_TIMEOUT = -2;

    private int waitForResult(String resultFilePath, int timeoutSeconds) {
        java.io.File resultFile = new java.io.File(resultFilePath);
        int attempts = timeoutSeconds * 2; // Check every 500ms
        
        for (int i = 0; i < attempts; i++) {
            try {
                Thread.sleep(500);
                if (resultFile.exists()) {
                    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(resultFile));
                    String result = reader.readLine();
                    reader.close();
                    resultFile.delete();
                    
                    if (result != null && result.startsWith("SUCCESS")) {
                        if (result.contains("NO_CHANGES")) {
                            return RESULT_NO_CHANGES;
                        }
                        return RESULT_PUSHED;
                    } else if (result != null && result.startsWith("FAILED")) {
                        return RESULT_FAILED;
                    }
                }
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Error checking result file: " + e.getMessage());
            }
        }
        
        // Timeout - clean up and return failure
        resultFile.delete();
        return RESULT_TIMEOUT;
    }
    
    private void logBackupResult(Context context, String status, String message) {
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences("BackupSettings", Context.MODE_PRIVATE);
            String historyJson = prefs.getString("backup_history", "[]");
            org.json.JSONArray historyArray = new org.json.JSONArray(historyJson);
            
            org.json.JSONObject event = new org.json.JSONObject();
            event.put("timestamp", System.currentTimeMillis());
            event.put("status", status);
            event.put("message", message);
            event.put("source", "auto");
            
            historyArray.put(event);
            
            // Keep only last 50 entries
            while (historyArray.length() > 50) {
                historyArray.remove(0);
            }
            
            prefs.edit().putString("backup_history", historyArray.toString()).apply();
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to log backup result: " + e.getMessage());
        }
    }

    private java.util.List<String> getEnabledScripts(Context context) {
        java.util.List<String> scripts = new java.util.ArrayList<>();
        try {
            String scriptsJson = context.getSharedPreferences("BackupSettings", Context.MODE_PRIVATE)
                    .getString("backup_scripts", "[]");
            org.json.JSONArray scriptsArray = new org.json.JSONArray(scriptsJson);
            
            for (int i = 0; i < scriptsArray.length(); i++) {
                org.json.JSONObject script = scriptsArray.getJSONObject(i);
                if (script.optBoolean("enabled", true)) {
                    scripts.add(script.getString("path"));
                }
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to load scripts: " + e.getMessage());
        }
        return scripts;
    }

    private void showNotification(Context context, String title, String message) {
        createNotificationChannel(context);
        
        Intent intent = new Intent(context, TermuxTaskerMainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_schedule)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);
        
        NotificationManager notificationManager = 
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, builder.build());
        }
    }

    private void createNotificationChannel(Context context) {
        NotificationManager notificationManager = 
                context.getSystemService(NotificationManager.class);
        if (notificationManager != null) {
            // Backup completion notifications
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, 
                    "Backup Notifications", 
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Notifications for scheduled backups");
            notificationManager.createNotificationChannel(channel);
            
            // Silent status notifications
            NotificationChannel statusChannel = new NotificationChannel(
                    STATUS_CHANNEL_ID,
                    "Background Status",
                    NotificationManager.IMPORTANCE_LOW);
            statusChannel.setDescription("Shows when automatic backups are active");
            statusChannel.setShowBadge(false);
            statusChannel.setSound(null, null);
            notificationManager.createNotificationChannel(statusChannel);
        }
    }
    
    public static void showBackgroundStatusNotification(Context context) {
        createStatusChannel(context);
        
        Intent intent = new Intent(context, com.termux.tasker.activities.TermuxTaskerMainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, STATUS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_schedule)
                .setContentTitle("Ops - Automatic Backups Active")
                .setContentText("Monitoring for scheduled backups")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(pendingIntent);
        
        NotificationManager notificationManager = 
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.notify(STATUS_NOTIFICATION_ID, builder.build());
        }
    }
    
    public static void hideBackgroundStatusNotification(Context context) {
        NotificationManager notificationManager = 
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.cancel(STATUS_NOTIFICATION_ID);
        }
    }
    
    private static void createStatusChannel(Context context) {
            NotificationManager notificationManager = 
                    context.getSystemService(NotificationManager.class);
            if (notificationManager != null) {
            NotificationChannel statusChannel = new NotificationChannel(
                    STATUS_CHANNEL_ID,
                    "Background Status",
                    NotificationManager.IMPORTANCE_LOW);
            statusChannel.setDescription("Shows when automatic backups are active");
            statusChannel.setShowBadge(false);
            statusChannel.setSound(null, null);
            notificationManager.createNotificationChannel(statusChannel);
        }
    }
}

