package org.broeuschmeul.android.gps.usb.provider.ublox;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.support.v4.util.AtomicFile;
import org.broeuschmeul.android.gps.usb.provider.USBGpsApplication;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** One session belongs to exactly one USB connection; no worker can write to a replacement device. */
public final class UbloxAssistSession {
    public static final String ENABLED = "ubloxAssist";
    public static final String ONLINE = "ubloxAssistOnline";
    public static final String HOST_TIME = "ubloxAssistHostTime";
    public static final String SECRETS = "assistnow_credentials";
    public static final String TOKEN = "token";
    public static final String STATUS = "ubloxAssistStatus";
    public interface Transport { void send(byte[] frame) throws IOException; }

    private final Context context;
    private final SharedPreferences prefs;
    private final Transport transport;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final CountDownLatch versionReceived = new CountDownLatch(1);
    private final AssistNowClient client = new AssistNowClient();
    private final EphemerisCache cache = new EphemerisCache();
    private final AtomicFile cacheFile;
    private final long started = SystemClock.elapsedRealtime();
    private volatile boolean closed;
    private volatile boolean compatible;
    private volatile boolean firstFix;
    private boolean firstNmea;
    private int returnedEphemerides;
    private volatile boolean polling;

    public UbloxAssistSession(Context context, Transport transport) {
        this.context = context.getApplicationContext();
        this.transport = transport;
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        cacheFile = new AtomicFile(new File(context.getFilesDir(), "ublox7-ephemeris.bin"));
    }

    public void start() {
        log("USB connected; checking receiver version");
        worker.execute(new Runnable() { public void run() { initialize(); } });
    }

    private void initialize() {
        try {
            for (int attempt = 0; attempt < 3 && !closed; attempt++) {
                send(UbxProtocol.poll(0x0a, 4));
                if (versionReceived.await(2, TimeUnit.SECONDS)) break;
            }
            if (!compatible || closed) {
                if (!closed) log("No compatible u-blox 7 response; assistance skipped, normal GPS continues");
                return;
            }
            try (FileInputStream in = cacheFile.openRead()) { cache.read(in); }
            catch (IOException e) { log("No readable ephemeris cache"); }
            if (prefs.getBoolean(HOST_TIME, false)) {
                try { send(UbxProtocol.timeAid(System.currentTimeMillis())); log("Android UTC time sent (10s uncertainty)"); }
                catch (IllegalArgumentException e) { log("Android date invalid; time assistance skipped"); }
            }
            List<byte[]> cached = cache.usable(System.currentTimeMillis());
            for (byte[] frame : cached) { send(frame); Thread.sleep(50); }
            log("Fresh cached ephemerides sent: " + cached.size());
            if (prefs.getBoolean(ONLINE, false) && !firstFix) downloadAndSend();
            worker.scheduleWithFixedDelay(new Runnable() {
                public void run() {
                    if (closed) return;
                    try {
                        returnedEphemerides = 0;
                        polling = true;
                        send(UbxProtocol.poll(0x0b, 0x31));
                    } catch (IOException e) { log("Ephemeris poll failed; normal GPS continues"); }
                }
            }, 0, 60, TimeUnit.SECONDS);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (IOException e) { if (!closed) log("USB assistance write failed; normal GPS continues"); }
    }

    private void downloadAndSend() throws InterruptedException, IOException {
        String token = context.getSharedPreferences(SECRETS, Context.MODE_PRIVATE).getString(TOKEN, "");
        if (token.trim().isEmpty()) { log("Online assistance skipped: configure a legacy AssistNow token"); return; }
        // Throttle reconnects too, including failures. This timestamp is never a proof of data freshness.
        SharedPreferences state = context.getSharedPreferences("assistnow_state", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long previous = state.getLong("lastAttempt", 0);
        if (now >= previous && now - previous < 5 * 60 * 1000L) {
            log("Online retry limited to once per 5 minutes; normal GPS continues"); return;
        }
        state.edit().putLong("lastAttempt", now).apply();
        log("Downloading legacy GPS assistance");
        long requestStarted = SystemClock.elapsedRealtime();
        List<byte[]> frames;
        try { frames = client.download(token); }
        catch (IOException e) {
            // Do not log exception text: network implementations may include the token-bearing URL.
            if (!closed) log("Online download failed (network, token, service or data format); normal GPS continues");
            return;
        }
        if (closed || firstFix) return;
        int count = 0;
        // Preserve server order, with time initialization sent before orbit messages.
        for (byte[] frame : frames) if (frame[3] == 1) {
            send(UbxProtocol.addTimeUncertainty(frame, SystemClock.elapsedRealtime() - requestStarted));
            Thread.sleep(50);
        }
        for (byte[] frame : frames) {
            if (frame[3] == 1) continue;
            if (frame[3] == 0x31 && !UbxProtocol.usableEphemeris(frame, System.currentTimeMillis())) continue;
            send(frame); count++;
            if (frame[3] == 0x31) cache.put(frame, System.currentTimeMillis());
            Thread.sleep(50);
        }
        saveCache();
        log("Online orbit/auxiliary frames sent: " + count + "; receiver readback follows (not a fix guarantee)");
    }

    private void send(byte[] frame) throws IOException {
        if (closed || Thread.currentThread().isInterrupted()) throw new IOException("Session closed");
        transport.send(frame);
    }

    public void onFrame(final byte[] frame) {
        if (closed) return;
        if (frame[2] == 0x0a && frame[3] == 4 && UbxProtocol.length(frame) >= 40) {
            try {
                String hardware = new String(frame, 36, 10, "US-ASCII").replace('\0', ' ').trim();
                String software = new String(frame, 6, 30, "US-ASCII").replace('\0', ' ').trim();
                compatible = hardware.equals("00070000");
                log("Receiver HW " + hardware + ", SW " + software
                        + (compatible ? "; u-blox 7 confirmed" : "; unsupported for this assistance mode"));
                versionReceived.countDown();
            } catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
        } else if (compatible && polling && frame[2] == 11 && frame[3] == 49
                && UbxProtocol.usableEphemeris(frame, System.currentTimeMillis())) {
            final long received = System.currentTimeMillis();
            try { worker.execute(new Runnable() { public void run() {
                if (closed) return;
                if (cache.put(frame, received)) saveCache();
                returnedEphemerides++;
                if (returnedEphemerides == 1) log("Receiver reports a usable GPS ephemeris after " + elapsed() + "s");
            } }); } catch (RejectedExecutionException ignored) { /* disconnect raced with a USB read */ }
        } else if (frame[2] == 5 && frame[3] == 0) {
            log("Receiver rejected a UBX command; inspect protocol compatibility");
        }
    }

    public void onNmea(String sentence) {
        if (!firstNmea) { firstNmea = true; log("First NMEA data after " + elapsed() + "s"); }
        if (firstFix || !validNmea(sentence)) return;
        String[] fields = sentence.substring(0, sentence.indexOf('*')).split(",", -1);
        boolean fix = fields[0].endsWith("RMC") && fields.length > 6 && fields[2].equals("A")
                && !fields[3].isEmpty() && !fields[5].isEmpty();
        if (fields[0].endsWith("GGA") && fields.length > 6)
            fix = fields[6].equals("1") || fields[6].equals("2") || fields[6].equals("4") || fields[6].equals("5");
        if (fix) { firstFix = true; log("First valid position after USB connection: " + elapsed() + "s"); }
    }

    private static boolean validNmea(String sentence) {
        int star = sentence.indexOf('*');
        if (!sentence.startsWith("$") || star < 1 || sentence.length() < star + 3) return false;
        int checksum = 0;
        for (int i = 1; i < star; i++) checksum ^= sentence.charAt(i);
        try { return checksum == Integer.parseInt(sentence.substring(star + 1, star + 3), 16); }
        catch (NumberFormatException e) { return false; }
    }

    private String elapsed() { return String.format(Locale.US, "%.1f", (SystemClock.elapsedRealtime() - started) / 1000.0); }
    private void saveCache() {
        FileOutputStream out = null;
        try { out = cacheFile.startWrite(); cache.write(out); cacheFile.finishWrite(out); }
        catch (IOException e) { if (out != null) cacheFile.failWrite(out); log("Cannot save ephemeris cache"); }
    }
    private void log(String message) {
        if (closed) return;
        prefs.edit().putString(STATUS, message).apply();
        ((USBGpsApplication) context).notifyNewSentence("[Assist +" + elapsed() + "s] " + message + "\n");
    }
    public void close() {
        closed = true; versionReceived.countDown(); worker.shutdownNow(); client.cancel();
    }
}
