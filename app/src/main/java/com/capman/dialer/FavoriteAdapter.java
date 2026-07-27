package com.capman.dialer;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * The horizontal favorites strip. A "+" always sits at the end so there is
 * somewhere to add from even when there are no favorites yet.
 */
public class FavoriteAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface Listener {
        void onFavoriteCall(Contact c);

        void onFavoriteMenu(Contact c);

        void onFavoriteAdd();

        /** In edit mode a long press starts the drag. */
        void onFavoriteDragStart(RecyclerView.ViewHolder holder);
    }

    private static final int T_FAV = 0;
    private static final int T_ADD = 1;

    private final List<Contact> items = new ArrayList<>();
    private final Listener listener;
    private boolean editMode = false;

    public FavoriteAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<Contact> favorites) {
        items.clear();
        if (favorites != null) items.addAll(favorites);
        notifyDataSetChanged();
    }

    public void setEditMode(boolean on) {
        editMode = on;
        notifyDataSetChanged();
    }

    public boolean isEditMode() {
        return editMode;
    }

    /** Moves an item while it is being dragged. */
    public boolean move(int from, int to) {
        if (from < 0 || to < 0 || from >= items.size() || to >= items.size()) return false;
        items.add(to, items.remove(from));
        notifyItemMoved(from, to);
        return true;
    }

    /** The order to persist: contact ids, comma separated. */
    public String orderCsv() {
        StringBuilder sb = new StringBuilder();
        for (Contact c : items) {
            if (sb.length() > 0) sb.append(',');
            sb.append(c.id);
        }
        return sb.toString();
    }

    /** Can this position be dragged? The "+" button has to stay put. */
    public boolean isDraggable(int position) {
        return editMode && position >= 0 && position < items.size();
    }

    @Override
    public int getItemViewType(int position) {
        return position < items.size() ? T_FAV : T_ADD;
    }

    @Override
    public int getItemCount() {
        return items.size() + 1;       // +1 for the "add" button
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == T_ADD) {
            return new AddVH(inf.inflate(R.layout.item_favorite_add, parent, false));
        }
        return new FavVH(inf.inflate(R.layout.item_favorite, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof AddVH) {
            holder.itemView.setOnClickListener(v -> listener.onFavoriteAdd());
            return;
        }
        Contact c = items.get(position);
        FavVH vh = (FavVH) holder;
        String name = c.displayName();
        vh.name.setText(name);
        vh.avatar.setText(PhoneUtil.initials(name));
        PhotoLoader.load(c.bestPhoto(false), vh.avatarPhoto, vh.avatar, false);

        // In edit mode it grows slightly and jitters, so it reads as "movable"
        vh.itemView.setAlpha(editMode ? 0.92f : 1f);

        vh.itemView.setOnClickListener(v -> {
            if (editMode) return;               // don't place a call by accident while reordering
            listener.onFavoriteCall(c);
        });
        vh.itemView.setOnLongClickListener(v -> {
            if (editMode) {
                listener.onFavoriteDragStart(vh);
            } else {
                listener.onFavoriteMenu(c);
            }
            return true;
        });
    }

    static class FavVH extends RecyclerView.ViewHolder {
        final TextView avatar, name;
        final ImageView avatarPhoto;

        FavVH(View v) {
            super(v);
            avatar = v.findViewById(R.id.avatar);
            avatarPhoto = v.findViewById(R.id.avatarPhoto);
            name = v.findViewById(R.id.name);
        }
    }

    static class AddVH extends RecyclerView.ViewHolder {
        AddVH(View v) {
            super(v);
        }
    }
}
