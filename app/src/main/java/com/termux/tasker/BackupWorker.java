package com.termux.tasker;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

public class BackupWorker extends Worker {

    private static final String LOG_TAG = "BackupWorker";

    public BackupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Logger.logInfo(LOG_TAG, "AUTO BACKUP STARTED - Scheduled backup worker executing");

        Context context = getApplicationContext();

        // Run the backup script
        Intent intent = new Intent("com.termux.RUN_COMMAND");
        intent.setClassName(TermuxConstants.TERMUX_PACKAGE_NAME, "com.termux.app.RunCommandService");
        intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash");
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-lc", "echo '[AUTO BACKUP TRIGGERED]' >> ~/.termux/tasker/run-command.log && bash ~/.termux/tasker/op-backup.sh"});
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
            
            return Result.success();
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to trigger backup: " + e.getMessage());
            return Result.failure();
        }
    }
}

