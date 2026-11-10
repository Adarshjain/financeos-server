package com.financeos.statement.parser.layout;

import com.financeos.statement.parser.Line;
import com.financeos.statement.parser.layout.profiles.AxisCardDtda2024;

import java.util.List;
import java.util.regex.Pattern;

/** The explicit, ordered list of layout profiles. The first one that matches a document wins. */
public final class LayoutRegistry {

    private final List<LayoutProfile> profiles;

    public LayoutRegistry(List<LayoutProfile> profiles) {
        this.profiles = List.copyOf(profiles);
    }

    /** Every profile the application knows, in matching order. */
    public static LayoutRegistry defaults() {
        return new LayoutRegistry(List.of(AxisCardDtda2024.PROFILE));
    }

    public List<LayoutProfile> profiles() {
        return profiles;
    }

    /**
     * The first profile whose issuer matches (or has none), whose required document text is present and whose table header
     * is found on some page; null when none does.
     */
    public LayoutProfile match(List<Line> lines, String issuerId) {
        return matchLazy(lines, () -> issuerId);
    }

    /** As {@link #match(List, String)} but the issuer is only looked up (once) when a profile's header and text matched. */
    public LayoutProfile matchLazy(List<Line> lines, java.util.function.Supplier<String> issuer) {
        boolean looked = false;
        String issuerId = null;
        for (LayoutProfile p : profiles) {
            if (!requiresPresent(lines, p) || !ProfiledReader.hasHeader(lines, p)) {
                continue;
            }
            if (p.issuerId() != null) {
                if (!looked) {
                    issuerId = issuer.get();
                    looked = true;
                }
                if (!p.issuerId().equalsIgnoreCase(issuerId)) {
                    continue;
                }
            }
            return p;
        }
        return null;
    }

    private static boolean requiresPresent(List<Line> lines, LayoutProfile p) {
        for (Pattern req : p.requires()) {
            boolean found = false;
            for (Line ln : lines) {
                if (req.matcher(ln.text()).find()) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }
}
