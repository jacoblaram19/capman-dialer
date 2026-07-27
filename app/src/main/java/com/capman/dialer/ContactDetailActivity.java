package com.capman.dialer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.CallLog;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.inputmethod.InputMethodManager;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The contact detail screen. The "i" button leads here both for a saved
 * contact and for a bare number from the call log - it is the same screen.
 *
 * The lower part lists the calls with that person: the last 5 first, all of
 * them behind "Show all entries".
 */
public class ContactDetailActivity extends BaseActivity {

    private static final String EX_CONTACT_ID = "contact_id";
    private static final String EX_NUMBER = "number";
    private static final String EX_FOCUS_NOTE = "not_odakla";

    private static final int PREVIEW_COUNT = 5;
    private static final int ALL_LIMIT = 500;

    private long contactId = -1;
    private String number;
    private Contact contact;
    private boolean showAllCalls = false;
    private boolean blocked = false;
    private boolean starred = false;

    private TextView nameView, subtitleView, avatarView, showAllButton, numbersTitle;
    private android.widget.EditText noteText;
    private LinearLayout numbersContainer, callsContainer;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private final Locale tr = new Locale("tr", "TR");
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", tr);
    private final SimpleDateFormat dayFmt = new SimpleDateFormat("d MMMM", tr);
    private final SimpleDateFormat yearFmt = new SimpleDateFormat("d MMMM yyyy", tr);

    public static Intent intentFor(Context ctx, long contactId, String number) {
        Intent i = new Intent(ctx, ContactDetailActivity.class);
        i.putExtra(EX_CONTACT_ID, contactId);
        i.putExtra(EX_NUMBER, number);
        return i;
    }

    /** Opens the screen with the note field focused and the keyboard up, for the "Add note" button. */
    public static Intent intentForNote(Context ctx, long contactId, String number) {
        return intentFor(ctx, contactId, number).putExtra(EX_FOCUS_NOTE, true);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_contact_detail);

        contactId = getIntent().getLongExtra(EX_CONTACT_ID, -1);
        number = getIntent().getStringExtra(EX_NUMBER);

        nameView = findViewById(R.id.name);
        subtitleView = findViewById(R.id.subtitle);
        avatarView = findViewById(R.id.avatar);
        numbersContainer = findViewById(R.id.numbersContainer);
        numbersTitle = findViewById(R.id.numbersTitle);
        callsContainer = findViewById(R.id.callsContainer);
        showAllButton = findViewById(R.id.showAllCalls);

        noteText = findViewById(R.id.noteText);
        noteText.setText(Notes.get(this, number));
        if (getIntent().getBooleanExtra(EX_FOCUS_NOTE, false)) focusNote();

        findViewById(R.id.back).setOnClickListener(v -> finish());
        showAllButton.setOnClickListener(v -> {
            showAllCalls = true;
            showAllButton.setVisibility(View.GONE);
            loadCalls();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        showAllCalls = false;
        load();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // The note is saved quietly on the way out; there is no separate "save" button
        if (noteText != null) Notes.set(this, number, noteText.getText().toString());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    /** Focus the note field and raise the keyboard. */
    private void focusNote() {
        noteText.requestFocus();
        noteText.setSelection(noteText.getText().length());
        noteText.post(() -> {
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) imm.showSoftInput(noteText, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    // ------------------------------------------------------------------ loading

    private void load() {
        io.execute(() -> {
            // Coming from the call screen there is no contact id (-1), only a
            // number. Look the contact up from it first: PhoneLookup matches
            // across country-code, leading-zero and spacing differences by
            // itself, which makes it the right tool here.
            if (contactId <= 0 && number != null && !number.isEmpty()) {
                Contact byNumber = ContactsRepo.lookupByNumber(getContentResolver(), number);
                if (byNumber != null && byNumber.id > 0) contactId = byNumber.id;
            }
            Contact c = contactId > 0 ? ContactsRepo.load(getContentResolver(), contactId) : null;
            ui.post(() -> {
                contact = c;
                starred = c != null && c.starred;
                bindHeader();
                bindNumbers();
                loadCalls();
                refreshBlockState();
            });
        });
    }

    /** The blocked state comes from the system list; the query runs in the background. */
    private void refreshBlockState() {
        final List<String> ns = new ArrayList<>(numbers());
        if (ns.isEmpty()) {
            paintBlock();
            return;
        }
        io.execute(() -> {
            boolean any = false;
            for (String n : ns) {
                if (BlockRepo.isBlocked(this, n)) {
                    any = true;
                    break;
                }
            }
            final boolean b = any;
            ui.post(() -> {
                blocked = b;
                paintBlock();
            });
        });
    }

    private void paintBlock() {
        ImageView icon = findViewById(R.id.actionBlockIcon);
        TextView label = findViewById(R.id.actionBlockLabel);
        icon.setBackgroundResource(blocked
                ? R.drawable.bg_circle_gold : R.drawable.bg_circle_red);
        icon.setColorFilter(getColor(blocked ? R.color.gold : R.color.red));
        label.setText(blocked ? "Unblock" : "Block");
    }

    private void paintFavorite() {
        ImageView star = findViewById(R.id.favorite);
        star.setVisibility(contact != null ? View.VISIBLE : View.GONE);
        star.setImageResource(starred ? R.drawable.ic_star : R.drawable.ic_star_border);
        star.setColorFilter(getColor(starred ? R.color.gold : R.color.text_dim));
    }

    private void toggleFavorite() {
        if (contact == null) return;
        final boolean next = !starred;
        io.execute(() -> {
            final boolean ok = ContactsRepo.setStarred(getContentResolver(), contact.id, next);
            ui.post(() -> {
                if (!ok) {
                    Toast.makeText(this, "Could not change the favorite", Toast.LENGTH_SHORT).show();
                    return;
                }
                starred = next;
                paintFavorite();
                Toast.makeText(this, next ? "Added to favorites" : "Removed from favorites",
                        Toast.LENGTH_SHORT).show();
            });
        });
    }

    /** Every number of the contact is blocked or unblocked together, so they cannot use a second line. */
    private void toggleBlock() {
        final List<String> ns = numbers();
        if (ns.isEmpty()) {
            Toast.makeText(this, "No number", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!BlockRepo.canBlock(this)) {
            Toast.makeText(this, "Number blocking is not available. This app has to be "
                    + "the default phone app.", Toast.LENGTH_LONG).show();
            return;
        }
        if (blocked) {
            int n = 0;
            for (String num : ns) {
                if (BlockRepo.unblock(this, num)) n++;
            }
            blocked = false;
            paintBlock();
            Toast.makeText(this, n > 0 ? "Unblocked" : "Could not unblock",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String who = contact != null ? contact.displayName() : PhoneUtil.pretty(ns.get(0));
        String extra = ns.size() > 1 ? "\n\n(All " + ns.size() + " numbers will be blocked.)" : "";
        new AlertDialog.Builder(this)
                .setTitle("Block")
                .setMessage("Block " + who + "?\n\n"
                        + "Calls from this number are rejected without the phone ever ringing." + extra)
                .setPositiveButton("Block", (d, w) -> {
                    int n = 0;
                    for (String num : ns) {
                        if (BlockRepo.block(this, num)) n++;
                    }
                    blocked = n > 0;
                    paintBlock();
                    Toast.makeText(this, n > 0 ? "Blocked" : "Could not block",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void bindHeader() {
        String title;
        String sub;
        if (contact != null) {
            title = contact.displayName();
            sub = contact.numbers.size() > 1
                    ? contact.numbers.size() + " numbers" : "In your contacts";
        } else {
            title = number != null && !number.isEmpty()
                    ? PhoneUtil.pretty(number) : getString(R.string.unknown_number);
            sub = "Not in your contacts";
        }
        nameView.setText(title);
        subtitleView.setText(sub);
        avatarView.setText(PhoneUtil.initials(title));
        PhotoLoader.load(contact == null ? null : contact.bestPhoto(true),
                findViewById(R.id.avatarPhoto), avatarView, true);

        String callNumber = primaryNumber();

        findViewById(R.id.actionCall).setOnClickListener(v -> {
            if (callNumber != null) Dial.call(this, callNumber);
        });
        findViewById(R.id.actionMessage).setOnClickListener(v -> sendSms(callNumber));
        findViewById(R.id.actionWhatsApp).setOnClickListener(v ->
                WhatsAppUtil.openChat(this, callNumber));
        findViewById(R.id.actionBlock).setOnClickListener(v -> toggleBlock());
        findViewById(R.id.favorite).setOnClickListener(v -> toggleFavorite());
        paintFavorite();
        paintBlock();

        // Not in the address book yet: "Add contact" instead of "Edit"
        ImageView editIcon = findViewById(R.id.actionEditIcon);
        TextView editLabel = findViewById(R.id.actionEditLabel);
        if (contact != null) {
            editIcon.setImageResource(R.drawable.ic_edit);
            editLabel.setText("Edit");
            findViewById(R.id.actionEdit).setOnClickListener(v ->
                    startActivity(EditContactActivity.intentForEdit(this, contact.id)));
            findViewById(R.id.edit).setVisibility(View.VISIBLE);
            findViewById(R.id.delete).setVisibility(View.VISIBLE);
            findViewById(R.id.edit).setOnClickListener(v ->
                    startActivity(EditContactActivity.intentForEdit(this, contact.id)));
            findViewById(R.id.delete).setOnClickListener(v -> confirmDelete());
        } else {
            editIcon.setImageResource(R.drawable.ic_person_add);
            editLabel.setText("Add contact");
            findViewById(R.id.actionEdit).setOnClickListener(v ->
                    startActivity(EditContactActivity.intentForNew(this, number)));
            findViewById(R.id.edit).setVisibility(View.GONE);
            findViewById(R.id.delete).setVisibility(View.GONE);
        }
    }

    private void bindNumbers() {
        numbersContainer.removeAllViews();
        List<String> numbers = numbers();
        if (numbers.isEmpty()) {
            numbersTitle.setVisibility(View.GONE);
            numbersContainer.setVisibility(View.GONE);
            return;
        }
        numbersTitle.setVisibility(View.VISIBLE);
        numbersContainer.setVisibility(View.VISIBLE);

        LayoutInflater inf = LayoutInflater.from(this);
        for (int i = 0; i < numbers.size(); i++) {
            String n = numbers.get(i);
            View row = inf.inflate(R.layout.item_number_row, numbersContainer, false);
            String label = (contact != null && i < contact.labels.size())
                    ? contact.labels.get(i) : "Phone";
            ((TextView) row.findViewById(R.id.numberLabel)).setText(label.toUpperCase(tr));
            ((TextView) row.findViewById(R.id.numberText)).setText(PhoneUtil.pretty(n));
            row.findViewById(R.id.numberRow).setOnClickListener(v -> Dial.call(this, n));
            row.findViewById(R.id.numberCall).setOnClickListener(v -> Dial.call(this, n));
            row.findViewById(R.id.numberMessage).setOnClickListener(v -> sendSms(n));
            numbersContainer.addView(row);
        }
    }

    private void loadCalls() {
        final List<String> numbers = numbers();
        final int limit = showAllCalls ? ALL_LIMIT : PREVIEW_COUNT + 1;
        io.execute(() -> {
            final List<RecentItem> calls = CallLogRepo.forNumbers(getContentResolver(), numbers, limit);
            ui.post(() -> bindCalls(calls));
        });
    }

    private void bindCalls(List<RecentItem> calls) {
        callsContainer.removeAllViews();

        boolean more = !showAllCalls && calls.size() > PREVIEW_COUNT;
        List<RecentItem> shown = more ? calls.subList(0, PREVIEW_COUNT) : calls;

        if (shown.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("No calls with this person");
            tv.setTextColor(getColor(R.color.text_faint));
            tv.setTextSize(14);
            int p = (int) (18 * getResources().getDisplayMetrics().density);
            tv.setPadding(p, p, p, p);
            callsContainer.addView(tv);
            showAllButton.setVisibility(View.GONE);
            return;
        }

        LayoutInflater inf = LayoutInflater.from(this);
        for (RecentItem r : shown) {
            View row = inf.inflate(R.layout.item_call_row, callsContainer, false);
            ImageView icon = row.findViewById(R.id.callType);
            int res;
            int tint;
            String kind;
            switch (r.type) {
                case CallLog.Calls.INCOMING_TYPE:
                    res = R.drawable.ic_call_received;
                    tint = getColor(R.color.green);
                    kind = "Incoming call";
                    break;
                case CallLog.Calls.OUTGOING_TYPE:
                    res = R.drawable.ic_call_made;
                    tint = getColor(R.color.blue);
                    kind = "Outgoing call";
                    break;
                case CallLog.Calls.REJECTED_TYPE:
                    res = R.drawable.ic_call_missed;
                    tint = getColor(R.color.red);
                    kind = "Rejected";
                    break;
                default:
                    res = R.drawable.ic_call_missed;
                    tint = getColor(R.color.red);
                    kind = "Missed call";
                    break;
            }
            icon.setImageResource(res);
            icon.setColorFilter(tint);
            ((TextView) row.findViewById(R.id.callWhen)).setText(when(r.date));
            ((TextView) row.findViewById(R.id.callKind)).setText(kind);
            ((TextView) row.findViewById(R.id.callDuration))
                    .setText(r.duration > 0 ? duration(r.duration) : "");
            callsContainer.addView(row);
        }
        showAllButton.setVisibility(more ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ actions

    private void confirmDelete() {
        if (contact == null) return;
        new AlertDialog.Builder(this)
                .setTitle("Delete contact")
                .setMessage("Delete " + contact.displayName() + " from your contacts?\n\n"
                        + "(The call history is kept.)")
                .setPositiveButton(R.string.delete, (d, w) -> io.execute(() -> {
                    boolean ok = ContactsRepo.delete(getContentResolver(), contact.id, contact.lookupKey);
                    ui.post(() -> {
                        Toast.makeText(this, ok ? "Contact deleted" : "Could not delete the contact",
                                Toast.LENGTH_SHORT).show();
                        if (ok) finish();
                    });
                }))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void sendSms(String n) {
        if (n == null || n.isEmpty()) return;
        try {
            Intent i = new Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", n, null));
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "No messaging app found", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ helpers

    private List<String> numbers() {
        if (contact != null && !contact.numbers.isEmpty()) return contact.numbers;
        if (number != null && !number.isEmpty()) return Collections.singletonList(number);
        return new ArrayList<>();
    }

    private String primaryNumber() {
        List<String> n = numbers();
        return n.isEmpty() ? null : n.get(0);
    }

    private String when(long ts) {
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(ts);
        if (DateUtils.isToday(ts)) return "Today " + timeFmt.format(new Date(ts));
        if (DateUtils.isToday(ts + DateUtils.DAY_IN_MILLIS)) {
            return "Yesterday " + timeFmt.format(new Date(ts));
        }
        if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)) {
            return dayFmt.format(new Date(ts)) + ", " + timeFmt.format(new Date(ts));
        }
        return yearFmt.format(new Date(ts)) + ", " + timeFmt.format(new Date(ts));
    }

    private String duration(long seconds) {
        long m = seconds / 60, s = seconds % 60;
        if (m == 0) return s + " s";
        return m + " min " + s + " s";
    }
}
