package com.termux.tasker.activities;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;
import com.termux.shared.logger.Logger;
import com.termux.tasker.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public class SchedulesActivity extends AppCompatActivity {

    private static final String LOG_TAG = "SchedulesActivity";
    private static final int MAX_HISTORY_TO_DISPLAY = 50;
    private static final int MAX_HISTORY_TO_STORE = 200;

    private RecyclerView recyclerView;
    private View emptyStateView;
    private TextView totalBackupsView;
    private TextView successCountView;
    private TextView failedCountView;
    private View loadingIndicator;
    private SharedPreferences prefs;
    private final ExecutorService historyExecutor = Executors.newSingleThreadExecutor();
    private final List<BackupHistoryItem> historyItems = new ArrayList<>();
    private ScheduleAdapter adapter;
    private Handler mainHandler;
    private volatile Future<?> currentHistoryTask;
    private final AtomicInteger loadGeneration = new AtomicInteger(0);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_schedules);
        mainHandler = new Handler(Looper.getMainLooper());
        loadingIndicator = findViewById(R.id.progress_history);

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
        adapter = new ScheduleAdapter(historyItems);
        recyclerView.setAdapter(adapter);
        
        findViewById(R.id.button_clear_all).setOnClickListener(v -> confirmClearAll());
        
        loadScheduleHistory();
    }

    private void confirmClearAll() {
        try {
            String historyJson = prefs.getString("backup_history", "[]");
            JSONArray historyArray = new JSONArray(historyJson);
            int count = historyArray.length();
            
            if (count == 0) {
                View rootView = findViewById(android.R.id.content);
                if (rootView != null) {
                    Snackbar.make(rootView, R.string.no_backup_history, Snackbar.LENGTH_SHORT).show();
                }
                return;
            }
            
            new AlertDialog.Builder(this)
                    .setTitle(R.string.confirm_clear_all)
                    .setMessage(getString(R.string.confirm_clear_all_message, count))
                    .setPositiveButton(R.string.action_clear_all, (dialog, which) -> {
                        showLoading(true);
                        historyExecutor.execute(() -> {
                            prefs.edit().putString("backup_history", "[]").apply();
                            mainHandler.post(() -> {
                                View rootView = findViewById(android.R.id.content);
                                if (rootView != null) {
                                    Snackbar.make(rootView, R.string.all_schedules_cleared, Snackbar.LENGTH_SHORT).show();
                                }
                                loadScheduleHistory();
                            });
                        });
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
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelPendingLoad();
        historyExecutor.shutdownNow();
    }

    private void loadScheduleHistory() {
        showLoading(true);
        cancelPendingLoad();
        final int requestId = loadGeneration.incrementAndGet();

        currentHistoryTask = historyExecutor.submit(() -> {
            try {
                String historyJson = prefs.getString("backup_history", "[]");
                JSONArray historyArray = new JSONArray(historyJson);
                JSONArray normalizedArray = normalizeHistoryArray(historyArray);
                int[] stats = calculateStats(normalizedArray);
                List<BackupHistoryItem> items = buildHistoryItems(normalizedArray);

                mainHandler.post(() -> {
                    if (requestId != loadGeneration.get() || isFinishing() || isDestroyed()) {
                        return;
                    }
                    currentHistoryTask = null;
                    updateHistoryUi(stats[0], stats[1], stats[2], items);
                });
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Failed to load history: " + e.getMessage());
                mainHandler.post(() -> {
                    if (requestId != loadGeneration.get() || isFinishing() || isDestroyed()) {
                        return;
                    }
                    currentHistoryTask = null;
                    showLoading(false);
                    showEmptyState();
                    if (totalBackupsView != null) totalBackupsView.setText("0");
                    if (successCountView != null) successCountView.setText("0");
                    if (failedCountView != null) failedCountView.setText("0");
                });
            }
        });
    }

    private void deleteSchedule(int position) {
        showLoading(true);
        historyExecutor.execute(() -> {
            try {
                String historyJson = prefs.getString("backup_history", "[]");
                JSONArray historyArray = new JSONArray(historyJson);

                int actualIndex = historyArray.length() - 1 - position;
                if (actualIndex < 0 || actualIndex >= historyArray.length()) {
                    mainHandler.post(() -> showLoading(false));
                    return;
                }

                JSONObject deletedItem = historyArray.getJSONObject(actualIndex);
                final String deletedItemJson = deletedItem.toString();
                final int deletedPosition = actualIndex;

                historyArray.remove(actualIndex);
                prefs.edit().putString("backup_history", historyArray.toString()).apply();

                mainHandler.post(() -> {
                    loadScheduleHistory();
                    showUndoSnackbar(deletedItemJson, deletedPosition);
                });
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Failed to delete schedule: " + e.getMessage());
                mainHandler.post(() -> showLoading(false));
            }
        });
    }

    private void cancelPendingLoad() {
        if (currentHistoryTask != null && !currentHistoryTask.isDone()) {
            currentHistoryTask.cancel(true);
        }
        currentHistoryTask = null;
    }

    private JSONArray normalizeHistoryArray(JSONArray historyArray) throws Exception {
        if (historyArray.length() <= MAX_HISTORY_TO_STORE) {
            return historyArray;
        }
        JSONArray trimmedArray = new JSONArray();
        int start = Math.max(0, historyArray.length() - MAX_HISTORY_TO_STORE);
        for (int i = start; i < historyArray.length(); i++) {
            trimmedArray.put(historyArray.get(i));
        }
        prefs.edit().putString("backup_history", trimmedArray.toString()).apply();
        return trimmedArray;
    }

    private int[] calculateStats(JSONArray historyArray) throws Exception {
        int total = historyArray.length();
        int success = 0;
        int failed = 0;
        for (int i = 0; i < historyArray.length(); i++) {
            JSONObject item = historyArray.getJSONObject(i);
            String status = item.optString("status", "");
            if ("Completed".equals(status)) {
                success++;
            } else if (!"Scheduled".equals(status) && !"Queued".equals(status)) {
                failed++;
            }
        }
        return new int[]{total, success, failed};
    }

    private List<BackupHistoryItem> buildHistoryItems(JSONArray historyArray) throws Exception {
        List<BackupHistoryItem> items = new ArrayList<>();
        if (historyArray.length() == 0) {
            return items;
        }
        int limit = Math.min(MAX_HISTORY_TO_DISPLAY, historyArray.length());
        for (int i = historyArray.length() - 1; i >= historyArray.length() - limit; i--) {
            JSONObject item = historyArray.getJSONObject(i);
            items.add(new BackupHistoryItem(
                    i,
                    item.optLong("timestamp", 0L),
                    item.optString("status", ""),
                    item.optString("message", ""),
                    item.optString("category", "Manual")
            ));
        }
        return items;
    }

    private void updateHistoryUi(int total, int success, int failed, List<BackupHistoryItem> items) {
        if (totalBackupsView != null) totalBackupsView.setText(String.valueOf(total));
        if (successCountView != null) successCountView.setText(String.valueOf(success));
        if (failedCountView != null) failedCountView.setText(String.valueOf(failed));
        showLoading(false);

        if (items.isEmpty()) {
            showEmptyState();
        } else {
            emptyStateView.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            historyItems.clear();
            historyItems.addAll(items);
            adapter.notifyDataSetChanged();
        }
    }

    private void showEmptyState() {
        emptyStateView.setVisibility(View.VISIBLE);
        recyclerView.setVisibility(View.GONE);
        historyItems.clear();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void showLoading(boolean show) {
        if (loadingIndicator != null) {
            loadingIndicator.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private void showUndoSnackbar(String deletedItemJson, int deletedPosition) {
        View rootView = findViewById(android.R.id.content);
        if (rootView == null) return;
        Snackbar.make(rootView, R.string.schedule_deleted, Snackbar.LENGTH_LONG)
                .setAction(R.string.action_undo, v -> restoreSchedule(deletedItemJson, deletedPosition))
                .show();
    }

    private void restoreSchedule(String deletedItemJson, int deletedPosition) {
        showLoading(true);
        historyExecutor.execute(() -> {
            try {
                String currentJson = prefs.getString("backup_history", "[]");
                JSONArray currentArray = new JSONArray(currentJson);
                JSONObject restoredItem = new JSONObject(deletedItemJson);

                JSONArray newArray = new JSONArray();
                boolean inserted = false;
                for (int i = 0; i < currentArray.length(); i++) {
                    if (i == deletedPosition) {
                        newArray.put(restoredItem);
                        inserted = true;
                    }
                    newArray.put(currentArray.get(i));
                }
                if (!inserted) {
                    newArray.put(restoredItem);
                }

                prefs.edit().putString("backup_history", newArray.toString()).apply();
                mainHandler.post(this::loadScheduleHistory);
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Failed to undo: " + e.getMessage());
                mainHandler.post(() -> showLoading(false));
            }
        });
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
        private final List<BackupHistoryItem> items;
        private final SimpleDateFormat dateFormat = new SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault());

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
            // Handle invalid/missing timestamp
            if (item.timestamp > 0) {
                holder.timeView.setText(dateFormat.format(new Date(item.timestamp)));
            } else {
                holder.timeView.setText("Unknown time");
            }
            holder.categoryView.setText(item.category.toUpperCase(Locale.getDefault()));
            holder.statusView.setText(item.status + (item.message.isEmpty() ? "" : " - " + item.message));
            
            holder.deleteButton.setOnClickListener(v -> {
                int adapterPosition = holder.getAdapterPosition();
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    deleteSchedule(adapterPosition);
                }
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

