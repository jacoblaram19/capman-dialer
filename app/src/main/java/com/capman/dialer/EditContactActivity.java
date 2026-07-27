package com.capman.dialer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Adding and editing a contact.
 *
 * This screen also answers the system's "add contact" requests (the INSERT /
 * INSERT_OR_EDIT filters in the manifest): with the stock contacts app removed,
 * "save to contacts" requests from other apps had nowhere to go.
 */
public class EditContactActivity extends BaseActivity {

    private static final String EX_CONTACT_ID = "contact_id";
    private static final String EX_NUMBER = "number";

    private long contactId = -1;
    private Contact contact;

    private EditText nameInput;
    private TextView avatar, accountLabel, accountHint;
    private LinearLayout numbersContainer;

    private List<ContactAccount> accounts = new ArrayList<>();
    private ContactAccount selectedAccount;

    private static final int REQ_PHOTO = 301;
    private static final int REQ_AUDIO = 302;
    private static final int REQ_AUDIO_PERM = 303;
    /** The newly picked photo; null means the photo is left alone. */
    private byte[] pickedPhoto;
    /** Did the user ask for the existing photo to be removed? */
    private boolean removePhotoRequested = false;

    /** The chosen ringtone uri; null means the default. */
    private String ringtoneUri;
    private TextView ringtoneLabel;
    /** The sound being previewed in the picker. */
    private android.media.Ringtone preview;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    public static Intent intentForNew(Context ctx, String number) {
        Intent i = new Intent(ctx, EditContactActivity.class);
        i.putExtra(EX_NUMBER, number);
        return i;
    }

    public static Intent intentForEdit(Context ctx, long contactId) {
        Intent i = new Intent(ctx, EditContactActivity.class);
        i.putExtra(EX_CONTACT_ID, contactId);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_contact);

        nameInput = findViewById(R.id.nameInput);
        avatar = findViewById(R.id.avatar);
        accountLabel = findViewById(R.id.accountLabel);
        accountHint = findViewById(R.id.accountHint);
        numbersContainer = findViewById(R.id.numbersContainer);

        findViewById(R.id.accountRow).setOnClickListener(v -> chooseAccount());
        findViewById(R.id.avatarBox).setOnClickListener(v -> pickPhoto());
        findViewById(R.id.changePhoto).setOnClickListener(v -> pickPhoto());
        findViewById(R.id.removePhoto).setOnClickListener(v -> removePhoto());
        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.addNumber).setOnClickListener(v -> addNumberRow(""));
        findViewById(R.id.save).setOnClickListener(v -> save());

        ringtoneLabel = findViewById(R.id.ringtoneLabel);
        findViewById(R.id.ringtoneRow).setOnClickListener(v -> chooseRingtone());

        nameInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                avatar.setText(PhoneUtil.initials(s.toString()));
            }
        });

        contactId = getIntent().getLongExtra(EX_CONTACT_ID, -1);
        String prefill = getIntent().getStringExtra(EX_NUMBER);

        // An "add contact" request coming from another app
        if (prefill == null) {
            String action = getIntent().getAction();
            if (Intent.ACTION_INSERT.equals(action)
                    || Intent.ACTION_INSERT_OR_EDIT.equals(action)
                    || Intent.ACTION_EDIT.equals(action)) {
                prefill = getIntent().getStringExtra(ContactsContract.Intents.Insert.PHONE);
                String extraName = getIntent().getStringExtra(ContactsContract.Intents.Insert.NAME);
                if (extraName != null) nameInput.setText(extraName);
            }
        }

        if (contactId > 0) {
            ((TextView) findViewById(R.id.title)).setText(R.string.edit_contact);
            // An existing contact already lives in an account; moving it is a
            // separate job, so the edit screen hides the destination picker.
            findViewById(R.id.accountTitle).setVisibility(View.GONE);
            findViewById(R.id.accountRow).setVisibility(View.GONE);
            loadExisting();
        } else {
            ((TextView) findViewById(R.id.title)).setText(R.string.new_contact);
            addNumberRow(prefill == null ? "" : prefill);
            if (prefill == null || prefill.isEmpty()) nameInput.requestFocus();
            loadAccounts();
        }
    }

    // ------------------------------------------------------------------ photo

    private void pickPhoto() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            startActivityForResult(i, REQ_PHOTO);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open the gallery", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;

        if (requestCode == REQ_AUDIO) {
            final Uri src = data.getData();
            Toast.makeText(this, "Adding the sound…", Toast.LENGTH_SHORT).show();
            io.execute(() -> {
                // Copied into the Ringtones folder so the system can read it
                final String imported = Ringtones.importAudio(this, src);
                ui.post(() -> {
                    if (imported == null) {
                        Toast.makeText(this, "Could not add the sound", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ringtoneUri = imported;
                    showRingtone();
                });
            });
            return;
        }
        if (requestCode != REQ_PHOTO) return;
        final Uri uri = data.getData();
        io.execute(() -> {
            final byte[] jpeg = readSquareJpeg(uri);
            ui.post(() -> {
                if (jpeg == null) {
                    Toast.makeText(this, "Could not read the photo", Toast.LENGTH_SHORT).show();
                    return;
                }
                pickedPhoto = jpeg;
                removePhotoRequested = false;
                findViewById(R.id.removePhoto).setVisibility(View.VISIBLE);
                Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                ImageView view = findViewById(R.id.avatarPhoto);
                view.setImageBitmap(bmp);
                view.setVisibility(View.VISIBLE);
                view.setTag(null);          // PhotoLoader'in eski isteklerini gecersiz kil
                avatar.setVisibility(View.GONE);
            });
        });
    }

    /** Crops the chosen image to a square, scales it to 512 px and encodes it as JPEG. */
    private byte[] readSquareJpeg(Uri uri) {
        InputStream in = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            in = getContentResolver().openInputStream(uri);
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, bounds);
            in.close();

            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= 512
                    && bounds.outHeight / (sample * 2) >= 512) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            in = getContentResolver().openInputStream(uri);
            if (in == null) return null;
            Bitmap src = BitmapFactory.decodeStream(in, null, opts);
            if (src == null) return null;

            int side = Math.min(src.getWidth(), src.getHeight());
            Bitmap square = Bitmap.createBitmap(src,
                    (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
            if (square != src) src.recycle();

            int out = Math.min(512, side);
            Bitmap scaled = Bitmap.createScaledBitmap(square, out, out, true);
            if (scaled != square) square.recycle();

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 88, bos);
            scaled.recycle();
            return bos.toByteArray();
        } catch (Exception | OutOfMemoryError e) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Remove the photo: the initial comes back at once, the deletion happens on save. */
    private void removePhoto() {
        removePhotoRequested = true;
        pickedPhoto = null;
        ImageView view = findViewById(R.id.avatarPhoto);
        view.setImageDrawable(null);
        view.setVisibility(View.GONE);
        view.setTag(null);
        avatar.setVisibility(View.VISIBLE);
        findViewById(R.id.removePhoto).setVisibility(View.GONE);
        Toast.makeText(this, "The photo will be removed when you save", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ ringtone

    private void showRingtone() {
        ringtoneLabel.setText(Ringtones.titleOf(this, ringtoneUri));
    }

    /**
     * The ringtone list: "add new music" on top, then the default, then the
     * phone's ringtones and the user's own music.
     */
    private void chooseRingtone() {
        if (!ensureAudioPermission()) return;

        io.execute(() -> {
            final List<Ringtones.Item> sounds = Ringtones.list(this);
            ui.post(() -> showRingtoneDialog(sounds));
        });
    }

    private void showRingtoneDialog(List<Ringtones.Item> sounds) {
        final List<Ringtones.Item> items = new ArrayList<>();
        items.add(new Ringtones.Item("➕  Add new music…", "ADD"));
        items.add(new Ringtones.Item("Default ringtone", null));
        items.addAll(sounds);

        CharSequence[] labels = new CharSequence[items.size()];
        int checked = 1;
        for (int i = 0; i < items.size(); i++) {
            labels[i] = items.get(i).title;
            if (ringtoneUri != null && ringtoneUri.equals(items.get(i).uri)) checked = i;
        }

        final int selected = checked;
        // Not applied on selection: it takes effect once the user listens and hits "Save"
        final String[] pending = {ringtoneUri};

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Ringtone")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    Ringtones.Item picked = items.get(which);
                    if ("ADD".equals(picked.uri)) {
                        stopPreview();
                        d.dismiss();
                        pickAudioFile();
                        return;
                    }
                    pending[0] = picked.uri;
                    playPreview(picked.uri);      // secilen ses hemen duyulsun
                })
                .setNegativeButton(R.string.cancel, (d, w) -> stopPreview())
                .setPositiveButton(R.string.save, (d, w) -> {
                    stopPreview();
                    ringtoneUri = pending[0];
                    showRingtone();
                })
                .create();
        dialog.setOnDismissListener(d -> stopPreview());
        dialog.show();

        // The list scrolls to the checked row, which pushed "Add new music" out
        // of sight when the selection was near the top. Pull it back up.
        if (selected <= 2) {
            dialog.getListView().post(() -> dialog.getListView().setSelection(0));
        }
    }

    /** Plays the chosen ringtone, stopping whatever was playing before. */
    private void playPreview(String uri) {
        stopPreview();
        if (uri == null || uri.trim().isEmpty()) return;       // varsayilan: calma
        try {
            preview = android.media.RingtoneManager.getRingtone(this, Uri.parse(uri));
            if (preview == null) return;
            preview.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            preview.play();
        } catch (Exception ignored) {
        }
    }

    private void stopPreview() {
        try {
            if (preview != null && preview.isPlaying()) preview.stop();
        } catch (Exception ignored) {
        }
        preview = null;
    }

    private void pickAudioFile() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("audio/*");
            startActivityForResult(i, REQ_AUDIO);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open the file picker", Toast.LENGTH_SHORT).show();
        }
    }

    /** The permission needed to read the music list (Android 13+). */
    private boolean ensureAudioPermission() {
        String perm = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
                ? "android.permission.READ_MEDIA_AUDIO"
                : android.Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        requestPermissions(new String[]{perm}, REQ_AUDIO_PERM);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_AUDIO_PERM) return;
        // Carry on even without it: the ringtones still list, only the music is missing
        chooseRingtoneAfterPermission();
    }

    private void chooseRingtoneAfterPermission() {
        io.execute(() -> {
            final List<Ringtones.Item> sounds = Ringtones.list(this);
            ui.post(() -> showRingtoneDialog(sounds));
        });
    }

    // ------------------------------------------------------------------ destination

    private void loadAccounts() {
        io.execute(() -> {
            final List<ContactAccount> list = AccountsRepo.writableAccounts(this);
            final ContactAccount last = AccountsRepo.lastUsed(this, list);
            ui.post(() -> {
                accounts = list;
                selectedAccount = last;
                showAccount();
            });
        });
    }

    private void showAccount() {
        if (selectedAccount == null) return;
        accountLabel.setText(selectedAccount.label);
        accountHint.setText(selectedAccount.isSim
                ? "A SIM holds one number per contact and may shorten the name"
                : "New contacts are saved here");
    }

    private void chooseAccount() {
        if (accounts.isEmpty()) return;
        CharSequence[] labels = new CharSequence[accounts.size()];
        int checked = 0;
        for (int i = 0; i < accounts.size(); i++) {
            labels[i] = accounts.get(i).label;
            if (accounts.get(i).equals(selectedAccount)) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Nereye kaydedilsin?")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    selectedAccount = accounts.get(which);
                    showAccount();
                    d.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Don't leave the preview playing in the background after leaving the screen
        stopPreview();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPreview();
        io.shutdownNow();
    }

    private void loadExisting() {
        io.execute(() -> {
            Contact c = ContactsRepo.load(getContentResolver(), contactId);
            final String tone = ContactsRepo.getRingtone(getContentResolver(), contactId);
            ui.post(() -> {
                ringtoneUri = tone;
                showRingtone();
                contact = c;
                if (c == null) {
                    Toast.makeText(this, "Contact not found", Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                nameInput.setText(c.name == null ? "" : c.name);
                avatar.setText(PhoneUtil.initials(c.displayName()));
                PhotoLoader.load(c.bestPhoto(true), findViewById(R.id.avatarPhoto), avatar, true);
                findViewById(R.id.removePhoto).setVisibility(
                        c.bestPhoto(true) != null ? View.VISIBLE : View.GONE);
                if (c.numbers.isEmpty()) {
                    addNumberRow("");
                } else {
                    for (String n : c.numbers) addNumberRow(n);
                }
            });
        });
    }

    private void addNumberRow(String value) {
        View row = LayoutInflater.from(this)
                .inflate(R.layout.item_number_edit, numbersContainer, false);
        EditText input = row.findViewById(R.id.numberInput);
        input.setText(value);
        row.findViewById(R.id.removeNumber).setOnClickListener(v -> {
            numbersContainer.removeView(row);
            if (numbersContainer.getChildCount() == 0) addNumberRow("");
        });
        numbersContainer.addView(row);
        if (value.isEmpty()) input.requestFocus();
    }

    private List<String> collectNumbers() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < numbersContainer.getChildCount(); i++) {
            View row = numbersContainer.getChildAt(i);
            EditText input = row.findViewById(R.id.numberInput);
            String v = input.getText().toString().trim();
            // 0532... -> +90532...  (short numbers that cannot be converted stay as they are)
            if (!v.isEmpty()) out.add(PhoneUtil.toE164(this, v));
        }
        return out;
    }

    private void save() {
        final String name = nameInput.getText().toString().trim();
        final List<String> numbers = collectNumbers();

        if (name.isEmpty() && numbers.isEmpty()) {
            Toast.makeText(this, "A name or a number is required", Toast.LENGTH_SHORT).show();
            return;
        }
        if (numbers.isEmpty()) {
            Toast.makeText(this, "At least one phone number is required", Toast.LENGTH_SHORT).show();
            return;
        }

        final boolean isNew = contactId <= 0;

        // A hardware limit of the SIM: one number per contact
        if (isNew && selectedAccount != null && selectedAccount.isSim && numbers.size() > 1) {
            new AlertDialog.Builder(this)
                    .setTitle("SIM card limit")
                    .setMessage("A SIM card stores one number per contact. "
                            + "Save only the first one (" + PhoneUtil.pretty(numbers.get(0))
                            + ")?")
                    .setPositiveButton(R.string.save, (d, w) ->
                            doSave(name, numbers.subList(0, 1), true))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        doSave(name, numbers, isNew);
    }

    private void doSave(final String name, final List<String> numbers, final boolean isNew) {
        final ContactAccount account = selectedAccount;
        final byte[] photo = pickedPhoto;
        final String tone = ringtoneUri;
        final boolean dropPhoto = removePhotoRequested;
        io.execute(() -> {
            final boolean ok;
            if (isNew) {
                long newId = ContactsRepo.insert(getContentResolver(), name, numbers, account, photo);
                ok = newId > 0;
                if (ok && account != null) AccountsRepo.remember(this, account);
                // The ringtone can only be written once the contact exists
                if (ok && tone != null) ContactsRepo.setRingtone(getContentResolver(), newId, tone);
            } else {
                ok = ContactsRepo.update(getContentResolver(), contactId, name, numbers);
                if (ok && photo != null) {
                    ContactsRepo.setPhoto(getContentResolver(), contactId, photo);
                } else if (ok && dropPhoto) {
                    ContactsRepo.setPhoto(getContentResolver(), contactId, null);
                }
                if (ok) ContactsRepo.setRingtone(getContentResolver(), contactId, tone);
            }
            ui.post(() -> {
                Toast.makeText(this,
                        ok ? (isNew ? "Contact saved" : "Contact updated") : "Could not save",
                        Toast.LENGTH_SHORT).show();
                if (ok) {
                    setResult(RESULT_OK);
                    finish();
                }
            });
        });
    }
}
