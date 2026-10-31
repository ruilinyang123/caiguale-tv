package com.fongmi.android.tv.update;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.utils.Path;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.Future;

import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class HttpUpdateTransfer implements UpdateTransfer {

    private final String url;
    private final File file;
    private final long expectedSize;
    private volatile boolean canceled;
    private volatile Call call;
    private Future<?> future;

    public HttpUpdateTransfer(String url, File file, long expectedSize) {
        this.url = UpdateUrl.requireHttpsUrl(url);
        this.file = file;
        this.expectedSize = Math.max(0, expectedSize);
    }

    @Override
    public void start(Callback callback) {
        canceled = false;
        future = Task.submit(() -> download(callback));
    }

    @Override
    public void cancel() {
        canceled = true;
        if (call != null) call.cancel();
        if (future != null) future.cancel(true);
        // A user cancel means "stop"; the next attempt starts from zero.
        Path.clear(file);
    }

    private void download(Callback callback) {
        try {
            try {
                attempt(callback);
            } catch (RangeRestart restart) {
                // The server cannot serve our cached prefix (mismatched or
                // ignored Range, or 416): drop the partial file and download
                // the whole body once; a second restart gives up on resume.
                Path.clear(file);
                attempt(callback);
            }
        } catch (Exception e) {
            // Keep the partial file on failure: the retry or the next mirror
            // resumes from byte offset instead of starting over.
            if (canceled || isCanceled(e)) return;
            App.post(() -> callback.error(message(e)));
        } finally {
            call = null;
            future = null;
        }
    }

    private void attempt(Callback callback) throws IOException {
        long offset = resumableOffset();
        if (offset > 0 && offset == expectedSize) {
            // A previous attempt already produced the full file; hand it to the
            // caller for validation instead of downloading it again.
            App.post(() -> {
                if (!canceled) callback.success(file);
            });
            return;
        }
        Request.Builder builder = new Request.Builder().url(url).header("Accept-Encoding", "identity");
        if (offset > 0) builder.header("Range", "bytes=" + offset + "-");
        call = UpdateHttp.client().newCall(builder.build());
        try (Response response = call.execute()) {
            if (offset > 0 && response.code() == 416) throw new RangeRestart();
            if (!response.isSuccessful()) throw new IOException("Download failed: HTTP " + response.code());
            ResponseBody body = response.body();
            if (body == null) throw new IOException("Download failed: empty response");
            if (offset > 0 && response.code() != 206) {
                // A plain 200 while a Range was requested: either the server
                // ignores Range or this is not the APK at all (CDN bot
                // challenge page). Reject wrong-size bodies first so the
                // partial file survives for the next mirror; a genuine
                // full-body 200 restarts the transfer from zero.
                long total = body.contentLength();
                if (expectedSize > 0 && total > 0 && total != expectedSize) throw new IOException("Download size mismatch");
                throw new RangeRestart();
            }
            if (response.code() == 206 && parseRangeStart(response.header("Content-Range")) != offset) throw new RangeRestart();
            long length = response.code() == 206 ? offset + Math.max(0, body.contentLength()) : body.contentLength();
            if (expectedSize > 0 && length > 0 && length != expectedSize) throw new IOException("Download size mismatch");
            stream(body.byteStream(), offset, length, callback);
            if (canceled) return;
            App.post(() -> {
                if (!canceled) callback.success(file);
            });
        }
    }

    private void stream(InputStream source, long offset, long length, Callback callback) throws IOException {
        try (BufferedInputStream input = new BufferedInputStream(source); FileOutputStream output = openOutput(offset)) {
            byte[] buffer = new byte[16384];
            long bytes = offset;
            long start = System.currentTimeMillis();
            long lastTime = start;
            long lastBytes = offset;
            int lastProgress = -1;
            postProgress(callback, progress(bytes, length), bytes, length, 0, 0);
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (canceled || Thread.currentThread().isInterrupted()) throw new IOException("Canceled");
                output.write(buffer, 0, count);
                bytes += count;
                if (expectedSize > 0 && bytes > expectedSize) throw new IOException("Download exceeds expected size");
                long now = System.currentTimeMillis();
                int progress = progress(bytes, length);
                if (progress == lastProgress && now - lastTime < 1000) continue;
                long speed = (bytes - lastBytes) * 1000 / Math.max(1, now - lastTime);
                postProgress(callback, progress, bytes, length, speed, now - start);
                lastProgress = progress;
                lastTime = now;
                lastBytes = bytes;
            }
            if (length > 0 && bytes != length) throw new IOException("Download incomplete");
            if (expectedSize > 0 && bytes != expectedSize) throw new IOException("Download size mismatch");
        }
    }

    private FileOutputStream openOutput(long offset) throws IOException {
        // Path.create() deletes an existing file, so resumption opens the
        // partial file in append mode manually; offset 0 truncates it.
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Cannot create download directory");
        return new FileOutputStream(file, offset > 0);
    }

    private int progress(long bytes, long length) {
        return length > 0 ? (int) (bytes * 100 / length) : -1;
    }

    long resumableOffset() {
        // Without a known size a stale file cannot be told apart from a valid
        // partial download, so only resume when the manifest carries a size.
        if (expectedSize <= 0 || canceled) return 0;
        if (!file.isFile() || file.length() <= 0 || file.length() > expectedSize) return 0;
        return file.length();
    }

    static long parseRangeStart(String contentRange) {
        if (contentRange == null) return -1;
        String value = contentRange.trim().toLowerCase(Locale.ROOT);
        if (!value.startsWith("bytes")) return -1;
        int dash = value.indexOf('-', 5);
        if (dash <= 5) return -1;
        try {
            return Long.parseLong(value.substring(5, dash).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void postProgress(Callback callback, int progress, long bytes, long total, long speed, long elapsed) {
        App.post(() -> {
            if (!canceled) callback.progress(progress, bytes, total, speed, elapsed);
        });
    }

    private boolean isCanceled(Exception e) {
        String message = e.getMessage();
        return "Canceled".equals(message) || "Socket closed".equals(message);
    }

    private String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static final class RangeRestart extends IOException {

        private RangeRestart() {
            super("Server cannot resume download");
        }
    }
}
