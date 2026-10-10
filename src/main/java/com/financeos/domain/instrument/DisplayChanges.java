package com.financeos.domain.instrument;

import com.financeos.api.instrument.dto.InstrumentRequest;
import org.springframework.lang.Nullable;

import java.util.Locale;
import java.util.Objects;

/**
 * Which display fields (name, symbol, exchange, currency, type) an edit actually changed, compared with
 * how the editing user saw the instrument (their overrides applied). An identifier edit moves the user
 * to another catalog instrument; only these changes follow them there — a value they merely left as it
 * was is not theirs to pin over the other instrument's own.
 */
public record DisplayChanges(boolean name, boolean symbol, boolean exchange, boolean currency, boolean type) {

    public static final DisplayChanges ALL = new DisplayChanges(true, true, true, true, true);
    public static final DisplayChanges NONE = new DisplayChanges(false, false, false, false, false);

    /** The fields of {@code request} that differ from {@code instrument} as {@code view} shows it. */
    public static DisplayChanges between(InstrumentOverrides view, Instrument instrument, InstrumentRequest request) {
        return new DisplayChanges(
                !same(request.name(), view.name(instrument), false),
                !same(request.symbol(), view.symbol(instrument), true),
                !same(request.exchange(), view.exchange(instrument), true),
                // A blank currency means "the catalog's" (no override), so it is no change of its own.
                request.currency() != null && !request.currency().isBlank()
                        && !same(request.currency(), view.currency(instrument), true),
                request.type() != null && request.type() != view.type(instrument));
    }

    /** Blank and null are the same (nothing entered); else trimmed, optionally ignoring case. */
    static boolean same(@Nullable String requested, @Nullable String seen, boolean ignoreCase) {
        String a = requested == null || requested.isBlank() ? null : requested.trim();
        String b = seen == null || seen.isBlank() ? null : seen.trim();
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return ignoreCase ? a.toLowerCase(Locale.ROOT).equals(b.toLowerCase(Locale.ROOT)) : Objects.equals(a, b);
    }

    public boolean any() {
        return name || symbol || exchange || currency || type;
    }
}
