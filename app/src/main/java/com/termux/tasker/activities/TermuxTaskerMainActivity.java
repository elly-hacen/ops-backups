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
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.card.MaterialCardView;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class TermuxTaskerMainActivity extends AppCompatActivity {

    public static final String LOG_TAG = "TermuxTaskerMainActivity";
    private static final String PREFS_NAME = "BackupSettings";
    private static final String WORK_NAME = "AutoBackupWork";

    private TextView backupStatusTextView;
    private TextView lastBackupTextView;
    private View progressBackup;
    private MaterialSwitch scheduleSwitch;
    private AutoCompleteTextView intervalSpinner;
    private CheckBox wifiOnlyCheckbox;
    private CheckBox chargingOnlyCheckbox;
    private Button testScheduleButton;
    private SharedPreferences prefs;
    private BottomSheetBehavior<androidx.core.widget.NestedScrollView> bottomSheetBehavior;

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

        // Setup bottom sheet menu
        androidx.core.widget.NestedScrollView bottomSheet = findViewById(R.id.bottom_sheet);
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet);
        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
        bottomSheetBehavior.setHideable(true);
        bottomSheetBehavior.setSkipCollapsed(true);
        
        // Setup floating menu button
        View menuButton = findViewById(R.id.menu_icon);
        if (menuButton != null) {
            // Load and start continuous pulse animation
            final Animation pulseAnim = AnimationUtils.loadAnimation(this, R.anim.bounce);
            menuButton.post(() -> menuButton.startAnimation(pulseAnim));
            
            menuButton.setOnClickListener(v -> {
                Logger.logInfo(LOG_TAG, "Menu button clicked");
                if (bottomSheetBehavior.getState() == BottomSheetBehavior.STATE_HIDDEN) {
                    menuButton.clearAnimation();
                    bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                } else {
                    bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
                }
            });
        }
        
        // Setup scrim overlay
        View scrim = findViewById(R.id.scrim);
        if (scrim != null) {
            scrim.setOnClickListener(v -> {
                if (bottomSheetBehavior.getState() != BottomSheetBehavior.STATE_HIDDEN) {
                    bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
                }
            });
        }
        
        // Dim background when sheet is shown
        bottomSheetBehavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@androidx.annotation.NonNull View bottomSheet, int newState) {
                if (scrim != null) {
                    if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                        scrim.setVisibility(View.GONE);
                        // Restart animation when sheet is hidden
                        if (menuButton != null) {
                            Animation pulseAnim = AnimationUtils.loadAnimation(TermuxTaskerMainActivity.this, R.anim.bounce);
                            menuButton.startAnimation(pulseAnim);
                        }
                    } else {
                        scrim.setVisibility(View.VISIBLE);
                    }
                }
            }

            @Override
            public void onSlide(@androidx.annotation.NonNull View bottomSheet, float slideOffset) {
                if (scrim != null && slideOffset > 0) {
                    scrim.setAlpha(slideOffset);
                    scrim.setVisibility(View.VISIBLE);
                }
            }
        });

        // Setup bottom sheet menu items
        findViewById(R.id.menu_schedules).setOnClickListener(v -> {
            startActivity(new Intent(this, SchedulesActivity.class));
            bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
        });
        
        findViewById(R.id.menu_usage_guide).setOnClickListener(v -> {
            startActivity(new Intent(this, UsageGuideActivity.class));
            bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
        });
        
        findViewById(R.id.menu_scripts).setOnClickListener(v -> {
            startActivity(new Intent(this, ScriptsManagementActivity.class));
            bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
        });
        
        findViewById(R.id.menu_check_updates).setOnClickListener(v -> {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.update_url)));
            startActivity(browserIntent);
            bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
        });
        
        findViewById(R.id.menu_about).setOnClickListener(v -> {
            showAboutDialog();
            bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
        });

        backupStatusTextView = findViewById(R.id.textview_backup_status);
        lastBackupTextView = findViewById(R.id.textview_last_backup);
        progressBackup = findViewById(R.id.progress_backup);
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

        // Check if first run
        if (!prefs.getBoolean("setup_completed", false)) {
            String termuxError = getTermuxAccessibilityError();
            if (termuxError != null) {
                startActivity(new Intent(this, SetupWizardActivity.class));
            }
        }

        // Handle back button for bottom sheet
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (bottomSheetBehavior.getState() != BottomSheetBehavior.STATE_HIDDEN) {
                    bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
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
                .setCancelable(true)
                .create();
        
        // Blur background
        if (dialog.getWindow() != null) {
            dialog.getWindow().setDimAmount(0.75f);
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        
        // Setup button
        Button checkUpdatesButton = dialogView.findViewById(R.id.button_check_updates);
        
        if (checkUpdatesButton != null) {
            checkUpdatesButton.setOnClickListener(v -> {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.update_url)));
                startActivity(browserIntent);
                dialog.dismiss();
            });
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

        // Get enabled scripts
        List<String> enabledScripts = getEnabledScripts();
        if (enabledScripts.isEmpty()) {
            updateBackupStatus(getString(R.string.no_enabled_scripts));
            Toast.makeText(this, R.string.no_enabled_scripts, Toast.LENGTH_LONG).show();
            return;
        }

        updateBackupStatus(getString(R.string.executing_scripts, enabledScripts.size()));
        showProgress(true);

        // Execute all enabled scripts
        StringBuilder combinedScript = new StringBuilder();
        for (int i = 0; i < enabledScripts.size(); i++) {
            String scriptPath = enabledScripts.get(i);
            if (i > 0) combinedScript.append(" && ");
            combinedScript.append("if [ -f ").append(scriptPath).append(" ]; then bash ").append(scriptPath)
                    .append("; else echo 'Script not found: ").append(scriptPath).append("'; fi");
        }

        String error = startBackgroundCommand(combinedScript.toString());
        
        // Hide progress after 3 seconds
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            showProgress(false);
            if (error == null) {
                saveLastBackupTime();
                updateBackupStatus(getString(R.string.all_scripts_executed));
                showSuccessDialog(enabledScripts.size());
            } else {
                updateBackupStatus(error);
                Toast.makeText(this, error, Toast.LENGTH_LONG).show();
            }
        }, 3000);
    }

    private List<String> getEnabledScripts() {
        List<String> scripts = new ArrayList<>();
        try {
            String scriptsJson = prefs.getString("backup_scripts", "[]");
            org.json.JSONArray scriptsArray = new org.json.JSONArray(scriptsJson);
            
            for (int i = 0; i < scriptsArray.length(); i++) {
                org.json.JSONObject script = scriptsArray.getJSONObject(i);
                if (script.optBoolean("enabled", true)) {
                    scripts.add(script.getString("path"));
                }
            }
            
            // Fallback to default if no scripts configured
            if (scripts.isEmpty() && scriptsArray.length() == 0) {
                scripts.add(getString(R.string.default_script_path));
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to load scripts: " + e.getMessage());
            // Fallback to default script
            scripts.add(getString(R.string.default_script_path));
        }
        return scripts;
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

    private void showProgress(boolean show) {
        if (progressBackup != null) {
            progressBackup.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        Button backupButton = findViewById(R.id.button_run_backup);
        if (backupButton != null) {
            backupButton.setEnabled(!show);
            backupButton.setAlpha(show ? 0.6f : 1.0f);
        }
    }

    private void showSuccessDialog(int scriptCount) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_backup_success, null);
        
        TextView scriptsCountView = dialogView.findViewById(R.id.textview_scripts_count);
        TextView lastBackupView = dialogView.findViewById(R.id.textview_last_backup_time);
        
        if (scriptsCountView != null) {
            scriptsCountView.setText(String.format("%d script(s) executed successfully", scriptCount));
        }
        
        if (lastBackupView != null) {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MMM dd, yyyy HH:mm:ss", java.util.Locale.getDefault());
            lastBackupView.setText(sdf.format(new java.util.Date()));
        }
        
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(true)
                .create();
        
        if (dialog.getWindow() != null) {
            dialog.getWindow().setDimAmount(0.75f);
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        
        dialogView.findViewById(R.id.button_ok).setOnClickListener(v -> dialog.dismiss());
        
        dialog.show();
    }

    private void setupScheduleUI() {
        // Setup interval dropdown
        String[] intervals = getResources().getStringArray(R.array.backup_intervals);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                R.layout.dropdown_item, intervals);
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
            
            // Visual feedback with animation
            scheduleSwitch.animate()
                    .scaleX(1.1f)
                    .scaleY(1.1f)
                    .setDuration(150)
                    .withEndAction(() -> {
                        scheduleSwitch.animate()
                                .scaleX(1.0f)
                                .scaleY(1.0f)
                                .setDuration(150)
                                .start();
                    })
                    .start();
            
            if (isChecked) {
                scheduleBackupWork();
                Toast.makeText(this, "✓ Auto backup enabled", Toast.LENGTH_SHORT).show();
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
            
            // Remove oldest entries if we're at the limit
            while (historyArray.length() >= 50) {
                historyArray.remove(0);
            }
            
            org.json.JSONObject event = new org.json.JSONObject();
            event.put("timestamp", System.currentTimeMillis());
            event.put("status", status);
            event.put("message", message);
            
            // Add interval category
            int intervalIndex = prefs.getInt("interval_index", 3);
            String[] intervals = getResources().getStringArray(R.array.backup_intervals);
            event.put("category", intervals[intervalIndex]);
            
            // Add the new event
            historyArray.put(event);
            
            prefs.edit().putString("backup_history", historyArray.toString()).apply();
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to log backup event: " + e.getMessage());
        }
    }
}
