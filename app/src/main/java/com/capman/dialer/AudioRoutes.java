package com.capman.dialer;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telecom.CallAudioState;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the call audio comes out: the speaker, the earpiece, a wired headset
 * or a connected Bluetooth device.
 *
 * Why this deserves its own class: the speaker button used to be a plain
 * on/off toggle that always fell back to {@code ROUTE_WIRED_OR_EARPIECE} when
 * switched off. On a Bluetooth headset, toggling the speaker off therefore
 * dropped the audio to the EARPIECE instead of back to the headset. The options
 * now come from the route mask Telecom reports, so we can simply ask.
 *
 * Reading device names needs BLUETOOTH_CONNECT since Android 12; without it we
 * write "Bluetooth" instead of the name and the choice still works.
 */
public final class AudioRoutes {

    private AudioRoutes() {
    }

    /** One selectable audio output. */
    public static class Option {
        /** The {@link CallAudioState} ROUTE_* constant. */
        public final int route;
        /** Only set for Bluetooth options: which device to route to. */
        public final BluetoothDevice device;
        public final String label;
        public final int icon;
        public final boolean active;

        Option(int route, BluetoothDevice device, String label, int icon, boolean active) {
            this.route = route;
            this.device = device;
            this.label = label;
            this.icon = icon;
            this.active = active;
        }
    }

    /**
     * Is it worth asking? Yes as soon as a Bluetooth device is around - the
     * speaker button then opens the picker instead of toggling.
     */
    public static boolean needsPicker() {
        return (CallManager.supportedRoutes() & CallAudioState.ROUTE_BLUETOOTH) != 0;
    }

    /** Short name of the output in use, shown under the button. */
    public static String currentLabel(Context ctx) {
        int route = CallManager.currentRoute();
        if (route == CallAudioState.ROUTE_SPEAKER) return "Speaker";
        if (route == CallAudioState.ROUTE_BLUETOOTH) {
            return nameOf(ctx, CallManager.activeBluetoothDevice());
        }
        if (route == CallAudioState.ROUTE_WIRED_HEADSET) return "Headset";
        return "Earpiece";
    }

    /** Is the speaker the current output? Drives the button highlight. */
    public static boolean speakerActive() {
        return CallManager.currentRoute() == CallAudioState.ROUTE_SPEAKER;
    }

    /** Every option: earpiece/headset first, then the Bluetooth devices, speaker last. */
    public static List<Option> list(Context ctx) {
        List<Option> out = new ArrayList<>();
        int supported = CallManager.supportedRoutes();
        int current = CallManager.currentRoute();

        if ((supported & CallAudioState.ROUTE_WIRED_HEADSET) != 0) {
            out.add(new Option(CallAudioState.ROUTE_WIRED_HEADSET, null, "Wired headset",
                    R.drawable.ic_headset, current == CallAudioState.ROUTE_WIRED_HEADSET));
        } else if ((supported & CallAudioState.ROUTE_EARPIECE) != 0) {
            out.add(new Option(CallAudioState.ROUTE_EARPIECE, null, "Earpiece",
                    R.drawable.ic_phone, current == CallAudioState.ROUTE_EARPIECE));
        }

        BluetoothDevice active = CallManager.activeBluetoothDevice();
        List<BluetoothDevice> devices = CallManager.bluetoothDevices();
        if (devices.isEmpty() && (supported & CallAudioState.ROUTE_BLUETOOTH) != 0) {
            // Before API 28 there is no device list; a single "Bluetooth" row will do
            out.add(new Option(CallAudioState.ROUTE_BLUETOOTH, null, "Bluetooth",
                    R.drawable.ic_bluetooth, current == CallAudioState.ROUTE_BLUETOOTH));
        }
        for (BluetoothDevice d : devices) {
            boolean on = current == CallAudioState.ROUTE_BLUETOOTH && sameDevice(d, active);
            out.add(new Option(CallAudioState.ROUTE_BLUETOOTH, d, nameOf(ctx, d),
                    R.drawable.ic_bluetooth, on));
        }

        if ((supported & CallAudioState.ROUTE_SPEAKER) != 0) {
            out.add(new Option(CallAudioState.ROUTE_SPEAKER, null, "Speaker",
                    R.drawable.ic_speaker, current == CallAudioState.ROUTE_SPEAKER));
        }
        return out;
    }

    public static void apply(Option o) {
        if (o == null) return;
        if (o.route == CallAudioState.ROUTE_BLUETOOTH) {
            CallManager.useBluetooth(o.device);
        } else {
            CallManager.setAudioRoute(o.route);
        }
    }

    // ------------------------------------------------------------------ names

    private static boolean sameDevice(BluetoothDevice a, BluetoothDevice b) {
        if (a == null || b == null) return false;
        return a.getAddress() != null && a.getAddress().equals(b.getAddress());
    }

    /** The device's display name, or "Bluetooth" without the permission or a name. */
    private static String nameOf(Context ctx, BluetoothDevice d) {
        if (d == null) return "Bluetooth";
        if (!canReadNames(ctx)) return "Bluetooth";
        try {
            String name = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) name = d.getAlias();
            if (name == null || name.trim().isEmpty()) name = d.getName();
            return name == null || name.trim().isEmpty() ? "Bluetooth" : name.trim();
        } catch (SecurityException e) {
            return "Bluetooth";
        }
    }

    /** Android 12+ requires BLUETOOTH_CONNECT to read a device name. */
    private static boolean canReadNames(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        return ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }
}
