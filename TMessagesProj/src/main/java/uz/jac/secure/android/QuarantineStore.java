package uz.jac.secure.android;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * App-private quarantine.
 *
 * A quarantined file stays inside {@code getNoBackupFilesDir()/jac-quarantine},
 * which is:
 *
 *   * not on external storage, so no other app can read it;
 *   * not indexed by MediaStore, so it never appears in the gallery or a file
 *     manager;
 *   * excluded from Android auto-backup, so a malicious sample is not
 *     replicated into the user's cloud backup and restored onto their next
 *     phone.
 *
 * The last point is easy to miss and genuinely matters — quarantining a file
 * into a backed-up directory just gives the malware a second life.
 *
 * Quarantine is reversible on purpose. Telegram ToS 1.3 requires that all
 * standard client features keep working, so "Open anyway" must exist;
 * {@link #release} is how a file leaves quarantine after the user has passed
 * the two-step warning.
 */
public final class QuarantineStore {

    private static final String DIR_NAME = "jac-quarantine";

    private final File root;

    public QuarantineStore(Context context) {
        // getNoBackupFilesDir is the point: app-private AND excluded from
        // Android's automatic cloud backup.
        this.root = new File(context.getNoBackupFilesDir(), DIR_NAME);
        //noinspection ResultOfMethodCallIgnored
        this.root.mkdirs();
    }

    public boolean isQuarantined(File file) {
        if (file == null) {
            return false;
        }
        try {
            return file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Move {@code source} into quarantine, keyed by its SHA-256.
     *
     * Returns the quarantined file, or the original if the move failed — a
     * failure to quarantine must not lose the user's file, and the verdict is
     * still surfaced either way.
     */
    public File quarantine(File source, String sha256) {
        if (source == null || !source.exists()) {
            return source;
        }
        // The name is derived from the hash, never from the sender-supplied
        // filename: that name may contain path separators, bidi overrides, or
        // 4 KB of padding, none of which belong in a path we construct.
        String name = (sha256 != null && sha256.length() == 64 ? sha256 : String.valueOf(source.getName().hashCode()))
                + ".quarantine";
        File target = new File(root, name);

        try {
            try {
                Files.move(source.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Cache and no-backup dirs can land on different volumes.
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            return source;
        }

        //noinspection ResultOfMethodCallIgnored
        target.setReadable(true, true);
        return target;
    }

    /**
     * Move a file back out of quarantine after an explicit user override.
     *
     * Called only from the second step of the two-step warning — never
     * automatically, and never as a side effect of rendering a message.
     */
    public File release(File quarantined, File destination) {
        if (quarantined == null || !quarantined.exists()) {
            return quarantined;
        }
        try {
            File parent = destination.getParentFile();
            if (parent != null) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            Files.move(quarantined.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return destination;
        } catch (IOException e) {
            return quarantined;
        }
    }

    /** Delete a quarantined sample. */
    public boolean discard(File quarantined) {
        return isQuarantined(quarantined) && quarantined.delete();
    }

    public File getRoot() {
        return root;
    }
}
