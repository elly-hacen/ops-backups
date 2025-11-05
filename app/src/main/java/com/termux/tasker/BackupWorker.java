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
            return Result.failure();
        }

        // Build combined script command
        StringBuilder combinedScript = new StringBuilder("echo '[AUTO BACKUP TRIGGERED]' >> ~/.termux/tasker/run-command.log");
        for (String scriptPath : enabledScripts) {
            combinedScript.append(" && if [ -f ").append(scriptPath).append(" ]; then bash ").append(scriptPath)
                    .append("; else echo 'Script not found: ").append(scriptPath).append("' >> ~/.termux/tasker/run-command.log; fi");
        }

        // Run the backup scripts
        Intent intent = new Intent("com.termux.RUN_COMMAND");
        intent.setClassName(TermuxConstants.TERMUX_PACKAGE_NAME, "com.termux.app.RunCommandService");
        intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash");
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-lc", combinedScript.toString()});
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home");
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent);
            } else {
                context.startService(intent);
            }
            Logger.logInfo(LOG_TAG, "Backup command sent to Termux successfully");
            
            // Save last backup time
            context.getSharedPreferences("BackupSettings", Context.MODE_PRIVATE)
                   .edit()
                   .putLong("last_backup_time", System.currentTimeMillis())
                   .apply();
            
            // Show notification
            showNotification(context, "Backup Complete", "Scheduled backup executed successfully");
            
            return Result.success();
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to trigger backup: " + e.getMessage());
            return Result.failure();
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
            
            // Fallback to default if no scripts configured
            if (scripts.isEmpty() && scriptsArray.length() == 0) {
                scripts.add("~/.termux/tasker/op-backup.sh");
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to load scripts: " + e.getMessage());
            // Fallback to default script
            scripts.add("~/.termux/tasker/op-backup.sh");
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "Backup Notifications";
            String description = "Notifications for scheduled backups";
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            
            NotificationManager notificationManager = 
                    context.getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }
}

