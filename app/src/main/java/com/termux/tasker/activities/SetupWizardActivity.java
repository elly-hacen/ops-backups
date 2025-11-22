package com.termux.tasker.activities;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.theme.TermuxThemeUtils;
import com.termux.shared.theme.NightMode;
import com.termux.tasker.R;
import com.termux.tasker.TermuxTaskerApplication;

public class SetupWizardActivity extends AppCompatActivity {

    private static final String LOG_TAG = "SetupWizardActivity";
    private static final String PREFS_NAME = "BackupSettings";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Enable dynamic colors - Android 12+/API 31+ is our minSdk
            try {
                getTheme().applyStyle(com.google.android.material.R.style.ThemeOverlay_Material3_DynamicColors_DayNight, true);
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Dynamic colors not available");
        }
        
        setContentView(R.layout.activity_setup_wizard);

        TermuxThemeUtils.setAppNightMode(this);
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);

        Button installTermuxButton = findViewById(R.id.button_install_termux);
        Button grantPermissionButton = findViewById(R.id.button_grant_permission);
        Button openUsageGuideButton = findViewById(R.id.button_open_usage_guide);
        Button skipButton = findViewById(R.id.button_skip);

        // Check Termux installation
        boolean termuxInstalled = isTermuxInstalled();
        installTermuxButton.setEnabled(!termuxInstalled);
        
        if (termuxInstalled) {
            installTermuxButton.setText("Termux Installed ✓");
            installTermuxButton.setAlpha(0.6f);
        }

        installTermuxButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("https://f-droid.org/packages/com.termux/"));
            startActivity(intent);
        });

        grantPermissionButton.setOnClickListener(v -> {
            // Request Termux RUN_COMMAND permission
                requestPermissions(new String[]{"com.termux.permission.RUN_COMMAND"}, 100);
        });

        openUsageGuideButton.setOnClickListener(v -> {
            startActivity(new Intent(this, UsageGuideActivity.class));
        });

        skipButton.setOnClickListener(v -> {
            markSetupComplete();
            finish();
        });
    }

    private boolean isTermuxInstalled() {
        try {
            getPackageManager().getApplicationInfo(TermuxConstants.TERMUX_PACKAGE_NAME, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void markSetupComplete() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putBoolean("setup_completed", true).apply();
    }

    @Override
    protected void onResume() {
        super.onResume();
        TermuxTaskerApplication.setLogConfig(this, false);
    }
}

