package com.termux.tasker.activities;

import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.navigation.NavigationView;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.theme.TermuxThemeUtils;
import com.termux.shared.theme.NightMode;
import com.termux.tasker.BackupWorker;
import com.termux.tasker.R;
import com.termux.tasker.TermuxTaskerApplication;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class TermuxTaskerMainActivity extends AppCompatActivity {

    public static final String LOG_TAG = "TermuxTaskerMainActivity";
    private static final String PREFS_NAME = "BackupSettings";
    private static final String WORK_NAME = "AutoBackupWork";

    private TextView backupStatusTextView;
    private TextView lastBackupTextView;
    private MaterialSwitch scheduleSwitch;
    private AutoCompleteTextView intervalSpinner;
    private CheckBox wifiOnlyCheckbox;
    private CheckBox chargingOnlyCheckbox;
    private Button testScheduleButton;
    private SharedPreferences prefs;
    private DrawerLayout drawerLayout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Enable dynamic colors on Android 12+ (Material You)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                getTheme().applyStyle(com.google.android.material.R.style.ThemeOverlay_Material3_DynamicColors_DayNight, true);
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Dynamic colors not available: " + e.getMessage());
            }
        }
        
        setContentView(R.layout.activity_termux_tasker_main);

        // Set NightMode.APP_NIGHT_MODE
        TermuxThemeUtils.setAppNightMode(this);
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);

        // Setup drawer and toolbar
        drawerLayout = findViewById(R.id.drawer_layout);
        NavigationView navigationView = findViewById(R.id.nav_view);
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setDisplayShowTitleEnabled(true);
                getSupportActionBar().setTitle(getString(R.string.app_title));
            }
            
            // Setup menu icon on right side
            findViewById(R.id.menu_icon).setOnClickListener(v -> {
                Logger.logInfo(LOG_TAG, "Menu icon clicked");
                if (drawerLayout != null) {
                    drawerLayout.openDrawer(GravityCompat.END);
                    Logger.logInfo(LOG_TAG, "Drawer opened");
                }
            });
        }

        // Setup navigation drawer
        if (navigationView != null) {
            navigationView.setNavigationItemSelectedListener(item -> {
                int id = item.getItemId();
                Logger.logInfo(LOG_TAG, "Drawer menu item clicked: " + id);
                if (id == R.id.nav_schedules) {
                    startActivity(new Intent(this, SchedulesActivity.class));
                } else if (id == R.id.nav_usage_guide) {
                    startActivity(new Intent(this, UsageGuideActivity.class));
                } else if (id == R.id.nav_about) {
                    showAboutDialog();
                } else if (id == R.id.nav_check_updates) {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.update_url)));
                    startActivity(browserIntent);
                }
                drawerLayout.closeDrawer(GravityCompat.END);
                return true;
            });
        } else {
            Logger.logError(LOG_TAG, "NavigationView is null!");
        }

        backupStatusTextView = findViewById(R.id.textview_backup_status);
        lastBackupTextView = findViewById(R.id.textview_last_backup);
        scheduleSwitch = findViewById(R.id.switch_enable_schedule);
        intervalSpinner = findViewById(R.id.spinner_interval);
        wifiOnlyCheckbox = findViewById(R.id.checkbox_wifi_only);
        chargingOnlyCheckbox = findViewById(R.id.checkbox_charging_only);
        testScheduleButton = findViewById(R.id.button_test_schedule);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        findViewById(R.id.button_run_backup).setOnClickListener(v -> triggerBackupCommand());
        testScheduleButton.setOnClickListener(v -> testSchedulerNow());

        setupScheduleUI();

        updateBackupStatus("");
        updateLastBackupTime();

        // Handle back button for drawer
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.END)) {
                    drawerLayout.closeDrawer(GravityCompat.END);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Set log level for the app
        TermuxTaskerApplication.setLogConfig(this, false);

        Logger.logVerbose(LOG_TAG, "onResume");
    }

    private void showAboutDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.about_dialog, null);
        
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();
        
        // Setup buttons
        Button checkUpdatesButton = dialogView.findViewById(R.id.button_check_updates);
        Button closeButton = dialogView.findViewById(R.id.button_close);
        
        if (checkUpdatesButton != null) {
            checkUpdatesButton.setOnClickListener(v -> {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.update_url)));
                startActivity(browserIntent);
            });
        }
        
        if (closeButton != null) {
            closeButton.setOnClickListener(v -> dialog.dismiss());
        }
        
        dialog.show();
    }

    private void triggerBackupCommand() {
        String validationError = validateTermuxReady();
        if (validationError != null) {
            updateBackupStatus(validationError);
            Toast.makeText(this, validationError, Toast.LENGTH_LONG).show();
            return;
        }

        updateBackupStatus(getString(R.string.status_backup_running));

        // Run the user's backup script
        final String script = "if [ -f ~/.termux/tasker/op-backup.sh ]; then bash ~/.termux/tasker/op-backup.sh; else echo 'Script not found at ~/.termux/tasker/op-backup.sh'; exit 1; fi";
        String error = startBackgroundCommand(script);
        if (error == null) {
            saveLastBackupTime();
            updateBackupStatus(getString(R.string.status_backup_success));
            Toast.makeText(this, getString(R.string.status_backup_success), Toast.LENGTH_SHORT).show();
        } else {
            updateBackupStatus(error);
            Toast.makeText(this, error, Toast.LENGTH_LONG).show();
        }
    }

    private String getTermuxAccessibilityError() {
        PackageManager packageManager = getPackageManager();
        ApplicationInfo applicationInfo;
        try {
            applicationInfo = packageManager.getApplicationInfo(TermuxConstants.TERMUX_PACKAGE_NAME, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return getString(R.string.error_termux_not_installed, TermuxConstants.TERMUX_PACKAGE_NAME);
        }

        if (!applicationInfo.enabled) {
            return getString(R.string.error_termux_app_disabled, TermuxConstants.TERMUX_APP_NAME);
        }

        return null;
    }

    private String validateTermuxReady() {
        String accessibilityError = getTermuxAccessibilityError();
        if (accessibilityError != null) {
            return accessibilityError;
        }

        // Check if we have RUN_COMMAND permission
        if (checkSelfPermission("com.termux.permission.RUN_COMMAND") != PackageManager.PERMISSION_GRANTED) {
            return "Missing com.termux.permission.RUN_COMMAND permission";
        }

        return null;
    }

    private String startBackgroundCommand(String script) {
        Intent intent = new Intent("com.termux.RUN_COMMAND");
        intent.setClassName(TermuxConstants.TERMUX_PACKAGE_NAME, "com.termux.app.RunCommandService");
        intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash");
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-lc", script});
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home");
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, intent);
            } else {
                startService(intent);
            }
            return null;
        } catch (Exception e) {
            String message = "Failed to run command: " + e.getMessage();
            Logger.logError(LOG_TAG, message);
            return message;
        }
    }

    private void updateBackupStatus(String message) {
        if (backupStatusTextView == null) return;
        backupStatusTextView.setText(message);
    }

    private void setupScheduleUI() {
        // Setup interval dropdown
        String[] intervals = getResources().getStringArray(R.array.backup_intervals);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_dropdown_item_1line, intervals);
        intervalSpinner.setAdapter(adapter);

        // Load saved settings
        boolean enabled = prefs.getBoolean("schedule_enabled", false);
        int intervalIndex = prefs.getInt("interval_index", 3); // Default: Once daily
        boolean wifiOnly = prefs.getBoolean("wifi_only", true);
        boolean chargingOnly = prefs.getBoolean("charging_only", false);

        scheduleSwitch.setChecked(enabled);
        intervalSpinner.setText(intervals[intervalIndex], false);
        wifiOnlyCheckbox.setChecked(wifiOnly);
        chargingOnlyCheckbox.setChecked(chargingOnly);
        
        // Show dropdown when clicked
        intervalSpinner.setOnClickListener(v -> intervalSpinner.showDropDown());

        // Update test button state
        updateTestButtonState();

        // Setup listeners
        scheduleSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("schedule_enabled", isChecked).apply();
            updateTestButtonState();
            if (isChecked) {
                scheduleBackupWork();
                Toast.makeText(this, "Auto backup enabled", Toast.LENGTH_SHORT).show();
            } else {
                cancelBackupWork();
                Toast.makeText(this, "Auto backup disabled", Toast.LENGTH_SHORT).show();
            }
        });

        intervalSpinner.setOnItemClickListener((parent, view, position, id) -> {
            prefs.edit().putInt("interval_index", position).apply();
            if (scheduleSwitch.isChecked()) {
                scheduleBackupWork();
            }
        });

        wifiOnlyCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("wifi_only", isChecked).apply();
            if (scheduleSwitch.isChecked()) {
                scheduleBackupWork();
            }
        });

        chargingOnlyCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("charging_only", isChecked).apply();
            if (scheduleSwitch.isChecked()) {
                scheduleBackupWork();
            }
        });
    }

    private void scheduleBackupWork() {
        int intervalIndex = prefs.getInt("interval_index", 3);
        boolean wifiOnly = prefs.getBoolean("wifi_only", true);
        boolean chargingOnly = prefs.getBoolean("charging_only", false);

        long intervalHours = getIntervalHours(intervalIndex);

        Constraints.Builder constraintsBuilder = new Constraints.Builder();
        
        if (wifiOnly) {
            constraintsBuilder.setRequiredNetworkType(NetworkType.UNMETERED);
        }
        
        if (chargingOnly) {
            constraintsBuilder.setRequiresCharging(true);
        }

        PeriodicWorkRequest backupWork = new PeriodicWorkRequest.Builder(
                BackupWorker.class,
                intervalHours,
                TimeUnit.HOURS)
                .setConstraints(constraintsBuilder.build())
                .build();

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                backupWork);

        Logger.logInfo(LOG_TAG, "Scheduled backup every " + intervalHours + " hours");
    }

    private void cancelBackupWork() {
        WorkManager.getInstance(this).cancelUniqueWork(WORK_NAME);
        Logger.logInfo(LOG_TAG, "Cancelled scheduled backups");
    }

    private void updateTestButtonState() {
        boolean scheduleEnabled = scheduleSwitch.isChecked();
        testScheduleButton.setEnabled(scheduleEnabled);
        testScheduleButton.setAlpha(scheduleEnabled ? 1.0f : 0.5f);
    }

    private void testSchedulerNow() {
        if (!scheduleSwitch.isChecked()) {
            Toast.makeText(this, "Enable auto backup first", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean wifiOnly = prefs.getBoolean("wifi_only", true);
        boolean chargingOnly = prefs.getBoolean("charging_only", false);

        // Check current state
        boolean isCharging = isDeviceCharging();
        boolean isOnWifi = isConnectedToWifi();

        Logger.logInfo(LOG_TAG, "Scheduling backup - Charging: " + isCharging + ", WiFi: " + isOnWifi);

        // Build message based on conditions
        StringBuilder message = new StringBuilder();
        boolean conditionsMet = true;

        if (wifiOnly && !isOnWifi) {
            message.append("Waiting for WiFi");
            conditionsMet = false;
        }

        if (chargingOnly && !isCharging) {
            if (message.length() > 0) message.append(" and ");
            message.append("charging");
            conditionsMet = false;
        }

        if (conditionsMet) {
            Toast.makeText(this, getString(R.string.backup_scheduled_successfully), Toast.LENGTH_SHORT).show();
            logBackupEvent("Scheduled", "Running now");
        } else {
            Toast.makeText(this, getString(R.string.backup_already_scheduled) + "\n" + message, Toast.LENGTH_LONG).show();
            logBackupEvent("Queued", "Waiting for " + message);
        }

        // Build constraints based on settings
        Constraints.Builder constraintsBuilder = new Constraints.Builder();
        
        if (wifiOnly) {
            constraintsBuilder.setRequiredNetworkType(NetworkType.UNMETERED);
        }
        
        if (chargingOnly) {
            constraintsBuilder.setRequiresCharging(true);
        }

        // Create a one-time work request with the same constraints
        OneTimeWorkRequest testWork = new OneTimeWorkRequest.Builder(BackupWorker.class)
                .setConstraints(constraintsBuilder.build())
                .build();

        WorkManager.getInstance(this).enqueue(testWork);
        
        // Update UI after 3 seconds
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            updateLastBackupTime();
        }, 3000);
    }

    private boolean isDeviceCharging() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = registerReceiver(null, ifilter);
        
        if (batteryStatus != null) {
            int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            return status == BatteryManager.BATTERY_STATUS_CHARGING || 
                   status == BatteryManager.BATTERY_STATUS_FULL;
        }
        return false;
    }

    private boolean isConnectedToWifi() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                NetworkCapabilities capabilities = cm.getNetworkCapabilities(cm.getActiveNetwork());
                return capabilities != null && 
                       capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            }
        }
        return false;
    }

    private long getIntervalHours(int index) {
        switch (index) {
            case 0: return 3;   // Every 3 hours
            case 1: return 6;   // Every 6 hours
            case 2: return 12;  // Every 12 hours
            case 3: return 24;  // Once daily
            case 4: return 12;  // Twice daily (will run every 12 hours)
            default: return 24;
        }
    }

    private void updateLastBackupTime() {
        long lastBackup = prefs.getLong("last_backup_time", 0);
        if (lastBackup == 0) {
            lastBackupTextView.setText(R.string.never_backed_up);
        } else {
            SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault());
            String timeStr = sdf.format(new Date(lastBackup));
            lastBackupTextView.setText(getString(R.string.last_backup, timeStr));
        }
    }

    public void saveLastBackupTime() {
        prefs.edit().putLong("last_backup_time", System.currentTimeMillis()).apply();
        updateLastBackupTime();
        logBackupEvent("Completed", "Successfully pushed to GitHub");
    }

    private void logBackupEvent(String status, String message) {
        try {
            String historyJson = prefs.getString("backup_history", "[]");
            org.json.JSONArray historyArray = new org.json.JSONArray(historyJson);
            
            org.json.JSONObject event = new org.json.JSONObject();
            event.put("timestamp", System.currentTimeMillis());
            event.put("status", status);
            event.put("message", message);
            
            // Add interval category
            int intervalIndex = prefs.getInt("interval_index", 3);
            String[] intervals = getResources().getStringArray(R.array.backup_intervals);
            event.put("category", intervals[intervalIndex]);
            
            // Keep only last 50 entries
            historyArray.put(event);
            if (historyArray.length() > 50) {
                historyArray.remove(0);
            }
            
            prefs.edit().putString("backup_history", historyArray.toString()).apply();
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to log backup event: " + e.getMessage());
        }
    }
}
