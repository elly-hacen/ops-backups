package com.termux.tasker.activities;

import android.os.Build;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.theme.TermuxThemeUtils;
import com.termux.shared.theme.NightMode;
import com.termux.tasker.R;
import com.termux.tasker.TermuxTaskerApplication;

public class UsageGuideActivity extends AppCompatActivity {

    private static final String LOG_TAG = "UsageGuideActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Enable dynamic colors - Android 12+/API 31+ is our minSdk
        try {
            getTheme().applyStyle(com.google.android.material.R.style.ThemeOverlay_Material3_DynamicColors_DayNight, true);
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Dynamic colors not available");
        }
        
        setContentView(R.layout.activity_usage_guide);

        TermuxThemeUtils.setAppNightMode(this);
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setDisplayHomeAsUpEnabled(true);
                getSupportActionBar().setHomeAsUpIndicator(R.drawable.ic_close);
                getSupportActionBar().setTitle(getString(R.string.menu_usage_guide));
            }
            toolbar.setNavigationOnClickListener(v -> finish());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        TermuxTaskerApplication.setLogConfig(this, false);
    }
}

