package com.termux.tasker.activities;

import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.OvershootInterpolator;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.card.MaterialCardView;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.theme.TermuxThemeUtils;
import com.termux.shared.theme.NightMode;
import com.termux.tasker.BackupWorker;
import com.termux.tasker.R;
import com.termux.tasker.TermuxTaskerApplication;
import com.termux.tasker.BuildConfig;
import com.termux.tasker.UpdateChecker;

import java.io.File;
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
    // Error log now stored in app-private external files dir (not world-readable)
    private static final String ERROR_LOG_FILENAME = "ops-error-log.json";
    private static final long MAX_LOG_SIZE = 5 * 1024 * 1024; // 5 MB
    
    // Mutex to prevent concurrent backup polling
    private static volatile boolean backupInProgress = false;

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
    private int versionClickCount = 0;
    private long lastVersionClickTime = 0;
    private Handler intervalUpdateHandler;
    private Runnable intervalUpdateRunnable;
    
    // Stats card views
    private TextView statLastBackup;
    private TextView statStatus;
    private View statStatusIndicator;
    private TextView statAutoStatus;
    private View statsContent;
    private View statsSkeleton;
    
    // Root view for Snackbar
    private View rootView;
    
    private MaterialCardView statsCard;
    private String cachedReleaseNotes;
    private String cachedReleaseNotesVersion;
    private String cachedReleaseNotesUrl;

    // Update checker
    private UpdateChecker updateChecker;
    private AlertDialog currentUpdateDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Enable dynamic colors (Material You) - Android 12+/API 31+ is our minSdk
            try {
                getTheme().applyStyle(com.google.android.material.R.style.ThemeOverlay_Material3_DynamicColors_DayNight, true);
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Dynamic colors not available: " + e.getMessage());
        }
        
        setContentView(R.layout.activity_termux_tasker_main);
        rootView = findViewById(android.R.id.content);
        statsCard = findViewById(R.id.card_stats);

        // Set NightMode.APP_NIGHT_MODE
        TermuxThemeUtils.setAppNightMode(this);
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);

        // Setup bottom sheet menu
        androidx.core.widget.NestedScrollView bottomSheet = findViewById(R.id.bottom_sheet);
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet);
        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
        bottomSheetBehavior.setHideable(true);
        bottomSheetBehavior.setSkipCollapsed(true);
        
        // Add padding for navigation bar (edge-to-edge)
        View bottomSheetContent = findViewById(R.id.bottom_sheet_content);
        if (bottomSheetContent != null) {
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(bottomSheetContent, (v, insets) -> {
                int navBarHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom;
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), 
                    (int) (48 * getResources().getDisplayMetrics().density) + navBarHeight);
                return insets;
            });
        }
        
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
            bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
            checkForUpdates();
        });

        findViewById(R.id.menu_whats_new).setOnClickListener(v -> {
            bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
            requestReleaseNotes(true, true);
        });
        
        findViewById(R.id.menu_privacy).setOnClickListener(v -> {
            showPrivacyDialog();
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
        
        // Stats card views
        statLastBackup = findViewById(R.id.stat_last_backup);
        statStatus = findViewById(R.id.stat_status);
        statStatusIndicator = findViewById(R.id.stat_status_indicator);
        statAutoStatus = findViewById(R.id.stat_auto_status);
        statsContent = findViewById(R.id.stats_content);
        statsSkeleton = findViewById(R.id.stats_skeleton);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        
        // Show skeleton loading initially
        showStatsLoading(true);

        findViewById(R.id.button_run_backup).setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            triggerBackupCommand();
        });
        testScheduleButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            testSchedulerNow();
        });

        setupScheduleUI();

        updateBackupStatus("");
        updateLastBackupTime();
        updateStatsCard();
        
        // Show background notification if auto-backup is enabled
        if (prefs.getBoolean("schedule_enabled", false)) {
            BackupWorker.showBackgroundStatusNotification(this);
        }

        // Easter egg - triple click version
        TextView versionView = findViewById(R.id.textview_version);
        if (versionView != null) {
            // Set version from BuildConfig
            versionView.setText("v" + com.termux.tasker.BuildConfig.VERSION_NAME);
            
            versionView.setOnClickListener(v -> {
                long currentTime = System.currentTimeMillis();
                
                // Reset counter if more than 2 seconds since last click
                if (currentTime - lastVersionClickTime > 2000) {
                    versionClickCount = 0;
                }
                
                versionClickCount++;
                lastVersionClickTime = currentTime;
                
                if (versionClickCount == 3) {
                    // Triple click detected - open website
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://owerflow.dev"));
                    startActivity(browserIntent);
                    versionClickCount = 0;
                }
            });
        }

        // Request necessary permissions
        requestPermissionsIfNeeded();

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

    private void requestPermissionsIfNeeded() {
        // Notification permission (Android 13+) - shows as popup
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 100);
                }
            }
            
        // Check for Files permission (MANAGE_EXTERNAL_STORAGE)
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!android.os.Environment.isExternalStorageManager()) {
                showFilesPermissionDialog();
            }
        }, 800);
        
        // Check Termux RUN_COMMAND permission
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (checkSelfPermission("com.termux.permission.RUN_COMMAND") != PackageManager.PERMISSION_GRANTED) {
                showTermuxPermissionDialog();
            }
        }, 2500);
    }

    private void showFilesPermissionDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_permission_files, null);
        
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(false)
                .create();
        
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setDimAmount(0.6f);
        }
        
        dialogView.findViewById(R.id.button_skip_files).setOnClickListener(v -> dialog.dismiss());
        
        dialogView.findViewById(R.id.button_grant_files).setOnClickListener(v -> {
            try {
                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivity(intent);
            }
            dialog.dismiss();
        });
        
        dialog.show();
    }

    private void showTermuxPermissionDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_permission_termux, null);
        
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(true)
                .create();
        
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setDimAmount(0.6f);
        }
        
        dialogView.findViewById(R.id.button_cancel_termux).setOnClickListener(v -> dialog.dismiss());
        
        dialogView.findViewById(R.id.button_open_settings_termux).setOnClickListener(v -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
            dialog.dismiss();
        });
        
        dialog.show();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Set log level for the app
        TermuxTaskerApplication.setLogConfig(this, false);

        Logger.logVerbose(LOG_TAG, "onResume");
        
        // Restart interval preview updater
        startIntervalPreviewUpdater();
    }

    @Override
    protected void onPause() {
        super.onPause();
        
        // Stop interval preview updater
        stopIntervalPreviewUpdater();
    }

    private void showPrivacyDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_privacy, null);
        
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(true)
                .create();
        
        if (dialog.getWindow() != null) {
            dialog.getWindow().setDimAmount(0.75f);
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        
        dialogView.findViewById(R.id.button_close_privacy).setOnClickListener(v -> dialog.dismiss());
        
        dialog.show();
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
        
        // Set dynamic version
        TextView versionView = dialogView.findViewById(R.id.textview_about_version);
        if (versionView != null) {
            versionView.setText("Version " + BuildConfig.VERSION_NAME);
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
        // Prevent concurrent backup triggers
        if (backupInProgress) {
            showSnackbar("Backup already in progress", Snackbar.LENGTH_SHORT);
            return;
        }
        
        String validationError = validateTermuxReady();
        if (validationError != null) {
            updateBackupStatus(validationError);
            showSnackbar(validationError, Snackbar.LENGTH_LONG);
            return;
        }

        // Check network connectivity
        if (!isNetworkAvailable()) {
            updateBackupStatus("No internet connection");
            showSnackbar("No internet connection available", Snackbar.LENGTH_LONG);
            return;
        }

        // Get enabled scripts
        List<String> enabledScripts = getEnabledScripts();
        if (enabledScripts.isEmpty()) {
            updateBackupStatus(getString(R.string.no_enabled_scripts));
            showSnackbar(getString(R.string.no_enabled_scripts), Snackbar.LENGTH_LONG);
            return;
        }

        backupInProgress = true;
        updateBackupStatus(getString(R.string.executing_scripts, enabledScripts.size()));
        showProgress(true);

        // Result file in shared storage (unique per session to avoid collisions)
        String resultFile = "/sdcard/ops-backup-result-" + System.currentTimeMillis() + ".txt";
        
        // Execute all enabled scripts and write result to shared storage
        StringBuilder combinedScript = new StringBuilder();
        combinedScript.append("rm -f ").append(resultFile).append(" && (");
        
        for (int i = 0; i < enabledScripts.size(); i++) {
            String scriptPath = enabledScripts.get(i);
            if (i > 0) combinedScript.append(" && ");
            combinedScript.append("bash ").append(scriptPath);
        }
        
        combinedScript.append(" && echo 'SUCCESS' > ").append(resultFile);
        combinedScript.append(") || echo 'FAILED:Script execution error' > ").append(resultFile);

        String error = startBackgroundCommand(combinedScript.toString());
        
        if (error != null) {
            showProgress(false);
            updateBackupStatus(error);
            showSnackbar(error, Snackbar.LENGTH_LONG);
            return;
        }
        
        // Poll for result file
        final int scriptCount = enabledScripts.size();
        pollBackupResultFile(0, scriptCount, resultFile);
    }

    private void pollBackupResultFile(int attemptCount, int scriptCount, String resultFilePath) {
        if (attemptCount > 30) {
            // Timeout after 15 seconds
            backupInProgress = false;
            showProgress(false);
            updateBackupStatus("Timeout - script is still running");
            
            // Clean up result file
            new java.io.File(resultFilePath).delete();
            
            // Show detailed timeout dialog
            String errorLogPath = getErrorLogFile().getAbsolutePath();
            new AlertDialog.Builder(this)
                .setTitle("Backup Timeout")
                .setMessage("Script is taking too long (>15s).\n\nPossible issues:\n• Git push waiting for authentication\n• Network is slow\n• Script is stuck\n\nCheck Termux terminal for details.\nError log: " + errorLogPath)
                .setPositiveButton("View Logs", (dialog, which) -> {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    android.content.ClipData clip = android.content.ClipData.newPlainText("View Logs", "cat \"" + errorLogPath + "\"");
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(clip);
                        showSnackbar("Command copied! Paste in Termux", Snackbar.LENGTH_LONG);
                    }
                })
                .setNegativeButton("Close", null)
                .show();
            
            logBackupEvent("Timeout", "Script execution exceeded 15 seconds - likely stuck on git push or network issue");
            return;
        }
        
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                java.io.File resultFile = new java.io.File(resultFilePath);
                if (resultFile.exists()) {
                    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(resultFile));
                    String result = reader.readLine();
                    reader.close();
                    
                    if (result != null) {
                        if (result.startsWith("SUCCESS")) {
                            backupInProgress = false;
                            showProgress(false);
                            prefs.edit().putLong("last_backup_time", System.currentTimeMillis()).apply();
                            updateLastBackupTime();
                            updateBackupStatus("Backup successful!");
                            logBackupEvent("Completed", "Successfully pushed to GitHub");
                            showSuccessDialog(scriptCount);
                            resultFile.delete();
                        } else if (result.startsWith("FAILED")) {
                            backupInProgress = false;
                            showProgress(false);
                            String errorMsg = result.contains(":") ? result.substring(result.indexOf(":") + 1) : "Unknown error";
                            updateBackupStatus("Backup failed: " + errorMsg);
                            
                            // Show detailed error dialog
                            String errLogPath = getErrorLogFile().getAbsolutePath();
                            new AlertDialog.Builder(this)
                                .setTitle("Backup Failed")
                                .setMessage("Error: " + errorMsg + "\n\nError log: " + errLogPath)
                                .setPositiveButton("View Logs", (dialog, which) -> {
                                    // Copy log path to clipboard
                                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                                    android.content.ClipData clip = android.content.ClipData.newPlainText("Error Log Path", "cat \"" + errLogPath + "\"");
                                    if (clipboard != null) {
                                        clipboard.setPrimaryClip(clip);
                                        showSnackbar("Command copied! Open Termux and paste", Snackbar.LENGTH_LONG);
                                    }
                                })
                                .setNegativeButton("Close", null)
                                .show();
                            
                            logBackupEvent("Failed", errorMsg);
                            resultFile.delete();
                        } else {
                            pollBackupResultFile(attemptCount + 1, scriptCount, resultFilePath);
                        }
                    } else {
                        pollBackupResultFile(attemptCount + 1, scriptCount, resultFilePath);
                    }
                } else {
                    // File doesn't exist yet, check again
                    pollBackupResultFile(attemptCount + 1, scriptCount, resultFilePath);
                }
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Failed to read result file: " + e.getMessage());
                pollBackupResultFile(attemptCount + 1, scriptCount, resultFilePath);
            }
        }, 500);
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm != null) {
                android.net.Network network = cm.getActiveNetwork();
                if (network == null) return false;
                NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
                return capabilities != null && 
                       (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || 
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        }
        return false;
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
            // Android 12+ always uses foreground service
                ContextCompat.startForegroundService(this, intent);
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
        
        // Update stats card status
        if (statStatus != null && statStatusIndicator != null) {
            if (message.isEmpty() || message.contains("Ready")) {
                statStatus.setText("Ready");
                statStatusIndicator.setBackgroundResource(R.drawable.status_indicator_idle);
            } else if (message.contains("success") || message.contains("Success")) {
                statStatus.setText("Success");
                statStatusIndicator.setBackgroundResource(R.drawable.status_indicator_active);
            } else if (message.contains("fail") || message.contains("error") || message.contains("Error")) {
                statStatus.setText("Failed");
                statStatusIndicator.setBackgroundResource(R.drawable.status_indicator_error);
            } else if (message.contains("Running") || message.contains("Pushing")) {
                statStatus.setText("Running");
                statStatusIndicator.setBackgroundResource(R.drawable.status_indicator_active);
            } else {
                statStatus.setText("Ready");
                statStatusIndicator.setBackgroundResource(R.drawable.status_indicator_idle);
            }
        }
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
        View successIcon = dialogView.findViewById(R.id.success_icon);
        
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
        
        dialogView.findViewById(R.id.button_ok).setOnClickListener(v -> {
            performHapticFeedbackLight(v);
            dialog.dismiss();
        });
        
        dialog.show();
        
        // Animate the success icon with overshoot effect
        if (successIcon != null) {
            Animation scaleAnim = AnimationUtils.loadAnimation(this, R.anim.success_scale);
            successIcon.startAnimation(scaleAnim);
            // Haptic feedback on success
            performHapticFeedback(successIcon);
        }

        playStatsCelebration();
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
                // Show persistent background notification
                BackupWorker.showBackgroundStatusNotification(this);
                performHapticFeedback(scheduleSwitch);
                showSnackbar("✓ Auto backup enabled");
            } else {
                cancelBackupWork();
                // Hide persistent background notification
                BackupWorker.hideBackgroundStatusNotification(this);
                performHapticFeedbackLight(scheduleSwitch);
                showSnackbar("Auto backup disabled");
            }
            updateStatsCard();
        });

        intervalSpinner.setOnItemClickListener((parent, view, position, id) -> {
            prefs.edit().putInt("interval_index", position).apply();
            if (scheduleSwitch.isChecked()) {
                scheduleBackupWork();
            }
            updateIntervalPreview(position);
        });
        
        // Show initial preview
        updateIntervalPreview(intervalIndex);
        
        // Start periodic interval preview updates
        startIntervalPreviewUpdater();

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
            showSnackbar("Enable auto backup first");
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
            performHapticFeedback(testScheduleButton);
            showSnackbar(getString(R.string.backup_scheduled_successfully));
            logBackupEvent("Scheduled", "Running now");
        } else {
            showSnackbar(getString(R.string.backup_already_scheduled) + " - " + message, Snackbar.LENGTH_LONG);
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
                NetworkCapabilities capabilities = cm.getNetworkCapabilities(cm.getActiveNetwork());
                return capabilities != null && 
                       capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
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

    private void updateIntervalPreview(int intervalIndex) {
        com.google.android.material.textfield.TextInputLayout intervalLayout = findViewById(R.id.layout_interval);
        if (intervalLayout != null) {
            long hours = getIntervalHours(intervalIndex);
            long lastBackup = prefs.getLong("last_backup_time", 0);
            
            if (lastBackup > 0) {
                long nextRun = lastBackup + (hours * 60 * 60 * 1000);
                long millisUntil = nextRun - System.currentTimeMillis();
                
                if (millisUntil > 0) {
                    long hoursUntil = millisUntil / (60 * 60 * 1000);
                    long minutesUntil = (millisUntil % (60 * 60 * 1000)) / (60 * 1000);
                    
                    if (hoursUntil > 0) {
                        intervalLayout.setHelperText("Next run in ~" + hoursUntil + "h " + minutesUntil + "m");
                    } else {
                        intervalLayout.setHelperText("Next run in ~" + minutesUntil + "m");
                    }
                } else {
                    intervalLayout.setHelperText("Due now");
                }
            } else {
                intervalLayout.setHelperText("Runs every " + hours + " hours");
            }
        }
    }

    private void startIntervalPreviewUpdater() {
        if (intervalUpdateHandler == null) {
            intervalUpdateHandler = new Handler(Looper.getMainLooper());
        }
        
        if (intervalUpdateRunnable == null) {
            intervalUpdateRunnable = new Runnable() {
                @Override
                public void run() {
                    int currentIndex = prefs.getInt("interval_index", 3);
                    updateIntervalPreview(currentIndex);
                    intervalUpdateHandler.postDelayed(this, 60000); // Update every minute
                }
            };
        }
        
        // Cancel any existing callbacks
        intervalUpdateHandler.removeCallbacks(intervalUpdateRunnable);
        
        // Start updating
        intervalUpdateHandler.post(intervalUpdateRunnable);
    }

    private void stopIntervalPreviewUpdater() {
        if (intervalUpdateHandler != null && intervalUpdateRunnable != null) {
            intervalUpdateHandler.removeCallbacks(intervalUpdateRunnable);
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
        updateStatsCard();
    }
    
    private UpdateChecker getUpdateChecker() {
        if (updateChecker == null) {
            updateChecker = new UpdateChecker(this);
        }
        return updateChecker;
    }

    private void updateStatsCard() {
        // Update last backup stat
        long lastBackup = prefs.getLong("last_backup_time", 0);
        if (statLastBackup != null) {
            if (lastBackup == 0) {
                statLastBackup.setText("Never");
            } else {
                statLastBackup.setText(getRelativeTimeString(lastBackup));
            }
        }
        
        // Update auto status
        if (statAutoStatus != null) {
            boolean enabled = prefs.getBoolean("schedule_enabled", false);
            statAutoStatus.setText(enabled ? "Active" : "Off");
        }
        
        // Hide skeleton after data is loaded
        showStatsLoading(false);
    }
    
    private void showStatsLoading(boolean loading) {
        if (statsContent == null || statsSkeleton == null) return;
        
        if (loading) {
            statsContent.setVisibility(View.INVISIBLE);
            statsSkeleton.setVisibility(View.VISIBLE);
            // Pulse animation for skeleton
            statsSkeleton.setAlpha(0.5f);
            statsSkeleton.animate()
                    .alpha(1f)
                    .setDuration(600)
                    .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            if (statsSkeleton.getVisibility() == View.VISIBLE) {
                                statsSkeleton.animate()
                                        .alpha(0.5f)
                                        .setDuration(600)
                                        .withEndAction(this)
                                        .start();
                            }
                        }
                    })
                    .start();
        } else {
            statsSkeleton.animate().cancel();
            statsSkeleton.setVisibility(View.GONE);
            statsContent.setVisibility(View.VISIBLE);
            // Fade in content
            statsContent.setAlpha(0f);
            statsContent.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .start();
        }
    }
    
    private String getRelativeTimeString(long timestamp) {
        long now = System.currentTimeMillis();
        long diff = now - timestamp;
        
        long seconds = diff / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        
        if (seconds < 60) {
            return "Just now";
        } else if (minutes < 60) {
            return minutes + "m ago";
        } else if (hours < 24) {
            return hours + "h ago";
        } else if (days < 7) {
            return days + "d ago";
        } else {
            SimpleDateFormat sdf = new SimpleDateFormat("MMM dd", Locale.getDefault());
            return sdf.format(new Date(timestamp));
        }
    }

    public void saveLastBackupTime() {
        prefs.edit().putLong("last_backup_time", System.currentTimeMillis()).apply();
        updateLastBackupTime();
        logBackupEvent("Completed", "Successfully pushed to GitHub");
        
        // Show success dialog
        List<String> enabledScripts = getEnabledScripts();
        showSuccessDialog(enabledScripts.size());
    }

    private void playStatsCelebration() {
        if (statsCard == null) return;
        statsCard.animate().cancel();
        statsCard.animate()
                .scaleX(1.04f)
                .scaleY(1.04f)
                .setDuration(220)
                .setInterpolator(new OvershootInterpolator())
                .withEndAction(() -> statsCard.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(180)
                        .start())
                .start();
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
            
            // Log errors to file
            if (status.equals("Failed") || status.equals("Timeout")) {
                logErrorToFile(status, message);
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to log backup event: " + e.getMessage());
        }
    }
    
    private java.io.File getErrorLogFile() {
        // Use app-private external files dir (not world-readable, survives app updates)
        java.io.File dir = getExternalFilesDir(null);
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }
        return new java.io.File(dir, ERROR_LOG_FILENAME);
    }
    
    private void logErrorToFile(String status, String message) {
        try {
            java.io.File logFile = getErrorLogFile();
            
            // Check file size and cleanup if needed
            if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
                cleanupErrorLog(logFile);
            }
            
            // Read existing logs
            org.json.JSONArray logArray;
            if (logFile.exists()) {
                String content = readFile(logFile);
                logArray = new org.json.JSONArray(content);
            } else {
                logArray = new org.json.JSONArray();
            }
            
            // Create error entry
            org.json.JSONObject errorEntry = new org.json.JSONObject();
            errorEntry.put("timestamp", System.currentTimeMillis());
            errorEntry.put("datetime", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date()));
            errorEntry.put("status", status);
            errorEntry.put("error", message);
            
            // Get enabled scripts
            List<String> enabledScripts = getEnabledScripts();
            errorEntry.put("scripts", new org.json.JSONArray(enabledScripts));
            
            // Add system info
            org.json.JSONObject systemInfo = new org.json.JSONObject();
            systemInfo.put("wifi", isConnectedToWifi());
            systemInfo.put("network", isNetworkAvailable());
            systemInfo.put("charging", isDeviceCharging());
            errorEntry.put("system", systemInfo);
            
            logArray.put(errorEntry);
            
            // Write to file
            writeFile(logFile, logArray.toString(2));
            
            Logger.logInfo(LOG_TAG, "Error logged to: " + logFile.getAbsolutePath());
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to write error log: " + e.getMessage());
        }
    }
    
    private void cleanupErrorLog(java.io.File logFile) {
        try {
            String content = readFile(logFile);
            org.json.JSONArray logArray = new org.json.JSONArray(content);
            
            // Keep only last 20 entries
            org.json.JSONArray newArray = new org.json.JSONArray();
            int start = Math.max(0, logArray.length() - 20);
            for (int i = start; i < logArray.length(); i++) {
                newArray.put(logArray.get(i));
            }
            
            writeFile(logFile, newArray.toString(2));
            Logger.logInfo(LOG_TAG, "Error log cleaned up. Kept last 20 entries.");
        } catch (Exception e) {
            // If cleanup fails, delete the file
            logFile.delete();
            Logger.logError(LOG_TAG, "Error log cleanup failed, file deleted: " + e.getMessage());
        }
    }
    
    private String readFile(java.io.File file) throws java.io.IOException {
        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file));
        StringBuilder content = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            content.append(line);
        }
        reader.close();
        return content.toString();
    }
    
    private void writeFile(java.io.File file, String content) throws java.io.IOException {
        java.io.FileWriter writer = new java.io.FileWriter(file);
        writer.write(content);
        writer.close();
    }
    
    // Helper method to show Snackbar with optional action
    private void showSnackbar(String message) {
        showSnackbar(message, Snackbar.LENGTH_SHORT, null, null);
    }
    
    private void showSnackbar(String message, int duration) {
        showSnackbar(message, duration, null, null);
    }
    
    private void showSnackbar(String message, int duration, String actionText, View.OnClickListener action) {
        if (rootView == null) return;
        Snackbar snackbar = Snackbar.make(rootView, message, duration);
        if (actionText != null && action != null) {
            snackbar.setAction(actionText, action);
        }
        snackbar.show();
    }
    
    // Helper method for haptic feedback
    private void performHapticFeedback(View view) {
        if (view != null) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        }
    }
    
    private void performHapticFeedbackLight(View view) {
        if (view != null) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
    }
    
    // ==================== UPDATE FUNCTIONALITY ====================
    
    private void checkForUpdates() {
        View checkingView = getLayoutInflater().inflate(R.layout.dialog_checking_update, null);
        AlertDialog checkingDialog = new AlertDialog.Builder(this)
                .setView(checkingView)
                .setCancelable(false)
                .create();

        if (checkingDialog.getWindow() != null) {
            checkingDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        checkingDialog.show();

        getUpdateChecker().checkForUpdates(new UpdateChecker.UpdateCheckListener() {
            @Override
            public void onUpdateAvailable(String latestVersion, String currentVersion, String releaseNotes, String downloadUrl, long fileSize, String releaseUrl) {
                checkingDialog.dismiss();
                cachedReleaseNotes = releaseNotes;
                cachedReleaseNotesVersion = latestVersion;
                cachedReleaseNotesUrl = releaseUrl;
                showUpdateAvailableDialog(latestVersion, currentVersion, releaseNotes, downloadUrl, fileSize, releaseUrl);
            }

            @Override
            public void onNoUpdateAvailable(String currentVersion) {
                checkingDialog.dismiss();
                showSnackbar(getString(R.string.update_no_update_snackbar, "v" + currentVersion), Snackbar.LENGTH_LONG);
            }

            @Override
            public void onError(String error) {
                checkingDialog.dismiss();
                showSnackbar("Update check failed: " + error, Snackbar.LENGTH_LONG, getString(R.string.action_retry), v -> checkForUpdates());
            }
        });
    }
    
    private void showUpdateAvailableDialog(String latestVersion, String currentVersion, String releaseNotes, String downloadUrl, long fileSize, String releaseUrl) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_update_available, null);
        
        TextView currentVersionView = dialogView.findViewById(R.id.textview_current_version);
        TextView latestVersionView = dialogView.findViewById(R.id.textview_latest_version);
        TextView downloadSizeView = dialogView.findViewById(R.id.textview_download_size);
        TextView releaseNotesView = dialogView.findViewById(R.id.textview_release_notes);
        View releaseLink = dialogView.findViewById(R.id.button_release_link);
        
        if (currentVersionView != null) currentVersionView.setText("v" + currentVersion);
        if (latestVersionView != null) latestVersionView.setText("v" + latestVersion);
        if (downloadSizeView != null) downloadSizeView.setText(getString(R.string.download_size_format, UpdateChecker.formatFileSize(fileSize)));
        if (releaseNotesView != null) releaseNotesView.setText(formatReleaseNotes(releaseNotes));
        if (releaseLink != null) {
            releaseLink.setVisibility(TextUtils.isEmpty(releaseUrl) ? View.GONE : View.VISIBLE);
            releaseLink.setOnClickListener(v -> {
                performHapticFeedbackLight(v);
                if (!TextUtils.isEmpty(releaseUrl)) {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl)));
                }
            });
        }
        
        currentUpdateDialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(true)
                .create();
        
        if (currentUpdateDialog.getWindow() != null) {
            currentUpdateDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        
        dialogView.findViewById(R.id.button_cancel).setOnClickListener(v -> {
            performHapticFeedbackLight(v);
            currentUpdateDialog.dismiss();
        });
        
        dialogView.findViewById(R.id.button_download).setOnClickListener(v -> {
            performHapticFeedback(v);
            currentUpdateDialog.dismiss();
            startDownload(downloadUrl, fileSize);
        });
        
        currentUpdateDialog.show();
    }
    
    private void startDownload(String downloadUrl, long totalSize) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_download_progress, null);
        
        LinearProgressIndicator progressBar = dialogView.findViewById(R.id.progress_download);
        TextView progressPercent = dialogView.findViewById(R.id.textview_progress_percent);
        TextView progressSize = dialogView.findViewById(R.id.textview_progress_size);
        
        currentUpdateDialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(false)
                .create();
        
        if (currentUpdateDialog.getWindow() != null) {
            currentUpdateDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        
        dialogView.findViewById(R.id.button_cancel_download).setOnClickListener(v -> {
            performHapticFeedbackLight(v);
            if (updateChecker != null) {
                updateChecker.cancelDownload();
            }
            currentUpdateDialog.dismiss();
            showSnackbar("Download cancelled");
        });
        
        currentUpdateDialog.show();
        
        getUpdateChecker().downloadUpdate(downloadUrl, 
            // Progress listener
            (progress, downloadedBytes, totalBytes) -> {
                if (progressBar != null) progressBar.setProgress(progress);
                if (progressPercent != null) progressPercent.setText(progress + "%");
                if (progressSize != null) {
                    progressSize.setText(UpdateChecker.formatFileSize(downloadedBytes) + " / " + UpdateChecker.formatFileSize(totalBytes));
                }
            },
            // Complete listener
            new UpdateChecker.DownloadCompleteListener() {
                @Override
                public void onDownloadComplete(File apkFile) {
                    currentUpdateDialog.dismiss();
                    showInstallDialog(apkFile);
                }
                
                @Override
                public void onDownloadFailed(String error) {
                    currentUpdateDialog.dismiss();
                    showSnackbar("Download failed: " + error, Snackbar.LENGTH_LONG);
                }
            }
        );
    }

    private void maybeShowWhatsNewOnLaunch() {
        SharedPreferences prefs = getReleaseNotesPrefs();
        String lastSeen = prefs.getString("last_seen_version", "");
        String current = BuildConfig.VERSION_NAME;
        if (TextUtils.equals(lastSeen, current)) return;
        requestReleaseNotes(false, false);
    }

    private void requestReleaseNotes(boolean allowDifferentVersion, boolean userInitiated) {
        showSnackbar(getString(R.string.release_notes_loading), Snackbar.LENGTH_SHORT);
        getUpdateChecker().fetchLatestReleaseNotes(new UpdateChecker.ReleaseNotesListener() {
            @Override
            public void onSuccess(String version, String releaseNotes, String releaseUrl) {
                if (!allowDifferentVersion && !TextUtils.equals(version, BuildConfig.VERSION_NAME)) {
                    if (userInitiated) {
                        showSnackbar(getString(R.string.release_notes_unavailable), Snackbar.LENGTH_LONG);
                    }
                    return;
                }
                cachedReleaseNotes = releaseNotes;
                cachedReleaseNotesVersion = version;
                cachedReleaseNotesUrl = releaseUrl;
                showReleaseNotesSheet(version, releaseNotes, releaseUrl);
                if (!allowDifferentVersion) {
                    markReleaseNotesSeen();
                }
            }

            @Override
            public void onError(String error) {
                if (userInitiated) {
                    showSnackbar(getString(R.string.release_notes_error), Snackbar.LENGTH_LONG);
                } else {
                    Logger.logError(LOG_TAG, "Release notes fetch failed: " + error);
                }
            }
        });
    }

    private void showReleaseNotesSheet(String version, String releaseNotes, String releaseUrl) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View view = getLayoutInflater().inflate(R.layout.bottom_sheet_release_notes, null);
        ((TextView) view.findViewById(R.id.text_release_version)).setText(getString(R.string.release_notes_version_label, version));
        ((TextView) view.findViewById(R.id.text_release_notes)).setText(formatReleaseNotes(releaseNotes));
        view.findViewById(R.id.button_release_close).setOnClickListener(v -> dialog.dismiss());
        View moreButton = view.findViewById(R.id.button_release_more);
        if (TextUtils.isEmpty(releaseUrl)) {
            moreButton.setVisibility(View.GONE);
        } else {
            moreButton.setOnClickListener(v -> {
                performHapticFeedbackLight(v);
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl)));
                dialog.dismiss();
            });
        }
        dialog.setContentView(view);
        dialog.show();
    }

    private CharSequence formatReleaseNotes(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return getString(R.string.release_notes_empty);
        }
        String normalized = raw.replace("\r", "");
        StringBuilder builder = new StringBuilder();
        for (String line : normalized.split("\n")) {
            String trimmed = line.trim();
            if (TextUtils.isEmpty(trimmed)) {
                continue;
            }
            if (trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.startsWith("- ")) {
                builder.append("• ").append(trimmed.substring(2).trim());
            } else {
                builder.append(trimmed);
            }
            builder.append("\n");
        }
        return builder.toString().trim();
    }

    private void markReleaseNotesSeen() {
        getReleaseNotesPrefs().edit().putString("last_seen_version", BuildConfig.VERSION_NAME).apply();
    }

    private SharedPreferences getReleaseNotesPrefs() {
        return getSharedPreferences("ReleaseNotes", MODE_PRIVATE);
    }
    
    private void showInstallDialog(File apkFile) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_install_update, null);
        
        currentUpdateDialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(true)
                .create();
        
        if (currentUpdateDialog.getWindow() != null) {
            currentUpdateDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        
        // Animate success icon
        View successIcon = dialogView.findViewById(R.id.success_icon);
        if (successIcon != null) {
            Animation scaleAnim = AnimationUtils.loadAnimation(this, R.anim.success_scale);
            successIcon.startAnimation(scaleAnim);
            performHapticFeedback(successIcon);
        }
        View releaseNotesButton = dialogView.findViewById(R.id.button_view_release_notes);
        if (releaseNotesButton != null) {
            if (!TextUtils.isEmpty(cachedReleaseNotes)) {
                releaseNotesButton.setVisibility(View.VISIBLE);
                releaseNotesButton.setOnClickListener(v -> {
                    performHapticFeedbackLight(v);
                    showReleaseNotesSheet(
                            TextUtils.isEmpty(cachedReleaseNotesVersion) ? BuildConfig.VERSION_NAME : cachedReleaseNotesVersion,
                            cachedReleaseNotes,
                            cachedReleaseNotesUrl);
                });
            } else {
                releaseNotesButton.setVisibility(View.GONE);
            }
        }
        
        dialogView.findViewById(R.id.button_cancel_install).setOnClickListener(v -> {
            performHapticFeedbackLight(v);
            currentUpdateDialog.dismiss();
            showSnackbar("Update saved. Install when ready.");
        });
        
        dialogView.findViewById(R.id.button_install).setOnClickListener(v -> {
            performHapticFeedback(v);
            currentUpdateDialog.dismiss();
            if (updateChecker != null) {
                updateChecker.installApk(apkFile);
            }
        });
        
        currentUpdateDialog.show();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        // Clean up handler
        stopIntervalPreviewUpdater();
        
        // Clean up update checker
        if (updateChecker != null) {
            updateChecker.cleanup();
        }
    }
}
