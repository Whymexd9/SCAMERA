package com.particlesdevs.photoncamera.gallery.ui.fragments;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.gallery.adapters.DragSelectionItemTouchListener;
import com.particlesdevs.photoncamera.gallery.files.GalleryFileOperations;
import com.particlesdevs.photoncamera.gallery.files.ImageFile;
import com.particlesdevs.photoncamera.gallery.helper.Constants;
import com.particlesdevs.photoncamera.gallery.interfaces.OnItemInteractionListener;
import com.particlesdevs.photoncamera.gallery.model.GalleryItem;
import com.particlesdevs.photoncamera.gallery.model.SelectionHelper;
import com.particlesdevs.photoncamera.gallery.ui.GallerySheets;
import com.particlesdevs.photoncamera.gallery.ui.GalleryUi;
import com.particlesdevs.photoncamera.gallery.ui.LibraryAdapter;
import com.particlesdevs.photoncamera.gallery.ui.SelectionBar;
import com.particlesdevs.photoncamera.gallery.viewmodel.GalleryViewModel;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * P59b: the library in the card style (GALLERY_TASK.md §1): header (camera / folders), folder chips with counts, day
 * sections over a 3-column grid with format and Ultra HDR badges, long-press + drag selection, a floating selection bar
 * (× · «3 снимка» · Сравнить · Поделиться · Удалить) and the delete / folders sheets. The data and file logic are the old
 * ones: GalleryViewModel's folders, SelectionHelper, GalleryFileOperations.deleteImageFiles, the share intent, the folders
 * preference, compare navigation by item positions.
 */
public class ImageLibraryFragment extends Fragment implements LibraryAdapter.Host {
    private static final String PREFS = "gallery_ui", KEY_CHIPS = "show_folder_chips";
    private GalleryViewModel viewModel;
    private NavController navController;
    private final SelectionHelper<GalleryItem> selection = new SelectionHelper<>();
    private Set<GalleryItem> dragBase = new LinkedHashSet<>();
    private LibraryAdapter adapter;
    private RecyclerView grid;
    private TextView empty;
    private HorizontalScrollView chipsScroll;
    private LinearLayout chips;
    private View selectionBar;
    private SelectionBar bar;
    private String activeFolder;
    private List<GalleryItem> folders = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        Context c = requireContext();
        navController = NavHostFragment.findNavController(this);
        FrameLayout root = new FrameLayout(c);
        root.setBackgroundColor(GalleryUi.BG);
        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View toCamera = GalleryUi.squareButton(c, R.drawable.ic_gallery_camera, Lang.t("К камере", "Back to the camera"), v -> requireActivity().finish());
        View toFolders = GalleryUi.squareButton(c, R.drawable.ic_gallery_folders, Lang.t("Папки галереи", "Gallery folders"), v -> openFolders());
        column.addView(GalleryUi.header(c, Lang.t("Галерея", "Gallery"), toCamera, toFolders));

        chipsScroll = new HorizontalScrollView(c);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        chipsScroll.setClipToPadding(false);
        chipsScroll.setPadding(GalleryUi.dp(c, 16), GalleryUi.dp(c, 4), GalleryUi.dp(c, 16), GalleryUi.dp(c, 10));
        chips = new LinearLayout(c);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chipsScroll.addView(chips);
        column.addView(chipsScroll);

        FrameLayout body = new FrameLayout(c);
        column.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        grid = new RecyclerView(c);
        grid.setClipToPadding(false);
        grid.setPadding(GalleryUi.dp(c, 9), 0, GalleryUi.dp(c, 9), GalleryUi.dp(c, 120));
        adapter = new LibraryAdapter(this);
        GridLayoutManager layout = new GridLayoutManager(c, 3);
        layout.setSpanSizeLookup(adapter.spans(3));
        grid.setLayoutManager(layout);
        grid.setAdapter(adapter);
        grid.setItemViewCacheSize(12);
        body.addView(grid, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        empty = GalleryUi.text(c, Lang.t("В этой папке пока нет снимков", "No photos in this folder yet"), 15, GalleryUi.MUTED);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(GalleryUi.dp(c, 20), GalleryUi.dp(c, 60), GalleryUi.dp(c, 20), GalleryUi.dp(c, 60));
        empty.setVisibility(View.GONE);
        body.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        bar = new SelectionBar(c, this::clearSelection, this::compareSelected, this::shareSelected, this::deleteSelected);
        selectionBar = bar.view;
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        bp.leftMargin = bp.rightMargin = GalleryUi.dp(c, 12);
        bp.bottomMargin = GalleryUi.dp(c, 14);
        root.addView(selectionBar, bp);
        selectionBar.setVisibility(View.GONE);

        grid.addOnItemTouchListener(new DragSelectionItemTouchListener(c, new OnItemInteractionListener() {
            @Override
            public void onItemClicked(RecyclerView view, RecyclerView.ViewHolder holder, int position) {}

            @Override
            public void onLongItemClicked(RecyclerView view, RecyclerView.ViewHolder holder, int position) {
                int item = adapter.itemAt(position);
                if (item < 0) return;
                holder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                dragBase = new LinkedHashSet<>(selection.getSelectedItems());
                GalleryItem g = adapter.items().get(item);
                if (!selection.getSelectedItems().contains(g)) selection.selectItem(g);
                onSelectionChanged();
            }

            @Override
            public void onMultipleViewHoldersSelected(RecyclerView view, List<RecyclerView.ViewHolder> s) {}

            @Override
            public void onViewHolderHovered(RecyclerView view, RecyclerView.ViewHolder holder) {}

            @Override
            public void onDragRange(RecyclerView view, int from, int to) {
                selectRange(from, to);
            }
        }));

        OnBackPressedCallback back = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (selecting()) clearSelection();
                else if (!navController.navigateUp()) requireActivity().finish();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), back);
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(GalleryViewModel.class);
        viewModel.getCurrentFolderImages().observe(getViewLifecycleOwner(), this::showItems);
        viewModel.getSelectedDisplayFolders().observe(getViewLifecycleOwner(), this::showFolders);
        viewModel.getUpdatePending().observe(getViewLifecycleOwner(), pending -> {
            if (pending) {
                viewModel.fetchAllMedia();
                viewModel.setCurrentFolderImages(viewModel.getAllSelectedImageFolder().getValue());
                activeFolder = null;
                viewModel.setUpdatePending(false);
            }
        });
        applyChipsVisibility();
    }

    private void showItems(List<GalleryItem> items) {
        if (items == null || adapter == null) return;
        selection.deselectAll();
        adapter.setItems(items);
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        onSelectionChanged();
    }

    private void showFolders(List<GalleryItem> list) {
        folders = list != null ? list : new ArrayList<>();
        if (activeFolder == null && !folders.isEmpty()) activeFolder = folders.get(0).getDisplayName();
        renderChips();
    }

    private void renderChips() {
        Context c = getContext();
        if (c == null || chips == null) return;
        chips.removeAllViews();
        for (GalleryItem f : folders) {
            String name = "ALL".equals(f.getDisplayName()) ? Lang.t("Все", "All") : f.getDisplayName();
            boolean active = f.getDisplayName().equals(activeFolder);
            TextView chip = GalleryUi.chip(c, name, f.getFiles().size(), active);
            chip.setContentDescription(name + ", " + GalleryUi.shots(f.getFiles().size()));
            chip.setOnClickListener(v -> {
                clearSelection();
                activeFolder = f.getDisplayName();
                viewModel.setCurrentFolderImages(f);
                renderChips();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = GalleryUi.dp(c, 8);
            chips.addView(chip, lp);
        }
    }

    // ---------------------------------------------------------------- selection

    @Override
    public boolean selecting() {
        return selection.isSelectionStarted() && !selection.isEmpty();
    }

    @Override
    public boolean isSelected(GalleryItem item) {
        return selection.getSelectedItems().contains(item);
    }

    @Override
    public void onPhotoClicked(int itemIndex, View view) {
        if (selecting()) {
            selection.toggleSelection(adapter.items().get(itemIndex));
            onSelectionChanged();
            return;
        }
        Bundle b = new Bundle();
        b.putInt(Constants.IMAGE_POSITION_KEY, itemIndex);
        navController.navigate(R.id.action_imageLibraryFragment_to_imageViewerFragment, b);
    }

    private void selectRange(int fromPosition, int toPosition) {
        int a = Math.min(fromPosition, toPosition), b = Math.max(fromPosition, toPosition);
        Set<GalleryItem> want = new LinkedHashSet<>(dragBase);
        for (int p = a; p <= b; ++p) {
            int item = adapter.itemAt(p);
            if (item >= 0) want.add(adapter.items().get(item));
        }
        for (GalleryItem g : new ArrayList<>(selection.getSelectedItems())) if (!want.contains(g)) selection.deselectItem(g);
        for (GalleryItem g : want) if (!selection.getSelectedItems().contains(g)) selection.selectItem(g);
        onSelectionChanged();
    }

    private void clearSelection() {
        selection.deselectAll();
        dragBase = new LinkedHashSet<>();
        onSelectionChanged();
    }

    private void onSelectionChanged() {
        if (adapter == null || grid == null) return;
        int n = selection.getSelectedItems().size();
        if (n == 0 && selection.isSelectionStarted()) selection.deselectAll();
        boolean on = selecting();
        adapter.refreshSelection(grid);
        selectionBar.setVisibility(on ? View.VISIBLE : View.GONE);
        bar.bind(n);
    }

    private void compareSelected() {
        List<GalleryItem> s = selection.getSelectedItems();
        if (s.size() != 2) return;
        List<GalleryItem> items = adapter.items();
        Bundle b = new Bundle(2);
        b.putInt(Constants.IMAGE1_KEY, items.indexOf(s.get(0)));
        b.putInt(Constants.IMAGE2_KEY, items.indexOf(s.get(1)));
        navController.navigate(R.id.action_imageLibraryFragment_to_imageCompareFragment, b);
    }

    private void shareSelected() {
        ArrayList<Uri> uris = selection.getSelectedItems().stream().map(g -> g.getFile().getFileUri()).collect(Collectors.toCollection(ArrayList::new));
        if (uris.isEmpty()) return;
        Intent share = new Intent(Intent.ACTION_SEND_MULTIPLE);
        share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        share.setType("image/*");
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, null));
    }

    private List<GalleryItem> pendingDelete = new ArrayList<>();

    private void deleteSelected() {
        List<GalleryItem> toDelete = new ArrayList<>(selection.getSelectedItems());
        if (toDelete.isEmpty()) return;
        long bytes = toDelete.stream().mapToLong(g -> g.getFile().getSize()).sum();
        GallerySheets.showDelete(requireContext(), toDelete.size(), bytes, () -> {
            pendingDelete = toDelete;
            GalleryFileOperations.deleteImageFiles(getActivity(), toDelete.stream().map(g -> (ImageFile) g.getFile()).collect(Collectors.toList()),
                    this::handleImagesDeletedCallback);
        });
    }

    public void handleImagesDeletedCallback(boolean isDeleted) {
        View root = getView();
        if (isDeleted) {
            List<GalleryItem> removed = pendingDelete.isEmpty() ? new ArrayList<>(selection.getSelectedItems()) : pendingDelete;
            int n = removed.size();
            List<GalleryItem> items = new ArrayList<>(adapter.items());
            items.removeAll(removed);
            pendingDelete = new ArrayList<>();
            clearSelection();
            viewModel.setUpdatePending(true);
            adapter.setItems(items);
            if (root != null) Snackbar.make(root, Lang.t("Удалено: ", "Deleted: ") + GalleryUi.shots(n), Snackbar.LENGTH_SHORT).show();
        } else if (root != null) {
            Snackbar.make(root, Lang.t("Не удалось удалить", "Deletion failed"), Snackbar.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- folders sheet

    private void openFolders() {
        Context c = requireContext();
        ArrayList<GalleryFileOperations.ImagesFolder> all = GalleryFileOperations.FindAllFoldersWithImages(c.getContentResolver());
        Set<String> chosen = new HashSet<>(PreferenceKeys.getStringSet(PreferenceKeys.Key.FOLDERS_LIST));
        if (chosen.isEmpty()) for (GalleryFileOperations.ImagesFolder f : GalleryFileOperations.getSelectedFolders()) chosen.add(String.valueOf(f.getFolderId()));
        List<GallerySheets.Folder> rows = new ArrayList<>();
        for (GalleryFileOperations.ImagesFolder f : all) {
            String id = String.valueOf(f.getFolderId());
            rows.add(new GallerySheets.Folder(id, f.getFolderName(), folderPath(f), chosen.contains(id) || chosen.contains(f.getFolderName())));
        }
        GallerySheets.show(c, GallerySheets.folders(c, chipsShown(), rows, (folder, on) -> {
            if (folder == null) {
                prefs().edit().putBoolean(KEY_CHIPS, on).apply();
                applyChipsVisibility();
                return;
            }
            Set<String> set = new HashSet<>();
            for (GallerySheets.Folder r : rows) if (r.on) set.add(r.id);
            PhotonCamera.getSettingsManagerStatic().set("default_scope", PreferenceKeys.Key.FOLDERS_LIST, set);
            clearSelection();
            viewModel.setUpdatePending(true);
        }));
    }

    private static String folderPath(GalleryFileOperations.ImagesFolder f) {
        ImageFile top = f.getTopImage();
        String p = top != null ? top.getAbsolutePath() : null;
        if (p == null) return f.getFolderName();
        int slash = p.lastIndexOf('/');
        String dir = slash > 0 ? p.substring(0, slash) : p;
        String root = "/storage/emulated/0/";
        return dir.startsWith(root) ? dir.substring(root.length()) : dir;
    }

    private SharedPreferences prefs() {
        return requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private boolean chipsShown() {
        return prefs().getBoolean(KEY_CHIPS, true);
    }

    private void applyChipsVisibility() {
        if (chipsScroll != null) chipsScroll.setVisibility(chipsShown() ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onDestroyView() {
        grid = null;
        adapter = null;
        super.onDestroyView();
    }
}
