package com.capman.dialer;

import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telecom.TelecomManager;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends BaseActivity {

    private static final int REQ_EXPORT = 201;
    private static final int REQ_IMPORT = 202;
    private static final int REQ_ROLE = 203;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private List<ContactAccount> accounts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        findViewById(R.id.back).setOnClickListener(v -> finish());

        io.execute(() -> {
            final List<ContactAccount> list = AccountsRepo.writableAccounts(this);
            ui.post(() -> {
                accounts = list;
                bindAll();
            });
        });
        bindAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        bindAll();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    // ------------------------------------------------------------------ rows

    private void bindAll() {
        row(R.id.rowTheme, "Appearance", themeLabel(), this::chooseTheme);
        row(R.id.rowAppIcon, "App icon", AppIcons.current(this).label,
                this::chooseAppIcon);

        row(R.id.rowDefaultAccount, "Default save location", defaultAccountLabel(),
                this::chooseDefaultAccount);
        row(R.id.rowExport, "Export contacts",
                "Back every contact up to a .vcf file", this::startExport);
        row(R.id.rowImport, "Import contacts",
                "Add contacts from a .vcf file", this::startImport);

        row(R.id.rowHistory, "Call history window", historyLabel(), this::chooseHistory);
        row(R.id.rowQuickReply, "Quick reply message", Prefs.quickReply(this), this::editQuickReply);

        toggle(R.id.rowPowerDouble, "Double-press the power key",
                "Rejects a call / hangs up the current one", Prefs.POWER_DOUBLE, true);
        toggle(R.id.rowVolumeSilence, "Volume key silences the ringer",
                "The call is not rejected, only the ringer stops", Prefs.VOLUME_SILENCE, true);
        row(R.id.rowIncomingStyle, "Incoming call style", incomingStyleLabel(),
                this::chooseIncomingStyle);
        toggle(R.id.rowPowerSilence, "Power key silences the ringer",
                "One press while ringing: the ringer stops and the screen goes off",
                Prefs.POWER_SILENCE, true);
        toggle(R.id.rowEarAnswer, "Raise to ear to answer",
                "Lift the ringing phone to your ear and it answers. Off while pocketed",
                Prefs.EAR_ANSWER, true);
        toggle(R.id.rowFlipSilence, "Flip to silence",
                "Turn the screen face down while ringing. Does nothing if it already is",
                Prefs.FLIP_SILENCE, true);
        toggle(R.id.rowProximity, "Blank the screen during a call",
                "Stops your cheek pressing the screen while the phone is at your ear",
                Prefs.PROXIMITY_OFF, true);
        toggle(R.id.rowRecordSpeaker, "Speaker on while recording",
                "The only way the other party can be recorded", Prefs.RECORD_SPEAKER, true);
        toggle(R.id.rowKeyFeedback, "Key tone and vibration", null, Prefs.KEY_FEEDBACK, true);
        row(R.id.rowBlocked, "Blocked numbers", blockedLabel(),
                () -> startActivity(BlockedListActivity.intent(this)));
        row(R.id.rowReminders, "Pending reminders", remindersLabel(), this::showReminders);
        row(R.id.rowPreview, "Try the incoming call screen",
                "Practise the chomper swipe without a real call",
                () -> startActivity(InCallActivity.previewIntent(this)));

        row(R.id.rowDefaultDialer, "Default phone app",
                isDefaultDialer() ? "This app" : "Another app — tap to change",
                this::requestDefaultDialer);
        row(R.id.rowTour, "Show the tour again",
                "A short walkthrough of the app", () -> {
                    Prefs.setOnboardingSeen(this, false);
                    startActivity(OnboardingActivity.intent(this));
                });
        row(R.id.rowAbout, "About", "Cap-Man Dialer " + versionName(), this::showAbout);
    }

    private void row(int id, String title, String value, Runnable onClick) {
        View v = findViewById(id);
        ((TextView) v.findViewById(R.id.settingTitle)).setText(title);
        TextView val = v.findViewById(R.id.settingValue);
        if (value == null) {
            val.setVisibility(View.GONE);
        } else {
            val.setVisibility(View.VISIBLE);
            val.setText(value);
        }
        v.findViewById(R.id.settingSwitch).setVisibility(View.GONE);
        v.setOnClickListener(x -> onClick.run());
    }

    private void toggle(int id, String title, String subtitle, String key, boolean def) {
        View v = findViewById(id);
        ((TextView) v.findViewById(R.id.settingTitle)).setText(title);
        TextView val = v.findViewById(R.id.settingValue);
        if (subtitle == null) {
            val.setVisibility(View.GONE);
        } else {
            val.setVisibility(View.VISIBLE);
            val.setText(subtitle);
        }
        Switch sw = v.findViewById(R.id.settingSwitch);
        sw.setVisibility(View.VISIBLE);
        sw.setChecked(Prefs.get(this).getBoolean(key, def));
        v.setOnClickListener(x -> {
            boolean next = !sw.isChecked();
            sw.setChecked(next);
            Prefs.get(this).edit().putBoolean(key, next).apply();
        });
    }

    // ------------------------------------------------------------------ appearance

    private String themeLabel() {
        switch (Prefs.theme(this)) {
            case Prefs.THEME_LIGHT: return "Light";
            case Prefs.THEME_SYSTEM: return "Follow the system";
            default: return "Dark";
        }
    }

    private void chooseTheme() {
        final String[] keys = {Prefs.THEME_DARK, Prefs.THEME_LIGHT, Prefs.THEME_SYSTEM};
        CharSequence[] labels = {"Dark", "Light", "Follow the system"};
        int checked = 0;
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(Prefs.theme(this))) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Appearance")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    Prefs.get(this).edit().putString(Prefs.THEME, keys[which]).apply();
                    d.dismiss();
                    recreate();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ contacts

    private String defaultAccountLabel() {
        if (accounts == null) return "…";
        ContactAccount a = AccountsRepo.lastUsed(this, accounts);
        return a == null ? "Device storage" : a.label;
    }

    private void chooseDefaultAccount() {
        if (accounts == null || accounts.isEmpty()) return;
        CharSequence[] labels = new CharSequence[accounts.size()];
        ContactAccount current = AccountsRepo.lastUsed(this, accounts);
        int checked = 0;
        for (int i = 0; i < accounts.size(); i++) {
            labels[i] = accounts.get(i).label;
            if (accounts.get(i).equals(current)) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Where should new contacts be saved?")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    AccountsRepo.remember(this, accounts.get(which));
                    d.dismiss();
                    bindAll();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void startExport() {
        String stamp = new SimpleDateFormat("yyyyMMdd", Locale.US)
                .format(new Date());
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/x-vcard");
        i.putExtra(Intent.EXTRA_TITLE, "contacts-" + stamp + ".vcf");
        startActivityForResult(i, REQ_EXPORT);
    }

    private void startImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_IMPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_EXPORT) doExport(uri);
        else if (requestCode == REQ_IMPORT) askImportAccount(uri);
    }

    private void doExport(Uri uri) {
        Toast.makeText(this, "Exporting…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            int written = 0;
            String error = null;
            try {
                List<Contact> contacts = ContactsRepo.loadAll(getContentResolver());
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IllegalStateException("could not open the file");
                    written = VCard.export(getContentResolver(), contacts, out, null);
                }
            } catch (Exception e) {
                error = e.getMessage();
            }
            final int count = written;
            final String err = error;
            ui.post(() -> Toast.makeText(this,
                    err == null ? (count + " contacts exported")
                            : ("Export failed: " + err),
                    Toast.LENGTH_LONG).show());
        });
    }

    private void askImportAccount(Uri uri) {
        if (accounts == null || accounts.isEmpty()) {
            doImport(uri, AccountsRepo.deviceStorage());
            return;
        }
        CharSequence[] labels = new CharSequence[accounts.size()];
        for (int i = 0; i < accounts.size(); i++) labels[i] = accounts.get(i).label;
        new AlertDialog.Builder(this)
                .setTitle("Where should the contacts go?")
                .setItems(labels, (d, which) -> doImport(uri, accounts.get(which)))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void doImport(Uri uri, ContactAccount account) {
        Toast.makeText(this, "Importing…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            int added = 0;
            String error = null;
            try {
                List<VCard.Entry> entries;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IllegalStateException("could not open the file");
                    entries = VCard.parse(in);
                }
                added = VCard.importEntries(getContentResolver(), entries, account, null);
            } catch (Exception e) {
                error = e.getMessage();
            }
            final int count = added;
            final String err = error;
            ui.post(() -> Toast.makeText(this,
                    err == null ? (count + " contacts added") : ("Import failed: " + err),
                    Toast.LENGTH_LONG).show());
        });
    }

    // ------------------------------------------------------------------ calls

    private String historyLabel() {
        int days = Prefs.historyDays(this);
        if (days <= 0) return "Everything";
        if (days >= 365) return "Last year";
        return "Last 6 months";
    }

    private void chooseHistory() {
        final int[] values = {182, 365, 0};
        CharSequence[] labels = {"Last 6 months", "Last year", "Everything"};
        int checked = 0;
        int current = Prefs.historyDays(this);
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("How much history should be shown?")
                .setMessage("This only affects the view; older entries are never deleted.")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    Prefs.get(this).edit().putInt(Prefs.HISTORY_DAYS, values[which]).apply();
                    d.dismiss();
                    bindAll();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private String incomingStyleLabel() {
        return Prefs.incomingFullScreen(this) ? "Full screen" : "Banner at the top";
    }

    /**
     * The caveat is written UNDER the option: giving AlertDialog both a message
     * and a list makes it drop the list entirely, which is why no options were
     * showing up at all.
     */
    private void chooseIncomingStyle() {
        CharSequence[] labels = {
                "Full screen",
                "Banner at the top\n(a locked phone still gets the full screen)"};
        int checked = Prefs.incomingFullScreen(this) ? 0 : 1;
        new AlertDialog.Builder(this)
                .setTitle("How should an incoming call look?")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    Prefs.get(this).edit()
                            .putBoolean(Prefs.INCOMING_FULLSCREEN, which == 0).apply();
                    d.dismiss();
                    bindAll();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** The icons show themselves in the list, so nothing has to be tried blind. */
    private void chooseAppIcon() {
        final AppIcons.Icon[] icons = AppIcons.ALL;
        final AppIcons.Icon now = AppIcons.current(this);

        android.widget.ArrayAdapter<AppIcons.Icon> adapter =
                new android.widget.ArrayAdapter<AppIcons.Icon>(
                        this, R.layout.item_icon_choice, icons) {
                    @Override
                    public View getView(int position, View convertView, android.view.ViewGroup parent) {
                        View row = convertView != null ? convertView
                                : getLayoutInflater().inflate(R.layout.item_icon_choice, parent, false);
                        AppIcons.Icon item = icons[position];

                        android.widget.ImageView img = row.findViewById(R.id.iconPreview);
                        img.setImageResource(item.preview);
                        img.setClipToOutline(true);

                        ((TextView) row.findViewById(R.id.iconLabel)).setText(item.label);
                        row.findViewById(R.id.iconCheck).setVisibility(
                                item.alias.equals(now.alias) ? View.VISIBLE : View.INVISIBLE);
                        return row;
                    }
                };

        new AlertDialog.Builder(this)
                .setTitle("App icon")
                .setAdapter(adapter, (d, which) -> {
                    boolean ok = AppIcons.apply(this, icons[which]);
                    bindAll();
                    Toast.makeText(this, ok
                                    ? "Icon changed — the launcher may take a few seconds"
                                    : "Could not change the icon",
                            Toast.LENGTH_LONG).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private String blockedLabel() {
        if (!BlockRepo.canBlock(this)) return "Not available";
        int n = BlockRepo.list(this).size();
        return n == 0 ? "No blocked numbers" : n + " numbers blocked";
    }

    private String remindersLabel() {
        int n = Reminders.pendingDescriptions(this).size();
        return n == 0 ? "No pending reminders" : n + " reminders pending";
    }

    private void showReminders() {
        List<String> pending = Reminders.pendingDescriptions(this);
        new AlertDialog.Builder(this)
                .setTitle("Pending reminders")
                .setMessage(pending.isEmpty()
                        ? "Nothing pending.\n\nSet one with the «Reject and remind me» "
                        + "/ «Silence and remind me» buttons on the incoming call screen."
                        : String.join("\n", pending))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void editQuickReply() {
        final EditText input = new EditText(this);
        input.setText(Prefs.quickReply(this));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        int p = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(p, p / 2, p, p / 2);
        new AlertDialog.Builder(this)
                .setTitle("Quick reply message")
                .setView(input)
                .setPositiveButton(R.string.save, (d, w) -> {
                    Prefs.get(this).edit()
                            .putString(Prefs.QUICK_REPLY, input.getText().toString()).apply();
                    bindAll();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ system

    private boolean isDefaultDialer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager rm = getSystemService(RoleManager.class);
            return rm != null && rm.isRoleHeld(RoleManager.ROLE_DIALER);
        }
        TelecomManager tm = getSystemService(TelecomManager.class);
        return tm != null && getPackageName().equals(tm.getDefaultDialerPackage());
    }

    private void requestDefaultDialer() {
        if (isDefaultDialer()) {
            Toast.makeText(this, "Already the default phone app", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                RoleManager rm = getSystemService(RoleManager.class);
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                    startActivityForResult(
                            rm.createRequestRoleIntent(RoleManager.ROLE_DIALER), REQ_ROLE);
                    return;
                }
            }
            Intent i = new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER);
            i.putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, getPackageName());
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "You have to pick it from the system settings", Toast.LENGTH_LONG).show();
        }
    }

    /** Keep the version in one place: read it from the package itself. */
    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("Cap-Man Dialer")
                .setMessage("Version " + versionName() + "\n\n"
                        + "Drag the chomper onto the handset to answer.\n"
                        + "Drag the skull onto the chomper to reject.\n"
                        + "Double-press the power key to reject or hang up.\n"
                        + "Press a volume key while ringing to silence it.\n\n"
                        + "Recording captures the microphone; Android does not let "
                        + "apps reach the other party's audio stream.")
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
