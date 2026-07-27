package com.capman.dialer;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Contact picker dialog.
 *
 * A plain list is useless once the address book holds thousands of people, so
 * there is a search box on top and the list filters as you type, by name or by
 * number.
 */
public final class ContactPicker {

    private ContactPicker() {
    }

    public static void show(Activity activity, String title, List<Contact> source,
                            Consumer<Contact> onPick) {
        View view = activity.getLayoutInflater().inflate(R.layout.dialog_contact_picker, null);
        EditText query = view.findViewById(R.id.pickerSearch);
        ListView list = view.findViewById(R.id.pickerList);
        TextView emptyView = view.findViewById(R.id.pickerEmpty);

        final List<Contact> shown = new ArrayList<>(source);
        ArrayAdapter<Contact> adapter = new ArrayAdapter<Contact>(
                activity, android.R.layout.simple_list_item_1, shown) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setText(getItem(position).displayName());
                tv.setTextColor(activity.getColor(R.color.text));
                return tv;
            }
        };
        list.setAdapter(adapter);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .create();

        query.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                filter(source, shown, s.toString());
                adapter.notifyDataSetChanged();
                emptyView.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
            }
        });

        list.setOnItemClickListener((parent, v, position, id) -> {
            dialog.dismiss();
            onPick.accept(shown.get(position));
        });
        dialog.show();
    }

    private static void filter(List<Contact> source, List<Contact> out, String q) {
        String lq = PhoneUtil.lowerCase(q.trim());
        String dq = PhoneUtil.digitsOnly(q);
        out.clear();
        for (Contact c : source) {
            boolean hit = lq.isEmpty() || PhoneUtil.lowerCase(c.displayName()).contains(lq);
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
    }
}
