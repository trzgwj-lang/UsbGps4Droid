package org.broeuschmeul.android.gps.usb.provider.ublox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;

/** Legacy service, explicitly requested in AID format for u-blox 7 (not MGA). */
public final class AssistNowClient {
    private volatile HttpsURLConnection active;
    private volatile boolean cancelled;

    public static URL requestUrl(String token) throws IOException {
        if (token.trim().isEmpty()) throw new IOException("AssistNow token is missing");
        return new URL("https://online-live1.services.u-blox.com/GetOnlineData.ashx?token="
                + URLEncoder.encode(token.trim(), "UTF-8")
                + ";gnss=gps;datatype=eph,alm,aux;format=aid;tacc=10;");
    }

    public List<byte[]> download(String token) throws IOException {
        HttpsURLConnection c = (HttpsURLConnection) requestUrl(token).openConnection();
        active = c;
        try {
            if (cancelled) throw new IOException("Cancelled");
            c.setConnectTimeout(10000); c.setReadTimeout(10000);
            c.setInstanceFollowRedirects(false); // Never forward credentials to a redirect target.
            c.setRequestProperty("Accept", "application/octet-stream");
            int status = c.getResponseCode();
            if (status != 200) throw new IOException("AssistNow HTTP " + status);
            if (c.getContentLength() > 65536) throw new IOException("Assistance response too large");
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            long deadline = System.nanoTime() + 15000000000L;
            try (InputStream input = c.getInputStream()) {
                byte[] buffer = new byte[2048];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    if (cancelled || Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
                    if (System.nanoTime() > deadline) throw new IOException("Assistance download timed out");
                    if (data.size() + n > 65536) throw new IOException("Assistance response too large");
                    data.write(buffer, 0, n);
                }
            }
            return UbxProtocol.assistance(data.toByteArray());
        } finally { c.disconnect(); active = null; }
    }

    public void cancel() {
        cancelled = true;
        HttpsURLConnection c = active;
        if (c != null) c.disconnect();
    }
}
