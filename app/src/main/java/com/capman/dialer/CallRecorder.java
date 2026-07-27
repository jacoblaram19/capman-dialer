package com.capman.dialer;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Recording audio during a call.
 *
 * A HARD LIMIT: since Android 10 ordinary apps cannot touch the call audio
 * stream (VOICE_CALL/VOICE_DOWNLINK) - that source needs a signature-level
 * permission. So we record from the microphone: your own voice comes through
 * clearly, the other party only if the SPEAKER is on and the sound travels
 * through the air. That is why the speaker is switched on when recording
 * starts.
 *
 * THE SILENT RECORDING PROBLEM: even when a microphone source can be
 * "prepared", the system may feed it pure silence during a call - a file
 * appears with nothing in it. UNPROCESSED, which used to be tried first, did
 * exactly that; because prepare() succeeded, MIC was never even attempted.
 *
 * The fix: before recording starts each source is briefly probed with
 * AudioRecord and the first one that actually carries signal is chosen. The
 * level is watched during recording too, so if nothing is coming through the
 * user hears about it right away rather than at the end of the call.
 */
public class CallRecorder {

    private static final String TAG = "CallRecorder";
    private static final String FOLDER = "CallRecordings";

    /** Probe order, the ones that work best during a call first. */
    private static final int[] SOURCES = {
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
    };

    private static final int SAMPLE_RATE = 44100;
    /** A peak below this counts as "no audio" (16-bit peak is 32767). */
    private static final int SILENCE_PEAK = 300;
    /** How long each source is probed for. */
    private static final long PROBE_MS = 320;
    /** How long to wait after the speaker comes on for the audio path to settle. */
    private static final long ROUTE_SETTLE_MS = 500;
    /** How often the level is sampled while recording. */
    private static final long LEVEL_INTERVAL_MS = 700;
    /** Warn if nothing at all comes through for this long. */
    private static final long SILENCE_ALERT_MS = 4000;

    public interface Listener {
        /** Recording started. silent=true means nothing is coming from the microphone. */
        void onRecordingStarted(boolean silent);

        void onRecordingFailed(String reason);

        /** The silence state changed while recording. */
        void onSilenceChanged(boolean silent);
    }

    /** The outcome of stop(). */
    public static class Result {
        public final String location;
        public final boolean silent;

        Result(String location, boolean silent) {
            this.location = location;
            this.silent = silent;
        }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private MediaRecorder recorder;
    private File file;
    private volatile boolean recording;
    private volatile boolean starting;
    private int peak;
    private long startedAt;
    private boolean silentReported;

    private final Runnable levelTick = new Runnable() {
        @Override
        public void run() {
            if (!recording) return;
            sampleLevel();
            ui.postDelayed(this, LEVEL_INTERVAL_MS);
        }
    };

    private Listener listener;

    public void setListener(Listener l) {
        this.listener = l;
    }

    public boolean isRecording() {
        return recording;
    }

    /** Is recording still being set up, i.e. are the sources being probed? */
    public boolean isStarting() {
        return starting;
    }

    public File currentFile() {
        return file;
    }

    // ------------------------------------------------------------------ starting

    /**
     * Starts recording. Probing the sources takes a few hundred milliseconds,
     * so the work happens in the background and the result arrives through
     * {@link Listener}.
     *
     * @param peer the other party's name or number, used in the file name
     */
    public void start(Context ctx, String peer) {
        if (recording || starting) return;
        starting = true;
        final Context app = ctx.getApplicationContext();
        io.execute(() -> {
            // If the speaker just came on, the audio path may not have settled yet
            sleep(ROUTE_SETTLE_MS);

            final int source = pickSource();
            final boolean silent = source < 0;
            final int chosen = silent ? SOURCES[0] : source;

            final boolean ok = begin(app, peer, chosen);
            ui.post(() -> {
                starting = false;
                if (!ok) {
                    if (listener != null) listener.onRecordingFailed("Could not open the microphone");
                    return;
                }
                recording = true;
                startedAt = SystemClock.elapsedRealtime();
                peak = 0;
                silentReported = silent;
                ui.postDelayed(levelTick, LEVEL_INTERVAL_MS);
                if (listener != null) listener.onRecordingStarted(silent);
            });
        });
    }

    /**
     * Probes the sources in order and returns the one that actually carries
     * signal.
     *
     * @return the source to use, or -1 if none of them produce audio
     */
    private int pickSource() {
        int bestSource = -1;
        int bestPeak = 0;
        for (int source : SOURCES) {
            int p = probe(source);
            Log.d(TAG, "kaynak " + source + " tepe seviye: " + p);
            if (p > bestPeak) {
                bestPeak = p;
                bestSource = source;
            }
            if (p >= SILENCE_PEAK) return source;   // ise yarayani bulduk, yeter
        }
        return bestPeak >= SILENCE_PEAK ? bestSource : -1;
    }

    /** Listens to one source briefly and measures the peak amplitude. -1 means it would not open. */
    private int probe(int source) {
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) return -1;

        AudioRecord rec = null;
        try {
            rec = new AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, min * 2);
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) return -1;
            rec.startRecording();

            short[] buf = new short[min / 2];
            int max = 0;
            long end = SystemClock.elapsedRealtime() + PROBE_MS;
            while (SystemClock.elapsedRealtime() < end) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) break;
                for (int i = 0; i < n; i++) {
                    int v = Math.abs(buf[i]);
                    if (v > max) max = v;
                }
            }
            rec.stop();
            return max;
        } catch (Exception | Error e) {
            Log.w(TAG, "kaynak " + source + " yoklanamadi", e);
            return -1;
        } finally {
            if (rec != null) {
                try {
                    rec.release();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private boolean begin(Context ctx, String peer, int source) {
        try {
            File dir = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC), FOLDER);
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "could not create the recordings folder");
                return false;
            }
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss",
                    new Locale("tr", "TR")).format(new Date());
            file = new File(dir, sanitize(peer) + "_" + stamp + ".m4a");

            recorder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? new MediaRecorder(ctx) : new MediaRecorder();

            if (!configure(source) && !configure(MediaRecorder.AudioSource.MIC)) {
                release();
                return false;
            }
            recorder.start();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "could not start recording", e);
            release();
            return false;
        }
    }

    /** Tries to prepare with the given source, returning false if it will not. */
    private boolean configure(int source) {
        try {
            recorder.reset();
            recorder.setAudioSource(source);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(SAMPLE_RATE);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setOutputFile(file.getAbsolutePath());
            recorder.prepare();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "kaynak " + source + " kullanilamadi", e);
            return false;
        }
    }

    // ------------------------------------------------------------------ level watch

    private void sampleLevel() {
        int amp = 0;
        try {
            if (recorder != null) amp = recorder.getMaxAmplitude();
        } catch (Exception ignored) {
        }
        if (amp > peak) peak = amp;

        boolean silentNow = peak < SILENCE_PEAK
                && SystemClock.elapsedRealtime() - startedAt > SILENCE_ALERT_MS;
        if (silentNow != silentReported) {
            silentReported = silentNow;
            if (listener != null) listener.onSilenceChanged(silentNow);
        }
    }

    /** Did nothing at all come through for the whole recording? */
    public boolean isSilent() {
        return silentReported;
    }

    // ------------------------------------------------------------------ finishing

    /**
     * Stops recording and moves the file under Music/CallRecordings, so it
     * shows up in file managers and music apps.
     */
    public Result stop(Context ctx) {
        if (!recording) return null;
        recording = false;
        ui.removeCallbacks(levelTick);
        try {
            recorder.stop();
        } catch (Exception e) {
            Log.w(TAG, "the recording did not close cleanly, it may be too short", e);
        }
        release();

        boolean silent = peak < SILENCE_PEAK;
        if (file == null || !file.exists() || file.length() == 0) {
            return new Result(null, silent);
        }
        String published = publish(ctx, file);
        return new Result(published != null ? published : file.getAbsolutePath(), silent);
    }

    public void shutdown() {
        ui.removeCallbacks(levelTick);
        io.shutdownNow();
    }

    private void release() {
        try {
            if (recorder != null) recorder.release();
        } catch (Exception ignored) {
        }
        recorder = null;
    }

    /** Copies the file into the shared Music folder, through MediaStore so no permission is needed. */
    private String publish(Context ctx, File src) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Audio.Media.DISPLAY_NAME, src.getName());
            v.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
            v.put(MediaStore.Audio.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MUSIC + "/" + FOLDER);
            v.put(MediaStore.Audio.Media.IS_PENDING, 1);

            Uri uri = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return null;

            try (InputStream in = new FileInputStream(src);
                 OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) return null;
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            v.clear();
            v.put(MediaStore.Audio.Media.IS_PENDING, 0);
            cr.update(uri, v, null, null);

            // copied, drop the temporary file
            if (!src.delete()) Log.w(TAG, "could not delete the temporary recording");
            return "Music/" + FOLDER + "/" + src.getName();
        } catch (Exception e) {
            Log.e(TAG, "could not move the recording to the shared folder", e);
            return null;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String sanitize(String s) {
        if (s == null || s.trim().isEmpty()) return "call";
        String cleaned = s.trim().replaceAll("[^\\p{L}\\p{N}._-]", "_");
        return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }
}
