package uz.jac.secure.android;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

import uz.jac.secure.core.model.ScanMode;

/**
 * The only file in the integration layer that knows about Telegram's types.
 *
 * Isolating the coupling here means the scanner core stays a plain JVM library
 * that unit-tests without an Android SDK, and a Telegram upstream rename breaks
 * exactly one small file instead of the whole scanner.
 */
public final class TelegramContext {

    private TelegramContext() {
    }

    /**
     * Dialog this object belongs to, or 0 when there is no message context.
     *
     * Used to index scan state by conversation so the chat list can find a
     * verdict, and to resolve the scan mode at a link tap.
     */
    public static long dialogIdOf(Object parentObject) {
        if (!(parentObject instanceof MessageObject)) {
            return 0;
        }
        return ((MessageObject) parentObject).getDialogId();
    }

    /**
     * Scan mode for a conversation known only by its id.
     *
     * The message-based {@link #scanModeFor} is the one to prefer wherever a
     * MessageObject is in hand: it also catches self-destructing media inside
     * an otherwise normal cloud chat, which a dialog id cannot tell you about.
     * This overload exists for the call sites that genuinely have no message —
     * a link tapped in a chat's pinned header, for instance.
     *
     * Same fail-closed rule: id 0 means "we do not know which chat this is",
     * and an unknown chat is treated as a secret one.
     */
    public static ScanMode scanModeForDialog(long dialogId) {
        if (dialogId == 0) {
            return ScanMode.SECRET_CHAT;
        }
        return DialogObject.isEncryptedDialog(dialogId) ? ScanMode.SECRET_CHAT : ScanMode.NORMAL;
    }

    /**
     * Decide the scan mode for a downloaded file.
     *
     * Fails CLOSED: anything we cannot positively identify as a normal cloud
     * chat is treated as a secret chat, i.e. offline-only. Getting this
     * backwards would send a hash derived from self-destructing media to a
     * server. The API Terms say nothing about secret chats specifically; what
     * they do say is 1.1, that a client must "guard their users' privacy with
     * utmost care and comply with our Security Guidelines". Shipping a
     * fingerprint of content the user believes never left their device fails
     * that plainly. So when in doubt we lose a little detection capability
     * rather than leak.
     */
    public static ScanMode scanModeFor(Object parentObject) {
        if (!(parentObject instanceof MessageObject)) {
            // No message context at all: could be an avatar or a sticker, but it
            // could equally be something we failed to classify.
            return ScanMode.SECRET_CHAT;
        }
        MessageObject message = (MessageObject) parentObject;

        if (message.isSecretMedia()) {
            return ScanMode.SECRET_CHAT;
        }
        if (message.messageOwner != null && message.messageOwner.destroyTime != 0) {
            // Self-destructing media: nothing about it may be persisted.
            return ScanMode.SECRET_CHAT;
        }
        if (DialogObject.isEncryptedDialog(message.getDialogId())) {
            return ScanMode.SECRET_CHAT;
        }
        return ScanMode.NORMAL;
    }

    /**
     * MIME type as declared by the sender.
     *
     * Advisory only, and treated as hostile input everywhere downstream — the
     * sender chooses it, so it is a claim to be checked against the file's
     * actual leading bytes, never a fact.
     */
    static String declaredMimeOf(Object parentObject) {
        if (!(parentObject instanceof MessageObject)) {
            return null;
        }
        TLRPC.Document document = ((MessageObject) parentObject).getDocument();
        return document != null ? document.mime_type : null;
    }
}
