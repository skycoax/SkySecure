package uz.jac.secure.android;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import uz.jac.secure.core.model.ScanResult;
import uz.jac.secure.core.model.Signal;

/**
 * One structural finding, in the form the UI needs it.
 *
 * A flattened {@link Signal}: the stable code, plus the variable parts of the
 * sentence that describes it. It exists because the UI must not display the
 * scanner's own {@code detail} string.
 *
 * That string is written in English inside {@code jacsecure-core}, a plain
 * Kotlin module with no access to Android resources — and this product's
 * primary language is uz-Latn. Rendering {@code detail} would mean the single
 * most important sentence in the app, the one telling someone their bank app is
 * counterfeit, appears in the one language the target user is least likely to
 * read. So the code selects a localised template and these args fill it in.
 *
 * Deliberately not the {@link Signal} type itself. Signal carries a severity
 * the UI has no use for and an English string the UI must not print, and having
 * a separate type here means a future core field cannot silently become
 * something the UI renders.
 */
public final class ScanFinding {

    public final String code;
    /** Positional; arity per code is documented in {@link ScanUi}. */
    public final String[] args;

    public ScanFinding(String code, String[] args) {
        this.code = code;
        this.args = args != null ? args : new String[0];
    }

    static List<ScanFinding> of(ScanResult result) {
        List<Signal> signals = result.getSignals();
        List<ScanFinding> out = new ArrayList<>(signals.size());
        for (Signal signal : signals) {
            List<String> args = signal.getArgs();
            out.add(new ScanFinding(signal.getCode(), args.toArray(new String[0])));
        }
        return Collections.unmodifiableList(out);
    }

    /** The arg at {@code index}, or null — templates outlive the code that fills them. */
    public String arg(int index) {
        return index >= 0 && index < args.length ? args[index] : null;
    }

    @Override
    public String toString() {
        return code;
    }
}
