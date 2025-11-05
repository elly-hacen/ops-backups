package com.termux.tasker.activities;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;

import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.theme.TermuxThemeUtils;
import com.termux.shared.theme.NightMode;
import com.termux.tasker.R;
import com.termux.tasker.TermuxTaskerApplication;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SchedulesActivity extends AppCompatActivity {

    private static final String LOG_TAG = "SchedulesActivity";
    private RecyclerView recyclerView;
    private TextView emptyStateView;
    private TextView totalBackupsView;
    private TextView successCountView;
    private TextView failedCountView;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Enable dynamic colors - Android 12+/API 31+ is our minSdk
        try {
            getTheme().applyStyle(com.google.android.material.R.style.ThemeOverlay_Material3_DynamicColors_DayNight, true);
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Dynamic colors not available: " + e.getMessage());
        }
        
        setContentView(R.layout.activity_schedules);

        TermuxThemeUtils.setAppNightMode(this);
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setDisplayHomeAsUpEnabled(true);
                getSupportActionBar().setHomeAsUpIndicator(R.drawable.ic_close);
                getSupportActionBar().setTitle(getString(R.string.menu_schedules));
            }
            toolbar.setNavigationOnClickListener(v -> finish());
        }

        recyclerView = findViewById(R.id.recycler_schedules);
        emptyStateView = findViewById(R.id.textview_empty_state);
        totalBackupsView = findViewById(R.id.textview_total_backups);
        successCountView = findViewById(R.id.textview_success_count);
        failedCountView = findViewById(R.id.textview_failed_count);
        prefs = getSharedPreferences("BackupSettings", MODE_PRIVATE);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        
        findViewById(R.id.button_clear_all).setOnClickListener(v -> confirmClearAll());
        
        loadScheduleHistory();
    }

    private void confirmClearAll() {
        try {
            String historyJson = prefs.getString("backup_history", "[]");
            JSONArray historyArray = new JSONArray(historyJson);
            int count = historyArray.length();
            
            if (count == 0) {
                Toast.makeText(this, R.string.no_backup_history, Toast.LENGTH_SHORT).show();
                return;
            }
            
            new AlertDialog.Builder(this)
                    .setTitle(R.string.confirm_clear_all)
                    .setMessage(getString(R.string.confirm_clear_all_message, count))
                    .setPositiveButton(R.string.action_clear_all, (dialog, which) -> {
                        prefs.edit().putString("backup_history", "[]").apply();
                        loadScheduleHistory();
                        Toast.makeText(this, R.string.all_schedules_cleared, Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to clear history: " + e.getMessage());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        TermuxTaskerApplication.setLogConfig(this, false);
        Logger.logVerbose(LOG_TAG, "onResume");
    }

    private void loadScheduleHistory() {
        String historyJson = prefs.getString("backup_history", "[]");
        
        // Show loading state
        if (totalBackupsView != null) totalBackupsView.setText("...");
        if (successCountView != null) successCountView.setText("...");
        if (failedCountView != null) failedCountView.setText("...");
        
        try {
            JSONArray historyArray = new JSONArray(historyJson);
            
            // Calculate stats
            int totalBackups = historyArray.length();
            int successCount = 0;
            int failedCount = 0;
            
            for (int i = 0; i < historyArray.length(); i++) {
                JSONObject item = historyArray.getJSONObject(i);
                String status = item.optString("status", "");
                if (status.equals("Completed")) {
                    successCount++;
                } else if (!status.equals("Scheduled") && !status.equals("Queued")) {
                    failedCount++;
                }
            }
            
            // Update stats views with animation
            final int finalTotal = totalBackups;
            final int finalSuccess = successCount;
            final int finalFailed = failedCount;
            
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (totalBackupsView != null) {
                    totalBackupsView.setText(String.valueOf(finalTotal));
                }
                if (successCountView != null) {
                    successCountView.setText(String.valueOf(finalSuccess));
                }
                if (failedCountView != null) {
                    failedCountView.setText(String.valueOf(finalFailed));
                }
            }, 250);
            
            if (historyArray.length() == 0) {
                emptyStateView.setVisibility(View.VISIBLE);
                recyclerView.setVisibility(View.GONE);
            } else {
                emptyStateView.setVisibility(View.GONE);
                recyclerView.setVisibility(View.VISIBLE);
                
                List<BackupHistoryItem> items = new ArrayList<>();
                for (int i = historyArray.length() - 1; i >= 0; i--) {
                    JSONObject item = historyArray.getJSONObject(i);
                    items.add(new BackupHistoryItem(
                        i,
                        item.getLong("timestamp"),
                        item.getString("status"),
                        item.optString("message", ""),
                        item.optString("category", "Manual")
                    ));
                }
                
                recyclerView.setAdapter(new ScheduleAdapter(items));
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to load history: " + e.getMessage());
            emptyStateView.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        }
    }

    private void deleteSchedule(int position) {
        try {
            String historyJson = prefs.getString("backup_history", "[]");
            JSONArray historyArray = new JSONArray(historyJson);
            
            int actualIndex = historyArray.length() - 1 - position;
            if (actualIndex >= 0 && actualIndex < historyArray.length()) {
                // Save deleted item for undo
                JSONObject deletedItem = historyArray.getJSONObject(actualIndex);
                final String deletedItemJson = deletedItem.toString();
                final int deletedPosition = actualIndex;
                
                // Remove item
                historyArray.remove(actualIndex);
                final String newHistoryJson = historyArray.toString();
                prefs.edit().putString("backup_history", newHistoryJson).apply();
                loadScheduleHistory();
                
                // Show snackbar with undo
                View rootView = findViewById(android.R.id.content);
                if (rootView != null) {
                    Snackbar.make(rootView, R.string.schedule_deleted, Snackbar.LENGTH_LONG)
                            .setAction("Undo", v -> {
                                try {
                                    String currentJson = prefs.getString("backup_history", "[]");
                                    JSONArray currentArray = new JSONArray(currentJson);
                                    JSONObject restoredItem = new JSONObject(deletedItemJson);
                                    
                                    // Insert at original position
                                    JSONArray newArray = new JSONArray();
                                    for (int i = 0; i < currentArray.length(); i++) {
                                        if (i == deletedPosition) {
                                            newArray.put(restoredItem);
                                        }
                                        newArray.put(currentArray.get(i));
                                    }
                                    if (deletedPosition >= currentArray.length()) {
                                        newArray.put(restoredItem);
                                    }
                                    
                                    prefs.edit().putString("backup_history", newArray.toString()).apply();
                                    loadScheduleHistory();
                                } catch (Exception ex) {
                                    Logger.logError(LOG_TAG, "Failed to undo: " + ex.getMessage());
                                }
                            })
                            .show();
                }
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to delete schedule: " + e.getMessage());
        }
    }

    private static class BackupHistoryItem {
        int position;
        long timestamp;
        String status;
        String message;
        String category;

        BackupHistoryItem(int position, long timestamp, String status, String message, String category) {
            this.position = position;
            this.timestamp = timestamp;
            this.status = status;
            this.message = message;
            this.category = category;
        }
    }

    private class ScheduleAdapter extends RecyclerView.Adapter<ScheduleViewHolder> {
        private List<BackupHistoryItem> items;

        ScheduleAdapter(List<BackupHistoryItem> items) {
            this.items = items;
        }

        @Override
        public ScheduleViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            View view = getLayoutInflater().inflate(R.layout.schedule_item, parent, false);
            return new ScheduleViewHolder(view);
        }

        @Override
        public void onBindViewHolder(ScheduleViewHolder holder, int position) {
            BackupHistoryItem item = items.get(position);
            SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault());
            holder.timeView.setText(sdf.format(new Date(item.timestamp)));
            holder.categoryView.setText(item.category.toUpperCase());
            holder.statusView.setText(item.status + (item.message.isEmpty() ? "" : " - " + item.message));
            
            holder.deleteButton.setOnClickListener(v -> {
                deleteSchedule(position);
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private static class ScheduleViewHolder extends RecyclerView.ViewHolder {
        TextView timeView;
        TextView categoryView;
        TextView statusView;
        Button deleteButton;

        ScheduleViewHolder(View itemView) {
            super(itemView);
            timeView = itemView.findViewById(R.id.textview_time);
            categoryView = itemView.findViewById(R.id.textview_category);
            statusView = itemView.findViewById(R.id.textview_status);
            deleteButton = itemView.findViewById(R.id.button_delete);
        }
    }
}

