package com.particlesdevs.photoncamera.ui.camera;

import android.content.DialogInterface;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.settings.SettingsManager;
import com.particlesdevs.photoncamera.util.Log;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/* loaded from: classes10.dex */
public class LensDiscoveryActivity extends AppCompatActivity {
    private static final String TAG = "LensDiscovery";
    private LensAdapter adapter;
    private TextView foundCountText;
    private View lensMenu;
    private boolean lensSettingsChanged;
    private ProgressBar progressBar;
    private RecyclerView recyclerView;
    private SettingsManager settingsManager;
    private Screen currentScreen = Screen.MENU;
    private final List<LensInfo> foundLenses = new ArrayList();

    private enum Screen {
        MENU,
        IDS,
        VISIBILITY,
        NAMES
    }

    @Override // androidx.fragment.app.FragmentActivity, androidx.activity.ComponentActivity, androidx.core.app.ComponentActivity, android.app.Activity
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.settingsManager = PhotonCamera.getSettingsManagerStatic();
        setContentView(R.layout.activity_lens_discovery);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(new View.OnClickListener() { // from class: com.particlesdevs.photoncamera.ui.camera.LensDiscoveryActivity$$ExternalSyntheticLambda1
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                LensDiscoveryActivity.this.lambda$onCreate$0(view);
            }
        });
        this.progressBar = (ProgressBar) findViewById(R.id.progress_bar);
        this.foundCountText = (TextView) findViewById(R.id.found_count);
        this.recyclerView = (RecyclerView) findViewById(R.id.lens_list);
        this.recyclerView.setLayoutManager(new LinearLayoutManager(this));
        this.adapter = new LensAdapter(this.foundLenses);
        this.recyclerView.setAdapter(this.adapter);
        this.lensMenu = findViewById(R.id.lens_menu);
        findViewById(R.id.menu_lens_ids).setOnClickListener(new View.OnClickListener() { // from class: com.particlesdevs.photoncamera.ui.camera.LensDiscoveryActivity$$ExternalSyntheticLambda2
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                LensDiscoveryActivity.this.lambda$onCreate$1(view);
            }
        });
        findViewById(R.id.menu_lens_visibility).setOnClickListener(new View.OnClickListener() { // from class: com.particlesdevs.photoncamera.ui.camera.LensDiscoveryActivity$$ExternalSyntheticLambda3
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                LensDiscoveryActivity.this.lambda$onCreate$2(view);
            }
        });
        findViewById(R.id.menu_lens_names).setOnClickListener(new View.OnClickListener() { // from class: com.particlesdevs.photoncamera.ui.camera.LensDiscoveryActivity$$ExternalSyntheticLambda4
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                LensDiscoveryActivity.this.lambda$onCreate$3(view);
            }
        });
        showScreen(Screen.MENU);
        startDiscovery();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreate$0(View v) {
        handleBack();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreate$1(View v) {
        showScreen(Screen.IDS);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreate$2(View v) {
        showScreen(Screen.VISIBILITY);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onCreate$3(View v) {
        showScreen(Screen.NAMES);
    }

    private void showScreen(Screen screen) {
        this.currentScreen = screen;
        boolean menu = screen == Screen.MENU;
        this.lensMenu.setVisibility(menu ? 0 : 8);
        this.recyclerView.setVisibility(menu ? 8 : 0);
        this.foundCountText.setVisibility(menu ? 8 : 0);
        if (menu) {
            getSupportActionBar().setTitle(R.string.lens_discovery_title);
        } else if (screen == Screen.IDS) {
            getSupportActionBar().setTitle(R.string.lens_menu_ids);
            this.recyclerView.setAdapter(this.adapter);
        } else {
            getSupportActionBar().setTitle(screen == Screen.VISIBILITY ? R.string.lens_menu_visibility : R.string.lens_menu_names);
            this.recyclerView.setAdapter(new LensSettingAdapter(this.foundLenses, screen));
        }
    }

    private void handleBack() {
        if (this.currentScreen == Screen.MENU) {
            if (!this.lensSettingsChanged) {
                super.onBackPressed();
                return;
            } else {
                PhotonCamera.restartApp(this);
                return;
            }
        }
        showScreen(Screen.MENU);
    }

    @Override // androidx.activity.ComponentActivity, android.app.Activity
    public void onBackPressed() {
        handleBack();
    }

    private void startDiscovery() {
        new Thread(new Runnable() { // from class: com.particlesdevs.photoncamera.ui.camera.LensDiscoveryActivity$$ExternalSyntheticLambda5
            @Override // java.lang.Runnable
            public final void run() {
                try {
                    LensDiscoveryActivity.this.lambda$startDiscovery$5();
                } catch (CameraAccessException e) {
                    Log.e(TAG, "Lens discovery failed", e);
                }
            }
        }).start();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$startDiscovery$5() throws CameraAccessException {
        CameraManager manager = (CameraManager) getSystemService("camera");
        try {
            String[] ids = manager.getCameraIdList();
            for (String id : ids) {
                addLensInfo(manager, id, "Reported");
            }
            boolean isSamsung = Build.BRAND.equalsIgnoreCase("samsung") || Build.BRAND.equalsIgnoreCase("google");
            for (int i = 0; i < 150; i++) {
                checkAndAdd(manager, String.valueOf(i), "Hidden");
                if (isSamsung) {
                    checkAndAdd(manager, "0-" + i, "Hidden (SS)");
                }
                for (int j = 0; j < 10; j++) {
                    checkAndAdd(manager, i + "/" + j, "Hidden (Alt)");
                    checkAndAdd(manager, i + "-" + j, "Hidden (Alt)");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Discovery failed", e);
        }
        runOnUiThread(new Runnable() { // from class: com.particlesdevs.photoncamera.ui.camera.LensDiscoveryActivity$$ExternalSyntheticLambda0
            @Override // java.lang.Runnable
            public final void run() {
                LensDiscoveryActivity.this.lambda$startDiscovery$4();
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$startDiscovery$4() {
        this.progressBar.setVisibility(8);
        this.adapter.notifyDataSetChanged();
    }

    private void checkAndAdd(CameraManager manager, String id, String source) throws CameraAccessException {
        synchronized (this.foundLenses) {
            for (LensInfo info : this.foundLenses) {
                if (info.f381id.equals(id)) {
                    return;
                }
            }
            addLensInfo(manager, id, source);
        }
    }

    private void addLensInfo(CameraManager manager, String id, String source) throws CameraAccessException {
        Set<String> physicalIds;
        try {
            CameraCharacteristics chars = manager.getCameraCharacteristics(id);
            final LensInfo info = new LensInfo();
            info.f381id = id;
            info.source = source;
            Integer facing = (Integer) chars.get(CameraCharacteristics.LENS_FACING);
            if (facing != null) {
                if (facing.intValue() == 0) {
                    info.facing = "Front";
                } else if (facing.intValue() == 1) {
                    info.facing = "Back";
                } else if (facing.intValue() == 2) {
                    info.facing = "External";
                }
            } else {
                info.facing = "Unknown";
            }
            float[] focals = (float[]) chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            int i = 0;
            if (focals != null && focals.length > 0) {
                info.focalLength = focals[0];
            }
            if (Build.VERSION.SDK_INT >= 28 && (physicalIds = chars.getPhysicalCameraIds()) != null && !physicalIds.isEmpty()) {
                info.isLogical = true;
                info.physicalIds = new ArrayList(physicalIds);
            }
            int[] capabilities = (int[]) chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            if (capabilities != null) {
                int length = capabilities.length;
                while (true) {
                    if (i >= length) {
                        break;
                    }
                    int cap = capabilities[i];
                    if (cap != 3) {
                        i++;
                    } else {
                        info.supportsRaw = true;
                        break;
                    }
                }
            }
            runOnUiThread(new Runnable() { // from class: com.particlesdevs.photoncamera.ui.camera.LensDiscoveryActivity$$ExternalSyntheticLambda7
                @Override // java.lang.Runnable
                public final void run() {
                    LensDiscoveryActivity.this.lambda$addLensInfo$6(info);
                }
            });
        } catch (Exception e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$addLensInfo$6(LensInfo info) {
        RecyclerView.Adapter<?> active;
        synchronized (this.foundLenses) {
            for (LensInfo existing : this.foundLenses) {
                if (existing.f381id.equals(info.f381id)) {
                    return;
                }
            }
            this.foundLenses.add(info);
            this.adapter.notifyItemInserted(this.foundLenses.size() - 1);
            if ((this.currentScreen == Screen.VISIBILITY || this.currentScreen == Screen.NAMES) && (active = this.recyclerView.getAdapter()) != null) {
                active.notifyItemInserted(this.foundLenses.size() - 1);
            }
            this.foundCountText.setText(getString(R.string.lens_discovery_found_count, new Object[]{Integer.valueOf(this.foundLenses.size())}));
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public String automaticLensName(LensInfo info, int position) {
        return info.facing.equals("Front") ? getString(R.string.lens_front) + " " + (position + 1) : info.focalLength > 0.0f ? String.format(Locale.US, "%.1fmm", Float.valueOf(info.focalLength)) : "Camera " + info.f381id;
    }

    /* JADX INFO: Access modifiers changed from: private */
    class LensSettingAdapter extends RecyclerView.Adapter<LensSettingAdapter.SettingHolder> {
        private final List<LensInfo> lenses;
        private final Screen mode;

        LensSettingAdapter(List<LensInfo> lenses, Screen mode) {
            this.lenses = lenses;
            this.mode = mode;
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public SettingHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new SettingHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_lens_setting, parent, false));
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public void onBindViewHolder(final SettingHolder holder, final int position) {
            final LensInfo info = this.lenses.get(position);
            String nameKey = "lens_name_" + info.f381id;
            String orderKey = "lens_order_" + info.f381id;
            String customName = LensDiscoveryActivity.this.settingsManager.getString("default_scope", nameKey, "");
            String shownName = customName.isEmpty() ? LensDiscoveryActivity.this.automaticLensName(info, position) : customName;
            holder.title.setText(shownName);
            Set<String> hidden = LensDiscoveryActivity.this.settingsManager.getStringSet("default_scope", "hidden_camera_ids", new HashSet());
            if (this.mode == Screen.VISIBILITY) {
                holder.summary.setText("ID " + info.f381id + " · " + info.facing + " · " + (info.isLogical ? "Logical" : "Physical"));
                holder.toggle.setVisibility(0);
                holder.toggle.setOnCheckedChangeListener(null);
                holder.toggle.setChecked(!hidden.contains(info.f381id));
                holder.toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { // from class: com.particlesdevs.photoncamera.ui.camera.LensDiscoveryActivity$LensSettingAdapter$$ExternalSyntheticLambda0
                    @Override // android.widget.CompoundButton.OnCheckedChangeListener
                    public final void onCheckedChanged(CompoundButton compoundButton, boolean z) {
                        LensSettingAdapter.this.lambda$onBindViewHolder$0(info, compoundButton, z);
                    }
                });
                holder.itemView.setOnClickListener(new View.OnClickListener() { // from class: com.particlesdevs.photoncamera.ui.camera.LensDiscoveryActivity$LensSettingAdapter$$ExternalSyntheticLambda1
                    @Override // android.view.View.OnClickListener
                    public final void onClick(View view) {
                        holder.toggle.performClick();
                    }
                });
                return;
            }
            String order = LensDiscoveryActivity.this.settingsManager.getString("default_scope", orderKey, String.valueOf(position));
            holder.summary.setText(LensDiscoveryActivity.this.getString(R.string.lens_custom_order) + ": " + order + " · ID " + info.f381id);
            holder.toggle.setVisibility(8);
            holder.itemView.setOnClickListener(new View.OnClickListener() { // from class: com.particlesdevs.photoncamera.ui.camera.LensDiscoveryActivity$LensSettingAdapter$$ExternalSyntheticLambda2
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) {
                    LensSettingAdapter.this.lambda$onBindViewHolder$2(info, position, view);
                }
            });
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$onBindViewHolder$0(LensInfo info, CompoundButton button, boolean checked) {
            Set<String> changed = new HashSet<>(LensDiscoveryActivity.this.settingsManager.getStringSet("default_scope", "hidden_camera_ids", new HashSet()));
            String str = info.f381id;
            if (checked) {
                changed.remove(str);
            } else {
                changed.add(str);
            }
            LensDiscoveryActivity.this.settingsManager.set("default_scope", "hidden_camera_ids", changed);
            LensDiscoveryActivity.this.lensSettingsChanged = true;
            if (checked) {
                Set<String> user = new HashSet<>(LensDiscoveryActivity.this.settingsManager.getStringSet("default_scope", "user_camera_ids", new HashSet()));
                user.add(info.f381id);
                LensDiscoveryActivity.this.settingsManager.set("default_scope", "user_camera_ids", user);
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$onBindViewHolder$2(LensInfo info, int position, View v) {
            LensDiscoveryActivity.this.showLensEditDialog(info, position);
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public int getItemCount() {
            return this.lenses.size();
        }

        class SettingHolder extends RecyclerView.ViewHolder {
            final TextView summary;
            final TextView title;
            final SwitchCompat toggle;

            SettingHolder(View itemView) {
                super(itemView);
                this.title = (TextView) itemView.findViewById(R.id.lens_setting_title);
                this.summary = (TextView) itemView.findViewById(R.id.lens_setting_summary);
                this.toggle = (SwitchCompat) itemView.findViewById(R.id.lens_setting_switch);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showLensEditDialog(final LensInfo info, final int position) {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(1);
        int pad = (int) (getResources().getDisplayMetrics().density * 20.0f);
        fields.setPadding(pad, pad / 2, pad, 0);
        final EditText name = new EditText(this);
        name.setHint(R.string.lens_edit_name_hint);
        name.setSingleLine(true);
        name.setText(this.settingsManager.getString("default_scope", "lens_name_" + info.f381id, ""));
        final EditText order = new EditText(this);
        order.setHint(R.string.lens_edit_order_hint);
        order.setSingleLine(true);
        order.setInputType(2);
        order.setText(this.settingsManager.getString("default_scope", "lens_order_" + info.f381id, String.valueOf(position)));
        fields.addView(name);
        fields.addView(order);
        new AlertDialog.Builder(this).setTitle(R.string.lens_edit_title).setView(fields).setNegativeButton(android.R.string.cancel, (DialogInterface.OnClickListener) null).setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() { // from class: com.particlesdevs.photoncamera.ui.camera.LensDiscoveryActivity$$ExternalSyntheticLambda6
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) throws NumberFormatException {
                LensDiscoveryActivity.this.lambda$showLensEditDialog$7(info, name, position, order, dialogInterface, i);
            }
        }).show();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$showLensEditDialog$7(LensInfo info, EditText name, int position, EditText order, DialogInterface dialog, int which) throws NumberFormatException {
        this.settingsManager.set("default_scope", "lens_name_" + info.f381id, name.getText().toString().trim());
        int value = position;
        try {
            value = Integer.parseInt(order.getText().toString());
        } catch (NumberFormatException e) {
        }
        this.settingsManager.set("default_scope", "lens_order_" + info.f381id, String.valueOf(Math.max(0, value)));
        this.lensSettingsChanged = true;
        RecyclerView.Adapter<?> active = this.recyclerView.getAdapter();
        if (active != null) {
            active.notifyItemChanged(position);
        }
        Toast.makeText(this, R.string.lens_settings_saved, 1).show();
    }

    /* JADX INFO: Access modifiers changed from: private */
    static class LensInfo {
        String facing;
        float focalLength;

        /* renamed from: id */
        String f381id;
        boolean isLogical;
        List<String> physicalIds;
        String source;
        boolean supportsRaw;

        private LensInfo() {
            this.physicalIds = new ArrayList();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    class LensAdapter extends RecyclerView.Adapter<LensAdapter.ViewHolder> {
        private final List<LensInfo> lenses;

        LensAdapter(List<LensInfo> lenses) {
            this.lenses = lenses;
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_lens_info, parent, false);
            return new ViewHolder(view);
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public void onBindViewHolder(final ViewHolder holder, final int position) throws NumberFormatException {
            final LensInfo info = this.lenses.get(position);
            holder.customLabel.setVisibility(8);
            holder.customOrder.setVisibility(8);
            holder.saveDisplayBtn.setVisibility(8);
            holder.idText.setText("ID: " + info.f381id + " (" + info.source + ")");
            StringBuilder sb = new StringBuilder();
            sb.append("Facing: ").append(info.facing);
            if (info.focalLength > 0.0f) {
                sb.append("\nFocal Length: ").append(info.focalLength).append("mm");
            }
            sb.append("\nType: ").append(info.isLogical ? "Logical" : "Physical");
            sb.append("\nSupports RAW: ").append(info.supportsRaw ? "Yes" : "No");
            holder.detailsText.setText(sb.toString());
            if (!info.physicalIds.isEmpty()) {
                holder.physicalIdsText.setVisibility(0);
                holder.physicalIdsText.setText("Physical IDs: " + info.physicalIds.toString());
            } else {
                holder.physicalIdsText.setVisibility(8);
            }
            final Set<String> hiddenIds = LensDiscoveryActivity.this.settingsManager.getStringSet("default_scope", "hidden_camera_ids", new HashSet());
            final Set<String> userIds = LensDiscoveryActivity.this.settingsManager.getStringSet("default_scope", "user_camera_ids", new HashSet());
            userIds.contains(info.f381id);
            boolean isHidden = hiddenIds.contains(info.f381id);
            final String nameKey = "lens_name_" + info.f381id;
            final String orderKey = "lens_order_" + info.f381id;
            holder.customLabel.setText(LensDiscoveryActivity.this.settingsManager.getString("default_scope", nameKey, ""));
            int savedOrder = position;
            try {
                savedOrder = Integer.parseInt(LensDiscoveryActivity.this.settingsManager.getString("default_scope", orderKey, String.valueOf(position)));
            } catch (NumberFormatException e) {
            }
            holder.customOrder.setText(String.valueOf(savedOrder));
            holder.saveDisplayBtn.setOnClickListener(new View.OnClickListener() { // from class: com.particlesdevs.photoncamera.ui.camera.LensDiscoveryActivity$LensAdapter$$ExternalSyntheticLambda0
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) throws NumberFormatException {
                    LensAdapter.this.lambda$onBindViewHolder$0(nameKey, holder, position, orderKey, view);
                }
            });
            if (isHidden) {
                holder.enableBtn.setText("Show/Enable");
                holder.enableBtn.setOnClickListener(new View.OnClickListener() { // from class: com.particlesdevs.photoncamera.ui.camera.LensDiscoveryActivity$LensAdapter$$ExternalSyntheticLambda1
                    @Override // android.view.View.OnClickListener
                    public final void onClick(View view) {
                        LensAdapter.this.lambda$onBindViewHolder$1(hiddenIds, info, userIds, position, view);
                    }
                });
            } else {
                holder.enableBtn.setText("Hide");
                holder.enableBtn.setOnClickListener(new View.OnClickListener() { // from class: com.particlesdevs.photoncamera.ui.camera.LensDiscoveryActivity$LensAdapter$$ExternalSyntheticLambda2
                    @Override // android.view.View.OnClickListener
                    public final void onClick(View view) {
                        LensAdapter.this.lambda$onBindViewHolder$2(hiddenIds, info, position, view);
                    }
                });
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$onBindViewHolder$0(String nameKey, ViewHolder holder, int position, String orderKey, View v) throws NumberFormatException {
            LensDiscoveryActivity.this.settingsManager.set("default_scope", nameKey, holder.customLabel.getText().toString().trim());
            int order = position;
            try {
                order = Integer.parseInt(holder.customOrder.getText().toString());
            } catch (NumberFormatException e) {
            }
            LensDiscoveryActivity.this.settingsManager.set("default_scope", orderKey, String.valueOf(Math.max(0, order)));
            LensDiscoveryActivity.this.lensSettingsChanged = true;
            Toast.makeText(LensDiscoveryActivity.this, LensDiscoveryActivity.this.getString(R.string.lens_custom_save) + ". Restart app.", 0).show();
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$onBindViewHolder$1(Set hiddenIds, LensInfo info, Set userIds, int position, View v) {
            Set<String> newHidden = new HashSet<>(hiddenIds);
            newHidden.remove(info.f381id);
            LensDiscoveryActivity.this.settingsManager.set("default_scope", "hidden_camera_ids", newHidden);
            Set<String> newUser = new HashSet<>(userIds);
            newUser.add(info.f381id);
            LensDiscoveryActivity.this.settingsManager.set("default_scope", "user_camera_ids", newUser);
            LensDiscoveryActivity.this.lensSettingsChanged = true;
            notifyItemChanged(position);
            Toast.makeText(LensDiscoveryActivity.this, "Lens enabled. Restart app.", 0).show();
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$onBindViewHolder$2(Set hiddenIds, LensInfo info, int position, View v) {
            Set<String> newHidden = new HashSet<>(hiddenIds);
            newHidden.add(info.f381id);
            LensDiscoveryActivity.this.settingsManager.set("default_scope", "hidden_camera_ids", newHidden);
            LensDiscoveryActivity.this.lensSettingsChanged = true;
            notifyItemChanged(position);
            Toast.makeText(LensDiscoveryActivity.this, "Lens hidden. Restart app.", 0).show();
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public int getItemCount() {
            return this.lenses.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            EditText customLabel;
            EditText customOrder;
            TextView detailsText;
            Button enableBtn;
            TextView idText;
            TextView physicalIdsText;
            Button saveDisplayBtn;

            ViewHolder(View itemView) {
                super(itemView);
                this.idText = (TextView) itemView.findViewById(R.id.lens_id);
                this.detailsText = (TextView) itemView.findViewById(R.id.lens_details);
                this.physicalIdsText = (TextView) itemView.findViewById(R.id.physical_ids);
                this.enableBtn = (Button) itemView.findViewById(R.id.btn_enable);
                this.saveDisplayBtn = (Button) itemView.findViewById(R.id.btn_save_lens_display);
                this.customLabel = (EditText) itemView.findViewById(R.id.lens_custom_label);
                this.customOrder = (EditText) itemView.findViewById(R.id.lens_custom_order);
            }
        }
    }
}
