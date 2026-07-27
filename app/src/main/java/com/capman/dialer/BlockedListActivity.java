package com.capman.dialer;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Managing blocked numbers (Settings > Blocked numbers). */
public class BlockedListActivity extends BaseActivity {

    private LinearLayout container;
    private TextView hint;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    /** Number to name, so the list shows who was blocked. */
    private Map<String, String> nameIndex = new HashMap<>();
    private List<Contact> allContacts = new ArrayList<>();

    public static Intent intent(Context ctx) {
        return new Intent(ctx, BlockedListActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_blocked);

        container = findViewById(R.id.blockedContainer);
        hint = findViewById(R.id.blockedHint);
        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.addBlocked).setOnClickListener(v -> askHowToAdd());

        io.execute(() -> {
            final List<Contact> contacts = ContactsRepo.loadAll(getContentResolver());
            final Map<String, String> idx = ContactsRepo.nameIndex(contacts);
            ui.post(() -> {
                allContacts = contacts;
                nameIndex = idx;
                load();
            });
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    // ------------------------------------------------------------------ the list

    private void load() {
        if (!BlockRepo.canBlock(this)) {
            hint.setText("Number blocking is not available on this device. "
                    + "The app has to be the default phone app.");
            container.removeAllViews();
            return;
        }
        List<BlockRepo.Entry> entries = BlockRepo.list(this);
        container.removeAllViews();

        if (entries.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("No blocked numbers");
            tv.setTextColor(getColor(R.color.text_faint));
            tv.setTextSize(14);
            int p = (int) (18 * getResources().getDisplayMetrics().density);
            tv.setPadding(p, p, p, p);
            container.addView(tv);
            return;
        }

        LayoutInflater inf = LayoutInflater.from(this);
        for (BlockRepo.Entry e : entries) {
            View row = inf.inflate(R.layout.item_blocked, container, false);
            String name = nameIndex.get(PhoneUtil.key(e.number));
            ((TextView) row.findViewById(R.id.blockedName))
                    .setText(name != null ? name : PhoneUtil.pretty(e.number));
            TextView num = row.findViewById(R.id.blockedNumber);
            if (name != null) {
                num.setVisibility(View.VISIBLE);
                num.setText(PhoneUtil.pretty(e.number));
            } else {
                num.setVisibility(View.GONE);
            }
            row.findViewById(R.id.unblock).setOnClickListener(v -> unblock(e.number));
            container.addView(row);
        }
    }

    private void unblock(String number) {
        boolean ok = BlockRepo.unblock(this, number);
        Toast.makeText(this, ok ? "Unblocked" : "Could not unblock",
                Toast.LENGTH_SHORT).show();
        load();
    }

    /** No need to memorise a number: it can be picked from the contacts too. */
    private void askHowToAdd() {
        if (!BlockRepo.canBlock(this)) {
            Toast.makeText(this, "Number blocking is not available", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Who should be blocked?")
                .setItems(new CharSequence[]{"Pick from contacts", "Type a number"}, (d, which) -> {
                    if (which == 0) pickFromContacts();
                    else askNumber();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void pickFromContacts() {
        if (allContacts.isEmpty()) {
            Toast.makeText(this, "Contacts are still loading", Toast.LENGTH_SHORT).show();
            return;
        }
        ContactPicker.show(this, "Contact to block", allContacts, this::blockContact);
    }

    /** Blocks every number of a contact, so they cannot call from a second line. */
    private void blockContact(Contact c) {
        int ok = 0;
        for (String n : c.numbers) {
            if (BlockRepo.block(this, n)) ok++;
        }
        Toast.makeText(this, ok > 0
                        ? c.displayName() + " blocked" + (ok > 1 ? " (" + ok + " numara)" : "")
                        : "Could not block",
                Toast.LENGTH_SHORT).show();
        load();
    }

    private void askNumber() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_PHONE);
        input.setHint(R.string.number_hint);
        int p = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(p, p / 2, p, p / 2);
        new AlertDialog.Builder(this)
                .setTitle("Block a number")
                .setView(input)
                .setPositiveButton("Block", (d, w) -> {
                    String n = input.getText().toString().trim();
                    if (n.isEmpty()) return;
                    boolean ok = BlockRepo.block(this, n);
                    Toast.makeText(this, ok ? "Number blocked" : "Could not block",
                            Toast.LENGTH_SHORT).show();
                    load();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
