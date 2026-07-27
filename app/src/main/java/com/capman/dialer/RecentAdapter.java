package com.capman.dialer;

import android.content.Context;
import android.provider.CallLog;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The call history list.
 *
 * - Consecutive calls to the same number collapse into one row marked "+2";
 *   the arrow expands it to show each call's time, date and duration.
 * - The default view covers the last 6 months; "Show more" at the bottom opens
 *   the whole log.
 */
public class RecentAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface Listener {
        void onCall(RecentItem r);

        void onInfo(RecentItem r);

        void onWhatsApp(RecentItem r);

        void onShowMore();
    }

    private static final int T_RECENT = 0;
    private static final int T_MORE = 1;
    private static final Object MORE = new Object();

    private final List<Object> items = new ArrayList<>();
    private final Set<Long> expanded = new HashSet<>();
    private final Listener listener;
    private Map<String, String> nameIndex;
    private Map<String, String> photoIndex;

    private final Locale tr = new Locale("tr", "TR");
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", tr);
    private final SimpleDateFormat dayFmt = new SimpleDateFormat("d MMM", tr);
    private final SimpleDateFormat yearFmt = new SimpleDateFormat("d MMM yyyy", tr);

    public RecentAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<RecentItem> recents, Map<String, String> nameIndex,
                       Map<String, String> photoIndex, boolean showMoreButton) {
        this.nameIndex = nameIndex;
        this.photoIndex = photoIndex;
        items.clear();
        items.addAll(recents);
        if (showMoreButton) items.add(MORE);
        expanded.clear();
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position) == MORE ? T_MORE : T_RECENT;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == T_MORE) {
            return new MoreVH(inf.inflate(R.layout.item_show_more, parent, false));
        }
        return new RecentVH(inf.inflate(R.layout.item_recent, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof MoreVH) {
            ((MoreVH) holder).button.setOnClickListener(v -> listener.onShowMore());
            return;
        }
        RecentItem r = (RecentItem) items.get(position);
        RecentVH vh = (RecentVH) holder;
        Context ctx = vh.itemView.getContext();

        String name = displayName(r);
        vh.name.setText(name);
        vh.avatar.setText(PhoneUtil.initials(name));
        String photo = photoIndex == null ? null : photoIndex.get(PhoneUtil.key(r.number));
        PhotoLoader.load(photo, vh.avatarPhoto, vh.avatar, false);

        int icon = iconFor(r.type);
        int tint = tintFor(ctx, r.type);
        vh.typeIcon.setImageResource(icon);
        vh.typeIcon.setColorFilter(tint);
        boolean missedLike = r.type == CallLog.Calls.MISSED_TYPE
                || r.type == CallLog.Calls.REJECTED_TYPE;
        vh.name.setTextColor(ctx.getColor(missedLike ? R.color.red : R.color.text));

        vh.sub.setText(when(r.date) + (r.duration > 0 ? " · " + duration(r.duration) : ""));

        vh.row.setOnClickListener(v -> listener.onCall(r));
        vh.info.setOnClickListener(v -> listener.onInfo(r));

        // You cannot message a withheld number on WhatsApp
        vh.whatsapp.setVisibility(r.number.isEmpty() ? View.GONE : View.VISIBLE);
        vh.whatsapp.setOnClickListener(v -> listener.onWhatsApp(r));

        // --- grouped calls
        // The badge shows the total: four calls in a row read as "+4".
        boolean grouped = r.group.size() > 1;
        boolean isOpen = expanded.contains(r.id);

        vh.count.setVisibility(grouped ? View.VISIBLE : View.GONE);
        vh.count.setText("+" + r.count);

        vh.expand.setVisibility(grouped ? View.VISIBLE : View.GONE);
        vh.expand.setRotation(isOpen ? 180f : 0f);
        vh.expand.setOnClickListener(v -> {
            if (expanded.contains(r.id)) expanded.remove(r.id);
            else expanded.add(r.id);
            notifyItemChanged(holder.getBindingAdapterPosition());
        });

        vh.expandContainer.removeAllViews();
        if (grouped && isOpen) {
            vh.expandContainer.setVisibility(View.VISIBLE);
            LayoutInflater inf = LayoutInflater.from(ctx);
            // If it says "+4", expanding must reveal all four so the number adds up
            for (int i = 0; i < r.group.size(); i++) {
                RecentItem child = r.group.get(i);
                View row = inf.inflate(R.layout.item_recent_child, vh.expandContainer, false);
                ImageView ci = row.findViewById(R.id.childType);
                ci.setImageResource(iconFor(child.type));
                ci.setColorFilter(tintFor(ctx, child.type));
                // Calls from the same day don't need the date repeated, the time is enough
                ((TextView) row.findViewById(R.id.childWhen))
                        .setText(sameDay(child.date, r.date) ? timeFmt.format(new Date(child.date))
                                : when(child.date));
                ((TextView) row.findViewById(R.id.childDuration))
                        .setText(child.duration > 0 ? duration(child.duration) : "—");
                vh.expandContainer.addView(row);
            }
        } else {
            vh.expandContainer.setVisibility(View.GONE);
        }
    }

    private int iconFor(int type) {
        switch (type) {
            case CallLog.Calls.INCOMING_TYPE: return R.drawable.ic_call_received;
            case CallLog.Calls.OUTGOING_TYPE: return R.drawable.ic_call_made;
            default: return R.drawable.ic_call_missed;
        }
    }

    private int tintFor(Context ctx, int type) {
        switch (type) {
            case CallLog.Calls.INCOMING_TYPE: return ctx.getColor(R.color.green);
            case CallLog.Calls.OUTGOING_TYPE: return ctx.getColor(R.color.blue);
            default: return ctx.getColor(R.color.red);
        }
    }

    private String displayName(RecentItem r) {
        if (nameIndex != null) {
            String n = nameIndex.get(PhoneUtil.key(r.number));
            if (n != null) return n;
        }
        if (r.cachedName != null && !r.cachedName.trim().isEmpty()) return r.cachedName;
        if (r.number.isEmpty()) return "Unknown number";
        return PhoneUtil.pretty(r.number);
    }

    private boolean sameDay(long a, long b) {
        Calendar ca = Calendar.getInstance();
        Calendar cb = Calendar.getInstance();
        ca.setTimeInMillis(a);
        cb.setTimeInMillis(b);
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR)
                && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR);
    }

    private String when(long ts) {
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(ts);
        if (DateUtils.isToday(ts)) return timeFmt.format(new Date(ts));
        if (DateUtils.isToday(ts + DateUtils.DAY_IN_MILLIS)) {
            return "Yesterday " + timeFmt.format(new Date(ts));
        }
        if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)) {
            return dayFmt.format(new Date(ts)) + " " + timeFmt.format(new Date(ts));
        }
        return yearFmt.format(new Date(ts));
    }

    private String duration(long seconds) {
        long m = seconds / 60, s = seconds % 60;
        if (m == 0) return s + " s";
        return m + " min " + s + " s";
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class RecentVH extends RecyclerView.ViewHolder {
        final View row;
        final TextView avatar, name, sub, count;
        final ImageView typeIcon, avatarPhoto;
        final ImageButton info, expand, whatsapp;
        final LinearLayout expandContainer;

        RecentVH(View v) {
            super(v);
            row = v.findViewById(R.id.row);
            avatar = v.findViewById(R.id.avatar);
            avatarPhoto = v.findViewById(R.id.avatarPhoto);
            name = v.findViewById(R.id.name);
            sub = v.findViewById(R.id.sub);
            count = v.findViewById(R.id.count);
            typeIcon = v.findViewById(R.id.typeIcon);
            info = v.findViewById(R.id.info);
            whatsapp = v.findViewById(R.id.whatsapp);
            expand = v.findViewById(R.id.expand);
            expandContainer = v.findViewById(R.id.expandContainer);
        }
    }

    static class MoreVH extends RecyclerView.ViewHolder {
        final TextView button;

        MoreVH(View v) {
            super(v);
            button = v.findViewById(R.id.showMore);
        }
    }
}
