package com.particlesdevs.photoncamera.ui.camera.views.settingsbar;

import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tiles of the shade's grid (GridLayoutManager(4)): the pinned keys, then the «Добавить» tile while fewer than 12 are
 * pinned. The key list changes through DiffUtil (pin, unpin) or {@link #move} (drag); a value change rebinds the views on
 * screen in place ({@link Binder#bind}), so a pressed tile keeps its view and its touch stream.
 */
final class ShadeTileAdapter extends RecyclerView.Adapter<ShadeTileAdapter.Holder> {
    /** Item of the «Добавить» tile. */
    static final String ADD = "\u0000add";

    /** Binds a tile to its key; owned by the sheet, which knows the catalog and the state. */
    interface Binder {
        void bind(ShadeTileView view, String key);

        void bindAdd(ShadeTileView view);

        void onCreated(Holder holder);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ShadeTileView tile;

        Holder(ShadeTileView tile) {
            super(tile);
            this.tile = tile;
        }

        String item() {
            return tile.addTile ? ADD : tile.key;
        }
    }

    private final List<String> items = new ArrayList<>();
    private final Binder binder;

    ShadeTileAdapter(Binder binder) {
        this.binder = binder;
        setHasStableIds(true);
    }

    /** The pinned keys and whether the «Добавить» tile follows them. */
    void submit(List<String> keys, boolean addTile) {
        List<String> next = new ArrayList<>(keys);
        if (addTile) next.add(ADD);
        List<String> old = new ArrayList<>(items);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return old.size(); }
            @Override public int getNewListSize() { return next.size(); }
            @Override public boolean areItemsTheSame(int o, int n) { return old.get(o).equals(next.get(n)); }
            // Values are rebound in place by the sheet; a moved or kept key is the same tile.
            @Override public boolean areContentsTheSame(int o, int n) { return true; }
        }, true);
        items.clear();
        items.addAll(next);
        diff.dispatchUpdatesTo(this);
    }

    /** Pinned keys in tile order (without the «Добавить» tile). */
    List<String> keys() {
        List<String> out = new ArrayList<>(items);
        out.remove(ADD);
        return out;
    }

    String item(int position) {
        return items.get(position);
    }

    boolean isAdd(int position) {
        return position >= 0 && position < items.size() && ADD.equals(items.get(position));
    }

    /** Drag: moves one tile; only notifyItemMoved, the dragged view stays the same. */
    void move(int from, int to) {
        if (from == to || isAdd(from) || isAdd(to)) return;
        if (from < to) for (int i = from; i < to; i++) Collections.swap(items, i, i + 1);
        else for (int i = from; i > to; i--) Collections.swap(items, i, i - 1);
        notifyItemMoved(from, to);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        String item = items.get(position);
        return ADD.equals(item) ? Long.MIN_VALUE : item.hashCode() & 0xFFFFFFFFL;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ShadeTileView tile = new ShadeTileView(parent.getContext());
        tile.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Holder holder = new Holder(tile);
        binder.onCreated(holder);
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        String item = items.get(position);
        if (ADD.equals(item)) binder.bindAdd(holder.tile);
        else binder.bind(holder.tile, item);
    }
}
