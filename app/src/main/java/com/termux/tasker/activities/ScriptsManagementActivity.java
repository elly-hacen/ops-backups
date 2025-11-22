package com.termux.tasker.activities;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textview.MaterialTextView;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.theme.TermuxThemeUtils;
import com.termux.shared.theme.NightMode;
import com.termux.tasker.R;
import com.termux.tasker.TermuxTaskerApplication;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ScriptsManagementActivity extends AppCompatActivity {

    private static final String LOG_TAG = "ScriptsManagementActivity";
    private static final String PREFS_NAME = "BackupSettings";
    
    private RecyclerView recyclerView;
    private View emptyStateView;
    private SharedPreferences prefs;
    private ScriptAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Enable dynamic colors - Android 12+/API 31+ is our minSdk
            try {
                getTheme().applyStyle(com.google.android.material.R.style.ThemeOverlay_Material3_DynamicColors_DayNight, true);
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Dynamic colors not available: " + e.getMessage());
        }
        
        setContentView(R.layout.activity_scripts);

        TermuxThemeUtils.setAppNightMode(this);
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setDisplayHomeAsUpEnabled(true);
                getSupportActionBar().setHomeAsUpIndicator(R.drawable.ic_close);
                getSupportActionBar().setTitle(getString(R.string.menu_scripts));
            }
            toolbar.setNavigationOnClickListener(v -> finish());
        }

        recyclerView = findViewById(R.id.recycler_scripts);
        emptyStateView = findViewById(R.id.textview_empty_state);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        
        findViewById(R.id.button_add_script).setOnClickListener(v -> showAddEditDialog(-1, null));
        
        loadScripts();
    }

    @Override
    protected void onResume() {
        super.onResume();
        TermuxTaskerApplication.setLogConfig(this, false);
        Logger.logVerbose(LOG_TAG, "onResume");
    }

    private void loadScripts() {
        String scriptsJson = prefs.getString("backup_scripts", "[]");
        
        try {
            JSONArray scriptsArray = new JSONArray(scriptsJson);
            
            if (scriptsArray.length() == 0) {
                emptyStateView.setVisibility(View.VISIBLE);
                recyclerView.setVisibility(View.GONE);
            } else {
                emptyStateView.setVisibility(View.GONE);
                recyclerView.setVisibility(View.VISIBLE);
                
                List<ScriptItem> items = new ArrayList<>();
                for (int i = 0; i < scriptsArray.length(); i++) {
                    JSONObject item = scriptsArray.getJSONObject(i);
                    items.add(new ScriptItem(
                        i,
                        item.getString("name"),
                        item.getString("path"),
                        item.optBoolean("enabled", true)
                    ));
                }
                
                adapter = new ScriptAdapter(items);
                recyclerView.setAdapter(adapter);
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to load scripts: " + e.getMessage());
            emptyStateView.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        }
    }

    private void showAddEditDialog(int position, ScriptItem existingScript) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_add_script, null);
        
        MaterialTextView titleView = dialogView.findViewById(R.id.textview_dialog_title);
        com.google.android.material.textfield.TextInputLayout nameLayout = dialogView.findViewById(R.id.layout_script_name);
        com.google.android.material.textfield.TextInputLayout pathLayout = dialogView.findViewById(R.id.layout_script_path);
        TextInputEditText nameEdit = dialogView.findViewById(R.id.edit_script_name);
        TextInputEditText pathEdit = dialogView.findViewById(R.id.edit_script_path);
        MaterialButton cancelButton = dialogView.findViewById(R.id.button_cancel);
        MaterialButton saveButton = dialogView.findViewById(R.id.button_save);
        MaterialButton path1Button = dialogView.findViewById(R.id.button_path1);
        MaterialButton path2Button = dialogView.findViewById(R.id.button_path2);
        MaterialButton path3Button = dialogView.findViewById(R.id.button_path3);
        
        if (existingScript != null) {
            titleView.setText(R.string.edit_script);
            nameEdit.setText(existingScript.name);
            pathEdit.setText(existingScript.path);
            nameEdit.post(() -> {
                nameEdit.clearFocus();
                pathEdit.clearFocus();
            });
        }
        
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();
        
        // Quick path buttons
        path1Button.setOnClickListener(v -> {
            String current = pathEdit.getText() != null ? pathEdit.getText().toString() : "";
            if (current.isEmpty()) {
                pathEdit.setText("~/.termux/tasker/");
            } else {
                pathEdit.append("~/.termux/tasker/");
            }
            pathLayout.setError(null);
        });
        
        path2Button.setOnClickListener(v -> {
            String current = pathEdit.getText() != null ? pathEdit.getText().toString() : "";
            if (current.isEmpty()) {
                pathEdit.setText("~/storage/shared/");
            } else {
                pathEdit.append("~/storage/shared/");
            }
            pathLayout.setError(null);
        });
        
        path3Button.setOnClickListener(v -> {
            String current = pathEdit.getText() != null ? pathEdit.getText().toString() : "";
            if (current.isEmpty()) {
                pathEdit.setText("/data/data/com.termux/");
            } else {
                pathEdit.append("/data/data/com.termux/");
            }
            pathLayout.setError(null);
        });
        
        cancelButton.setOnClickListener(v -> dialog.dismiss());
        
        saveButton.setOnClickListener(v -> {
            String name = nameEdit.getText() != null ? nameEdit.getText().toString().trim() : "";
            String path = pathEdit.getText() != null ? pathEdit.getText().toString().trim() : "";
            
            if (name.isEmpty()) {
                Toast.makeText(this, R.string.error_empty_script_name, Toast.LENGTH_SHORT).show();
                return;
            }
            
            if (path.isEmpty()) {
                Toast.makeText(this, R.string.error_empty_script_path, Toast.LENGTH_SHORT).show();
                return;
            }
            
            // Validate path format
            if (!path.startsWith("/") && !path.startsWith("~/")) {
                pathLayout.setError(getString(R.string.error_invalid_script_path));
                return;
            }
            
            // Check for duplicate paths
            if (isDuplicatePath(path, position)) {
                pathLayout.setError("This script path already exists");
                return;
            }
            
            pathLayout.setError(null);
            saveScript(position, name, path);
            dialog.dismiss();
        });
        
        // Clear error on text change
        pathEdit.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                pathLayout.setError(null);
            }
            
            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });
        
        dialog.show();
    }


    private boolean isDuplicatePath(String path, int currentPosition) {
        try {
            String scriptsJson = prefs.getString("backup_scripts", "[]");
            JSONArray scriptsArray = new JSONArray(scriptsJson);
            
            for (int i = 0; i < scriptsArray.length(); i++) {
                if (i == currentPosition) continue; // Skip current item when editing
                JSONObject script = scriptsArray.getJSONObject(i);
                if (script.getString("path").equals(path)) {
                    return true;
                }
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to check duplicates: " + e.getMessage());
        }
        return false;
    }

    private void saveScript(int position, String name, String path) {
        try {
            String scriptsJson = prefs.getString("backup_scripts", "[]");
            JSONArray scriptsArray = new JSONArray(scriptsJson);
            
            JSONObject scriptObj = new JSONObject();
            scriptObj.put("name", name);
            scriptObj.put("path", path);
            scriptObj.put("enabled", true);
            
            if (position >= 0 && position < scriptsArray.length()) {
                // Edit existing
                JSONObject existing = scriptsArray.getJSONObject(position);
                scriptObj.put("enabled", existing.optBoolean("enabled", true));
                scriptsArray.put(position, scriptObj);
            } else {
                // Add new
                scriptsArray.put(scriptObj);
            }
            
            prefs.edit().putString("backup_scripts", scriptsArray.toString()).apply();
            Toast.makeText(this, R.string.script_saved, Toast.LENGTH_SHORT).show();
            loadScripts();
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to save script: " + e.getMessage());
            Toast.makeText(this, "Failed to save script", Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleScript(int position, boolean enabled) {
        try {
            String scriptsJson = prefs.getString("backup_scripts", "[]");
            JSONArray scriptsArray = new JSONArray(scriptsJson);
            
            if (position >= 0 && position < scriptsArray.length()) {
                JSONObject scriptObj = scriptsArray.getJSONObject(position);
                scriptObj.put("enabled", enabled);
                scriptsArray.put(position, scriptObj);
                prefs.edit().putString("backup_scripts", scriptsArray.toString()).apply();
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to toggle script: " + e.getMessage());
        }
    }

    private void deleteScript(int position) {
        try {
            String scriptsJson = prefs.getString("backup_scripts", "[]");
            JSONArray scriptsArray = new JSONArray(scriptsJson);
            
            if (position >= 0 && position < scriptsArray.length()) {
                JSONObject script = scriptsArray.getJSONObject(position);
                String scriptName = script.getString("name");
                
                new AlertDialog.Builder(this)
                        .setTitle(R.string.confirm_delete_script)
                        .setMessage(getString(R.string.confirm_delete_script_message, scriptName))
                        .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                            try {
                                scriptsArray.remove(position);
                                prefs.edit().putString("backup_scripts", scriptsArray.toString()).apply();
                                Toast.makeText(this, R.string.script_deleted, Toast.LENGTH_SHORT).show();
                                loadScripts();
                            } catch (Exception e) {
                                Logger.logError(LOG_TAG, "Failed to delete: " + e.getMessage());
                            }
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to delete script: " + e.getMessage());
        }
    }

    private static class ScriptItem {
        int position;
        String name;
        String path;
        boolean enabled;

        ScriptItem(int position, String name, String path, boolean enabled) {
            this.position = position;
            this.name = name;
            this.path = path;
            this.enabled = enabled;
        }
    }

    private class ScriptAdapter extends RecyclerView.Adapter<ScriptViewHolder> {
        private List<ScriptItem> items;

        ScriptAdapter(List<ScriptItem> items) {
            this.items = items;
        }

        @Override
        public ScriptViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            View view = getLayoutInflater().inflate(R.layout.script_item, parent, false);
            return new ScriptViewHolder(view);
        }

        @Override
        public void onBindViewHolder(ScriptViewHolder holder, int position) {
            ScriptItem item = items.get(position);
            holder.nameView.setText(item.name);
            holder.pathView.setText(item.path);
            holder.enabledCheckbox.setChecked(item.enabled);
            
            // Visual feedback for disabled scripts
            float alpha = item.enabled ? 1.0f : 0.5f;
            holder.nameView.setAlpha(alpha);
            holder.pathView.setAlpha(alpha);
            holder.copyButton.setAlpha(alpha);
            holder.editButton.setAlpha(alpha);
            holder.deleteButton.setAlpha(alpha);
            holder.itemView.setAlpha(item.enabled ? 1.0f : 0.7f);
            
            holder.enabledCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                toggleScript(position, isChecked);
                // Update visual feedback immediately
                float newAlpha = isChecked ? 1.0f : 0.5f;
                holder.nameView.setAlpha(newAlpha);
                holder.pathView.setAlpha(newAlpha);
                holder.copyButton.setAlpha(newAlpha);
                holder.editButton.setAlpha(newAlpha);
                holder.deleteButton.setAlpha(newAlpha);
                holder.itemView.setAlpha(isChecked ? 1.0f : 0.7f);
            });
            
            holder.copyButton.setOnClickListener(v -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Script Path", item.path);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(ScriptsManagementActivity.this, "Path copied", Toast.LENGTH_SHORT).show();
            });
            
            holder.editButton.setOnClickListener(v -> {
                showAddEditDialog(position, item);
            });
            
            holder.deleteButton.setOnClickListener(v -> {
                deleteScript(position);
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private static class ScriptViewHolder extends RecyclerView.ViewHolder {
        MaterialTextView nameView;
        MaterialTextView pathView;
        MaterialCheckBox enabledCheckbox;
        MaterialButton copyButton;
        MaterialButton editButton;
        MaterialButton deleteButton;

        ScriptViewHolder(View itemView) {
            super(itemView);
            nameView = itemView.findViewById(R.id.textview_script_name);
            pathView = itemView.findViewById(R.id.textview_script_path);
            enabledCheckbox = itemView.findViewById(R.id.checkbox_enabled);
            copyButton = itemView.findViewById(R.id.button_copy);
            editButton = itemView.findViewById(R.id.button_edit);
            deleteButton = itemView.findViewById(R.id.button_delete);
        }
    }
}

