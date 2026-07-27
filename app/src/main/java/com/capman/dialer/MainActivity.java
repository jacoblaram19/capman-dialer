package com.capman.dialer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telecom.TelecomManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.LinearLayout;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends BaseActivity
        implements ContactAdapter.Listener, RecentAdapter.Listener, FavoriteAdapter.Listener {

    private static final int REQ_PERMS = 101;
    private static final int REQ_ROLE = 102;

    private static final int TAB_RECENTS = 0;
    private static final int TAB_CONTACTS = 1;
    private static final int TAB_DIALPAD = 2;

    private EditText search;
    private ImageButton searchClear, addContact, dialBackspace, dialCall, dialSaveContact;
    private RecyclerView listRecents, listContacts, dialMatches, listFavorites;
    private View dialpad, favoritesBar;
    private LinearLayout alphaIndex;
    private View filterBar;
    private TextView empty, dialNumber, alphaPreview, chipAll, chipMissed, clipSuggest, favSave;
    private ImageView favEdit;
    private androidx.recyclerview.widget.ItemTouchHelper favTouchHelper;
    /** Recents filter: show only the missed calls that have not been returned. */
    private boolean missedOnly = false;
    private String clipNumber;

    private ContactAdapter contactAdapter, matchAdapter;
    private RecentAdapter recentAdapter;
    private FavoriteAdapter favoriteAdapter;

    private List<Contact> allContacts = new ArrayList<>();
    private Map<String, String> nameIndex = new HashMap<>();

    private int tab = TAB_RECENTS;
    private boolean recentsShowAll = false;
    private boolean roleAsked = false;
    /** Ask for permissions once per launch, so this cannot loop. */
    private boolean permsAsked = false;
    private final StringBuilder dialed = new StringBuilder();

    private ToneGenerator tone;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        setupLists();
        setupTabs();
        setupSearch();
        setupDialpad();
        setupAlphaIndex();
        setTab(TAB_RECENTS);
        handleIntent(getIntent());
        // Alarms are wiped when the app is updated or force-stopped; re-arm them
        Reminders.rescheduleAll(this);

        // A short tour after a fresh install. Permissions are requested once the
        // tour is done: fired together, the permission dialogs land on top of it.
        if (!Prefs.onboardingSeen(this)) {
            startActivity(OnboardingActivity.intent(this));
        } else if (missingPermissions().isEmpty()) {
            loadData();
        } else {
            permsAsked = true;
            requestPermissions(missingPermissions().toArray(new String[0]), REQ_PERMS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!Prefs.onboardingSeen(this)) return;     // tanitim surerken bekle
        if (missingPermissions().isEmpty()) {
            loadData();
            maybeRequestDefaultDialer();
        } else if (!permsAsked) {
            permsAsked = true;
            requestPermissions(missingPermissions().toArray(new String[0]), REQ_PERMS);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tone != null) tone.release();
        io.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        if (!search.getText().toString().isEmpty()) {
            search.setText("");
            return;
        }
        if (tab != TAB_RECENTS) {
            setTab(TAB_RECENTS);
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ setup

    private void bindViews() {
        search = findViewById(R.id.search);
        searchClear = findViewById(R.id.searchClear);
        addContact = findViewById(R.id.addContact);
        listRecents = findViewById(R.id.listRecents);
        listContacts = findViewById(R.id.listContacts);
        dialpad = findViewById(R.id.dialpad);
        dialMatches = findViewById(R.id.dialMatches);
        dialNumber = findViewById(R.id.dialNumber);
        dialBackspace = findViewById(R.id.dialBackspace);
        dialCall = findViewById(R.id.dialCall);
        dialSaveContact = findViewById(R.id.dialSaveContact);
        empty = findViewById(R.id.empty);
        favoritesBar = findViewById(R.id.favoritesBar);
        listFavorites = findViewById(R.id.listFavorites);
        alphaIndex = findViewById(R.id.alphaIndex);
        alphaPreview = findViewById(R.id.alphaPreview);
        filterBar = findViewById(R.id.filterBar);
        chipAll = findViewById(R.id.chipAll);
        chipMissed = findViewById(R.id.chipMissed);
        clipSuggest = findViewById(R.id.clipSuggest);
        favEdit = findViewById(R.id.favEdit);
        favSave = findViewById(R.id.favSave);

        chipAll.setOnClickListener(v -> setMissedOnly(false));
        chipMissed.setOnClickListener(v -> setMissedOnly(true));
        clipSuggest.setOnClickListener(v -> {
            if (clipNumber == null) return;
            dialed.setLength(0);
            dialed.append(clipNumber);
            onDialedChanged();
            clipSuggest.setVisibility(View.GONE);
        });
        paintChips();
    }

    // ------------------------------------------------------------------ favorite order

    /**
     * Reordering the favorites. A long press normally opens the menu, so to
     * avoid a clash you first enter edit mode with the pencil under the star;
     * inside that mode a long press starts the drag instead.
     */
    private void setupFavoriteReorder() {
        favEdit.setOnClickListener(v -> setFavEditMode(!favoriteAdapter.isEditMode()));
        favSave.setOnClickListener(v -> {
            Prefs.setFavOrder(this, favoriteAdapter.orderCsv());
            setFavEditMode(false);
            Toast.makeText(this, "Order saved", Toast.LENGTH_SHORT).show();
        });

        ItemTouchHelper.Callback cb = new ItemTouchHelper.Callback() {
            @Override
            public int getMovementFlags(RecyclerView rv, RecyclerView.ViewHolder vh) {
                if (!favoriteAdapter.isDraggable(vh.getBindingAdapterPosition())) return 0;
                return makeMovementFlags(ItemTouchHelper.START | ItemTouchHelper.END, 0);
            }

            @Override
            public boolean onMove(RecyclerView rv, RecyclerView.ViewHolder from,
                                  RecyclerView.ViewHolder to) {
                int t = to.getBindingAdapterPosition();
                if (!favoriteAdapter.isDraggable(t)) return false;   // "+" yerinde kalsin
                return favoriteAdapter.move(from.getBindingAdapterPosition(), t);
            }

            @Override
            public void onSwiped(RecyclerView.ViewHolder vh, int direction) {
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return false;      // suruklemeyi biz baslatiyoruz
            }

            @Override
            public void onSelectedChanged(RecyclerView.ViewHolder vh, int actionState) {
                super.onSelectedChanged(vh, actionState);
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && vh != null) {
                    vh.itemView.setScaleX(1.12f);
                    vh.itemView.setScaleY(1.12f);
                }
            }

            @Override
            public void clearView(RecyclerView rv, RecyclerView.ViewHolder vh) {
                super.clearView(rv, vh);
                vh.itemView.setScaleX(1f);
                vh.itemView.setScaleY(1f);
            }
        };
        favTouchHelper = new ItemTouchHelper(cb);
        favTouchHelper.attachToRecyclerView(listFavorites);
    }

    private void setFavEditMode(boolean on) {
        favoriteAdapter.setEditMode(on);
        favSave.setVisibility(on ? View.VISIBLE : View.GONE);
        favEdit.setColorFilter(getColor(on ? R.color.gold : R.color.text_faint));
        if (on) {
            Toast.makeText(this, "Long-press and drag to reorder",
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onFavoriteDragStart(RecyclerView.ViewHolder holder) {
        if (favTouchHelper != null) favTouchHelper.startDrag(holder);
    }

    // ------------------------------------------------------------------ recents filter

    private void setMissedOnly(boolean on) {
        if (missedOnly == on) return;
        missedOnly = on;
        paintChips();
        loadData();
    }

    private void paintChips() {
        paintChip(chipAll, !missedOnly);
        paintChip(chipMissed, missedOnly);
    }

    private void paintChip(TextView chip, boolean active) {
        // The active one gets the gold outline and the other fades, so the state is obvious
        chip.setBackgroundResource(active ? R.drawable.bg_pill : R.drawable.bg_tab_active);
        chip.setTextColor(getColor(active ? R.color.gold : R.color.text_faint));
    }

    // ------------------------------------------------------------------ pano onerisi

    /**
     * If the clipboard holds a phone number, offer it on the keypad as a paste
     * suggestion. Only checked while the keypad tab is open - reading the
     * clipboard raises a system notice, so we do not poke at it needlessly.
     */
    private void checkClipboard() {
        if (tab != TAB_DIALPAD) {
            clipSuggest.setVisibility(View.GONE);
            return;
        }
        clipNumber = clipboardNumber();
        boolean show = clipNumber != null && !clipNumber.equals(dialed.toString());
        clipSuggest.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) clipSuggest.setText("Paste from clipboard: " + PhoneUtil.pretty(clipNumber));
    }

    private String clipboardNumber() {
        try {
            android.content.ClipboardManager cm =
                    getSystemService(android.content.ClipboardManager.class);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            android.content.ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;
            CharSequence text = clip.getItemAt(0).coerceToText(this);
            if (text == null) return null;

            String raw = text.toString().trim();
            if (raw.length() > 40) return null;
            // strip spaces, dashes and brackets; keep digits and a leading +
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (c >= '0' && c <= '9') sb.append(c);
                else if (c == '+' && sb.length() == 0) sb.append(c);
                else if (c == ' ' || c == '-' || c == '(' || c == ')' || c == '/') continue;
                else return null;                       // numara disi karakter varsa vazgec
            }
            String out = sb.toString();
            String digits = PhoneUtil.digitsOnly(out);
            return (digits.length() >= 7 && digits.length() <= 15) ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void setupLists() {
        contactAdapter = new ContactAdapter(this);
        listContacts.setLayoutManager(new LinearLayoutManager(this));
        listContacts.setAdapter(contactAdapter);

        recentAdapter = new RecentAdapter(this);
        listRecents.setLayoutManager(new LinearLayoutManager(this));
        listRecents.setAdapter(recentAdapter);

        matchAdapter = new ContactAdapter(this);
        dialMatches.setLayoutManager(new LinearLayoutManager(this));
        dialMatches.setAdapter(matchAdapter);

        favoriteAdapter = new FavoriteAdapter(this);
        listFavorites.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        listFavorites.setAdapter(favoriteAdapter);
        setupFavoriteReorder();

        addContact.setOnClickListener(v -> startActivity(
                EditContactActivity.intentForNew(this, null)));
        findViewById(R.id.settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
    }

    private void setupTabs() {
        findViewById(R.id.tabRecents).setOnClickListener(v -> setTab(TAB_RECENTS));
        findViewById(R.id.tabContacts).setOnClickListener(v -> setTab(TAB_CONTACTS));
        findViewById(R.id.tabDialpad).setOnClickListener(v -> setTab(TAB_DIALPAD));
    }

    private void setupSearch() {
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                applySearch(s.toString());
            }
        });
        searchClear.setOnClickListener(v -> {
            search.setText("");
            hideKeyboard();
        });
    }

    private void setupDialpad() {
        key(R.id.key1, '1');
        key(R.id.key2, '2');
        key(R.id.key3, '3');
        key(R.id.key4, '4');
        key(R.id.key5, '5');
        key(R.id.key6, '6');
        key(R.id.key7, '7');
        key(R.id.key8, '8');
        key(R.id.key9, '9');
        key(R.id.key0, '0');
        key(R.id.keyStar, '*');
        key(R.id.keyHash, '#');

        // A long press on 0 types "+"
        findViewById(R.id.key0).setOnLongClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            dialed.append('+');
            onDialedChanged();
            return true;
        });

        dialBackspace.setOnClickListener(v -> {
            if (dialed.length() > 0) {
                dialed.deleteCharAt(dialed.length() - 1);
                onDialedChanged();
            }
        });
        dialBackspace.setOnLongClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            dialed.setLength(0);
            onDialedChanged();
            return true;
        });

        dialCall.setOnClickListener(v -> {
            if (dialed.length() == 0) {
                // An empty "call": bring back the last dialled number, the classic behaviour
                io.execute(() -> {
                    String last = CallLogRepo.lastOutgoing(getContentResolver());
                    ui.post(() -> {
                        if (last != null) {
                            dialed.setLength(0);
                            dialed.append(last);
                            onDialedChanged();
                        }
                    });
                });
                return;
            }
            Dial.call(this, dialed.toString());
        });

        dialSaveContact.setOnClickListener(v ->
                startActivity(EditContactActivity.intentForNew(this, dialed.toString())));

        onDialedChanged();
    }

    private void key(int id, char c) {
        findViewById(id).setOnClickListener(v -> {
            if (Prefs.keyFeedback(this)) {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                playTone(c);
            }
            dialed.append(c);
            onDialedChanged();
        });
    }

    // ------------------------------------------------------------------ tabs

    private void setTab(int which) {
        tab = which;
        boolean searching = !search.getText().toString().trim().isEmpty();
        if (!searching) {
            listRecents.setVisibility(which == TAB_RECENTS ? View.VISIBLE : View.GONE);
            listContacts.setVisibility(which == TAB_CONTACTS ? View.VISIBLE : View.GONE);
            dialpad.setVisibility(which == TAB_DIALPAD ? View.VISIBLE : View.GONE);
        }
        paintTab(R.id.tabRecentsIcon, R.id.tabRecentsLabel, which == TAB_RECENTS);
        paintTab(R.id.tabContactsIcon, R.id.tabContactsLabel, which == TAB_CONTACTS);
        paintTab(R.id.tabDialpadIcon, R.id.tabDialpadLabel, which == TAB_DIALPAD);
        if (which == TAB_DIALPAD) hideKeyboard();
        updateFavoritesBar();
        // The clipboard is only read on a tab change: reading it on every
        // keystroke is slow and keeps triggering the system's "pasted from
        // clipboard" notice
        checkClipboard();
        updateEmpty();
    }

    /** The favorites strip and the filter only appear on the recents tab, when not searching. */
    private void updateFavoritesBar() {
        boolean searching = !search.getText().toString().trim().isEmpty();
        boolean onRecents = tab == TAB_RECENTS && !searching;
        favoritesBar.setVisibility(onRecents ? View.VISIBLE : View.GONE);
        filterBar.setVisibility(onRecents ? View.VISIBLE : View.GONE);
        alphaIndex.setVisibility(tab == TAB_CONTACTS && !searching && !allContacts.isEmpty()
                ? View.VISIBLE : View.GONE);
        if (alphaIndex.getVisibility() != View.VISIBLE) alphaPreview.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------------ alphabet strip

    /** A-Z; anything not starting with a letter lands in the "#" section at the end. */
    private static final String[] ALPHA = {
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
            "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "#"};

    private void setupAlphaIndex() {
        alphaIndex.removeAllViews();
        for (String letter : ALPHA) {
            TextView tv = new TextView(this);
            tv.setText(letter);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
            tv.setTextColor(getColor(R.color.text_faint));
            tv.setGravity(Gravity.CENTER);
            alphaIndex.addView(tv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        // Dragging a finger along the strip works as well as tapping single letters
        alphaIndex.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.performClick();
                alphaPreview.setVisibility(View.GONE);
                return true;
            }
            int height = alphaIndex.getHeight();
            if (height <= 0) return true;
            int index = (int) (event.getY() / height * ALPHA.length);
            index = Math.max(0, Math.min(ALPHA.length - 1, index));

            alphaPreview.setText(ALPHA[index]);
            alphaPreview.setVisibility(View.VISIBLE);
            if (action == MotionEvent.ACTION_DOWN && Prefs.keyFeedback(this)) {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
            jumpToSection(index);
            return true;
        });
    }

    /** Jumps to a letter, or to the nearest one that actually has contacts. */
    private void jumpToSection(int index) {
        for (int i = index; i < ALPHA.length; i++) {
            if (scrollToSection(ALPHA[i])) return;
        }
        for (int i = index - 1; i >= 0; i--) {
            if (scrollToSection(ALPHA[i])) return;
        }
    }

    private boolean scrollToSection(String letter) {
        int position = contactAdapter.positionOfSection(letter);
        if (position < 0) return false;
        RecyclerView.LayoutManager lm = listContacts.getLayoutManager();
        if (lm instanceof LinearLayoutManager) {
            ((LinearLayoutManager) lm).scrollToPositionWithOffset(position, 0);
        }
        return true;
    }

    private void paintTab(int iconId, int labelId, boolean active) {
        int color = getColor(active ? R.color.gold : R.color.text_faint);
        ((ImageView) findViewById(iconId)).setColorFilter(color);
        ((TextView) findViewById(labelId)).setTextColor(color);
    }

    // ------------------------------------------------------------------ data

    private void loadData() {
        io.execute(() -> {
            final List<Contact> contacts = ContactsRepo.loadAll(getContentResolver());
            final Map<String, String> idx = ContactsRepo.nameIndex(contacts);
            final Map<String, String> photos = ContactsRepo.photoIndex(contacts);
            final List<Contact> favorites = ContactsRepo.favorites(contacts, Prefs.favOrder(MainActivity.this));

            // Unreturned missed calls, for both the list and the counter on the chip
            final List<RecentItem> pending = CallLogRepo.missedPending(getContentResolver(),
                    Prefs.historyDays(MainActivity.this));

            final List<RecentItem> rows;
            final boolean hasMore;
            if (missedOnly) {
                rows = pending;
                hasMore = false;
            } else {
                CallLogRepo.Page page = CallLogRepo.load(getContentResolver(),
                        recentsShowAll, Prefs.historyDays(MainActivity.this));
                rows = page.items;
                hasMore = page.hasMore;
            }
            final int pendingCount = pending.size();
            ui.post(() -> {
                allContacts = contacts;
                nameIndex = idx;
                recentAdapter.submit(rows, idx, photos, hasMore);
                // Refreshing the list mid-edit would wipe the order the user is building
                if (!favoriteAdapter.isEditMode()) favoriteAdapter.submit(favorites);
                chipMissed.setText(pendingCount > 0 ? "Missed " + pendingCount : "Missed");
                applySearch(search.getText().toString());
                updateDialMatches();
                updateEmpty();
            });
        });
    }

    private void applySearch(String q) {
        boolean searching = !q.trim().isEmpty();
        searchClear.setVisibility(searching ? View.VISIBLE : View.GONE);

        if (searching) {
            // Whatever tab we are on, typing brings up the matching contacts
            listRecents.setVisibility(View.GONE);
            dialpad.setVisibility(View.GONE);
            listContacts.setVisibility(View.VISIBLE);
            contactAdapter.submit(filter(q), false);
        } else {
            contactAdapter.submit(allContacts, true);
            listRecents.setVisibility(tab == TAB_RECENTS ? View.VISIBLE : View.GONE);
            listContacts.setVisibility(tab == TAB_CONTACTS ? View.VISIBLE : View.GONE);
            dialpad.setVisibility(tab == TAB_DIALPAD ? View.VISIBLE : View.GONE);
        }
        updateFavoritesBar();
        updateEmpty();
    }

    private List<Contact> filter(String q) {
        String lq = PhoneUtil.lowerCase(q.trim());
        String dq = PhoneUtil.digitsOnly(q);
        List<Contact> out = new ArrayList<>();
        for (Contact c : allContacts) {
            boolean hit = c.name != null && PhoneUtil.lowerCase(c.name).contains(lq);
            if (!hit && !dq.isEmpty()) {
                for (String n : c.numbers) {
                    if (PhoneUtil.digitsOnly(n).contains(dq)) {
                        hit = true;
                        break;
                    }
                }
            }
            if (hit) out.add(c);
        }
        return out;
    }

    private void onDialedChanged() {
        String s = dialed.toString();
        dialNumber.setText(PhoneUtil.pretty(s));
        dialBackspace.setVisibility(s.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        dialSaveContact.setVisibility(s.length() >= 3 ? View.VISIBLE : View.INVISIBLE);
        updateDialMatches();
    }

    private void updateDialMatches() {
        String dq = PhoneUtil.digitsOnly(dialed.toString());
        if (dq.length() < 2) {
            matchAdapter.submit(new ArrayList<>(), false);
            return;
        }
        List<Contact> out = new ArrayList<>();
        for (Contact c : allContacts) {
            for (String n : c.numbers) {
                if (PhoneUtil.digitsOnly(n).contains(dq)) {
                    out.add(c);
                    break;
                }
            }
            if (out.size() >= 20) break;
        }
        matchAdapter.submit(out, false);
    }

    private void updateEmpty() {
        boolean searching = !search.getText().toString().trim().isEmpty();
        String msg = null;
        if (searching) {
            if (contactAdapter.getItemCount() == 0) msg = "No matching contacts";
        } else if (tab == TAB_CONTACTS && allContacts.isEmpty()) {
            msg = getString(R.string.no_contacts);
        } else if (tab == TAB_RECENTS && recentAdapter.getItemCount() == 0) {
            msg = missedOnly
                    ? "No missed calls to return \uD83D\uDC4C"
                    : getString(R.string.no_recents);
        }
        empty.setText(msg == null ? "" : msg);
        empty.setVisibility(msg == null ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------------ list events

    @Override
    public void onCall(Contact c) {
        if (c.numbers.isEmpty()) return;
        if (c.numbers.size() == 1) {
            Dial.call(this, c.numbers.get(0));
            return;
        }
        CharSequence[] opts = new CharSequence[c.numbers.size()];
        for (int i = 0; i < c.numbers.size(); i++) {
            String label = i < c.labels.size() ? c.labels.get(i) : "";
            opts[i] = label + "  " + PhoneUtil.pretty(c.numbers.get(i));
        }
        new AlertDialog.Builder(this)
                .setTitle(c.displayName())
                .setItems(opts, (d, which) -> Dial.call(this, c.numbers.get(which)))
                .show();
    }

    @Override
    public void onInfo(Contact c) {
        startActivity(ContactDetailActivity.intentFor(this, c.id, c.primaryNumber()));
    }

    @Override
    public void onCall(RecentItem r) {
        if (r.number.isEmpty()) {
            Toast.makeText(this, "Number withheld", Toast.LENGTH_SHORT).show();
            return;
        }
        Dial.call(this, r.number);
    }

    @Override
    public void onInfo(RecentItem r) {
        long contactId = -1;
        for (Contact c : allContacts) {
            for (String n : c.numbers) {
                if (PhoneUtil.sameNumber(n, r.number)) {
                    contactId = c.id;
                    break;
                }
            }
            if (contactId > 0) break;
        }
        startActivity(ContactDetailActivity.intentFor(this, contactId, r.number));
    }

    @Override
    public void onWhatsApp(RecentItem r) {
        if (r.number.isEmpty()) {
            Toast.makeText(this, "Number withheld", Toast.LENGTH_SHORT).show();
            return;
        }
        WhatsAppUtil.openChat(this, r.number);
    }

    @Override
    public void onShowMore() {
        recentsShowAll = true;
        loadData();
        Toast.makeText(this, "Loading the full call history…", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ favorites

    @Override
    public void onFavoriteCall(Contact c) {
        onCall(c);
    }

    @Override
    public void onFavoriteMenu(Contact c) {
        CharSequence[] options = {"Send a message", "Remove from favorites", "Open contact details"};
        new AlertDialog.Builder(this)
                .setTitle(c.displayName())
                .setItems(options, (d, which) -> {
                    switch (which) {
                        case 0:
                            sendSms(c.primaryNumber());
                            break;
                        case 1:
                            setStarred(c, false);
                            break;
                        default:
                            onInfo(c);
                            break;
                    }
                })
                .show();
    }

    @Override
    public void onFavoriteAdd() {
        final List<Contact> candidates = new ArrayList<>();
        for (Contact c : allContacts) {
            if (!c.starred) candidates.add(c);
        }
        if (candidates.isEmpty()) {
            Toast.makeText(this, allContacts.isEmpty()
                            ? "No contacts yet" : "Everyone is already a favorite",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        ContactPicker.show(this, "Add to favorites", candidates, c -> setStarred(c, true));
    }

    private void setStarred(Contact c, boolean starred) {
        io.execute(() -> {
            final boolean ok = ContactsRepo.setStarred(getContentResolver(), c.id, starred);
            ui.post(() -> {
                if (!ok) {
                    Toast.makeText(this, "Could not change the favorite", Toast.LENGTH_SHORT).show();
                    return;
                }
                Toast.makeText(this, starred ? "Added to favorites" : "Removed from favorites",
                        Toast.LENGTH_SHORT).show();
                loadData();
            });
        });
    }

    private void sendSms(String number) {
        if (number == null || number.isEmpty()) {
            Toast.makeText(this, "No number", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_SENDTO,
                    Uri.fromParts("smsto", number, null)));
        } catch (Exception e) {
            Toast.makeText(this, "No messaging app found", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ permissions / role

    private List<String> missingPermissions() {
        List<String> need = new ArrayList<>();
        String[] perms = {
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.WRITE_CONTACTS,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.ANSWER_PHONE_CALLS,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.SEND_SMS,
        };
        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return need;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_PERMS) return;
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            loadData();
        } else {
            Toast.makeText(this, "Contacts permission is needed to show the address book",
                    Toast.LENGTH_LONG).show();
        }
        maybeRequestDefaultDialer();
    }

    /** Asks to become the default phone app, which the call screen depends on. */
    private void maybeRequestDefaultDialer() {
        if (roleAsked) return;
        if (isDefaultDialer()) return;
        roleAsked = true;
        new AlertDialog.Builder(this)
                .setTitle("Default phone app")
                .setMessage("To show incoming calls and manage them, this app has to be "
                        + "your default phone app.")
                .setPositiveButton("Ayarla", (d, w) -> requestDefaultDialer())
                .setNegativeButton("Not now", null)
                .show();
    }

    private boolean isDefaultDialer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager rm = getSystemService(RoleManager.class);
            return rm != null && rm.isRoleHeld(RoleManager.ROLE_DIALER);
        }
        TelecomManager tm = getSystemService(TelecomManager.class);
        return tm != null && getPackageName().equals(tm.getDefaultDialerPackage());
    }

    private void requestDefaultDialer() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                RoleManager rm = getSystemService(RoleManager.class);
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER), REQ_ROLE);
                    return;
                }
            }
            Intent i = new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER);
            i.putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, getPackageName());
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "You have to pick the default phone app in the system settings",
                    Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ helpers

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        // When another app says "call this number" (ACTION_CALL) we place the
        // call straight away; DIAL/VIEW only fills in the keypad.
        if (Intent.ACTION_CALL.equals(action)) {
            Uri data = intent.getData();
            String n = data != null && "tel".equals(data.getScheme())
                    ? Uri.decode(data.getSchemeSpecificPart()) : null;
            if (n != null && !n.trim().isEmpty()) {
                Dial.call(this, n);
            } else {
                setTab(TAB_DIALPAD);
            }
        } else if (Intent.ACTION_DIAL.equals(action) || Intent.ACTION_VIEW.equals(action)) {
            Uri data = intent.getData();
            if (data != null && "tel".equals(data.getScheme())) {
                String n = Uri.decode(data.getSchemeSpecificPart());
                dialed.setLength(0);
                dialed.append(n);
                onDialedChanged();
                setTab(TAB_DIALPAD);
            } else {
                setTab(TAB_DIALPAD);
            }
        } else if (Intent.ACTION_CALL_BUTTON.equals(action)) {
            setTab(TAB_DIALPAD);
        }
    }

    private void playTone(char c) {
        try {
            if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_DTMF, 55);
            int t;
            switch (c) {
                case '0': t = ToneGenerator.TONE_DTMF_0; break;
                case '1': t = ToneGenerator.TONE_DTMF_1; break;
                case '2': t = ToneGenerator.TONE_DTMF_2; break;
                case '3': t = ToneGenerator.TONE_DTMF_3; break;
                case '4': t = ToneGenerator.TONE_DTMF_4; break;
                case '5': t = ToneGenerator.TONE_DTMF_5; break;
                case '6': t = ToneGenerator.TONE_DTMF_6; break;
                case '7': t = ToneGenerator.TONE_DTMF_7; break;
                case '8': t = ToneGenerator.TONE_DTMF_8; break;
                case '9': t = ToneGenerator.TONE_DTMF_9; break;
                case '*': t = ToneGenerator.TONE_DTMF_S; break;
                case '#': t = ToneGenerator.TONE_DTMF_P; break;
                default: return;
            }
            tone.startTone(t, 120);
        } catch (Exception ignored) {
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
    }
}
