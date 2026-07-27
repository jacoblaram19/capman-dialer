package com.capman.dialer;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Contact list. Tapping a row CALLS; the wide info button on the right opens details. */
public class ContactAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface Listener {
        void onCall(Contact c);

        void onInfo(Contact c);
    }

    private static final int T_HEADER = 0;
    private static final int T_CONTACT = 1;

    private final List<Object> items = new ArrayList<>();
    private final Listener listener;

    public ContactAdapter(Listener listener) {
        this.listener = listener;
    }

    /** Lays out the contacts, optionally with initial-letter headers. */
    public void submit(List<Contact> contacts, boolean showHeaders) {
        items.clear();
        String lastHeader = null;
        for (Contact c : contacts) {
            if (showHeaders) {
                String h = PhoneUtil.initials(c.displayName());
                if (!h.equals(lastHeader)) {
                    items.add(h);
                    lastHeader = h;
                }
            }
            items.add(c);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position) instanceof Contact ? T_CONTACT : T_HEADER;
    }

    /** Position of an initial-letter header, or -1 when no contact starts with it. */
    public int positionOfSection(String section) {
        for (int i = 0; i < items.size(); i++) {
            Object o = items.get(i);
            if (!(o instanceof Contact) && o.equals(section)) return i;
        }
        return -1;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == T_HEADER) {
            return new HeaderVH(inf.inflate(R.layout.item_header, parent, false));
        }
        return new ContactVH(inf.inflate(R.layout.item_contact, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object item = items.get(position);
        if (holder instanceof HeaderVH) {
            ((HeaderVH) holder).text.setText((String) item);
            return;
        }
        Contact c = (Contact) item;
        ContactVH vh = (ContactVH) holder;
        vh.name.setText(c.displayName());
        vh.avatar.setText(PhoneUtil.initials(c.displayName()));
        PhotoLoader.load(c.bestPhoto(false), vh.avatarPhoto, vh.avatar, false);

        String primary = c.primaryNumber();
        String label = c.labels.isEmpty() ? "" : c.labels.get(0);
        StringBuilder sub = new StringBuilder();
        if (primary != null) {
            if (!label.isEmpty()) sub.append(label).append(" · ");
            sub.append(PhoneUtil.pretty(primary));
        }
        if (c.numbers.size() > 1) {
            sub.append("  (+").append(c.numbers.size() - 1).append(")");
        }
        vh.sub.setText(sub.toString());

        vh.row.setOnClickListener(v -> listener.onCall(c));
        vh.info.setOnClickListener(v -> listener.onInfo(c));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ContactVH extends RecyclerView.ViewHolder {
        final View row;
        final TextView avatar, name, sub;
        final ImageButton info;
        final android.widget.ImageView avatarPhoto;

        ContactVH(View v) {
            super(v);
            row = v.findViewById(R.id.row);
            avatar = v.findViewById(R.id.avatar);
            avatarPhoto = v.findViewById(R.id.avatarPhoto);
            name = v.findViewById(R.id.name);
            sub = v.findViewById(R.id.sub);
            info = v.findViewById(R.id.info);
        }
    }

    static class HeaderVH extends RecyclerView.ViewHolder {
        final TextView text;

        HeaderVH(View v) {
            super(v);
            text = (TextView) v;
        }
    }
}
