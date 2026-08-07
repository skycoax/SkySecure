package uz.jac.secure.android;

import java.nio.ByteBuffer;

import uz.jac.secure.core.hash.StreamingSha256;

/**
 * Java-facing wrapper over the Kotlin {@link StreamingSha256}.
 *
 * Exists so the patch applied to {@code FileLoadOperation.java} touches nothing
 * but a field declaration and two call sites. Keeping the upstream diff that
 * small is what makes rebasing onto a new Telegram release a ten-minute job
 * rather than a merge conflict every time.
 *
 * Not thread-safe, and does not need to be: one instance per
 * FileLoadOperation, written only from the thread that owns the file channel.
 */
public final class StreamingHashSink {

    private final StreamingSha256 delegate = new StreamingSha256();

    /**
     * Feed a chunk that is about to be written at {@code offset}.
     *
     * The buffer's position and limit are left untouched — the caller hands the
     * same buffer to {@code channel.write()} on the next line.
     */
    public void update(long offset, ByteBuffer buffer) {
        if (buffer == null) {
            delegate.invalidate();
            return;
        }
        delegate.update(offset, buffer);
    }

    /** Abandon the streaming digest; the caller must re-hash from disk. */
    public void invalidate() {
        delegate.invalidate();
    }

    public boolean isValid() {
        return delegate.isValid();
    }

    /**
     * Lowercase hex digest, or null when the download was not sequential or did
     * not cover {@code expectedTotalBytes}.
     *
     * Null is a normal outcome, not a failure: streamed video and preload write
     * out of order by design. ScanGate treats null as "hash the finished file".
     * What must never happen is returning a digest computed over reordered
     * bytes, because a wrong hash yields a confident verdict about a file
     * nobody actually looked at.
     */
    public String digestHexOrNull(long expectedTotalBytes) {
        if (expectedTotalBytes <= 0) {
            return delegate.digestHex(null);
        }
        return delegate.digestHex(expectedTotalBytes);
    }
}
