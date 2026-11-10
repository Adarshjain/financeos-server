package com.financeos.statement.parser.layout;

import com.financeos.statement.parser.Line;
import com.financeos.statement.parser.layout.profiles.AxisCardDtda2022;
import com.financeos.statement.parser.layout.profiles.HdfcCardDtda2018;
import com.financeos.statement.parser.layout.profiles.HdfcCardDtda2024;
import com.financeos.statement.parser.layout.profiles.HdfcCardDtdap2022;
import com.financeos.statement.parser.layout.profiles.AxisCardDtda2024;
import com.financeos.statement.parser.layout.profiles.AxisCardDtdace2021;
import com.financeos.statement.parser.layout.profiles.AxisCardDtdace2024;
import com.financeos.statement.parser.layout.profiles.HdfcCardDttda2025;
import com.financeos.statement.parser.layout.profiles.HdfcCardDttdra2025;
import com.financeos.statement.parser.layout.profiles.HdfcCardDttdra2026;
import com.financeos.statement.parser.layout.profiles.YesCardDtda2025;
import com.financeos.statement.parser.layout.profiles.HdfcBankTdnwdcb2025;
import com.financeos.statement.parser.layout.profiles.IciciCardSdtdaa2021;
import com.financeos.statement.parser.layout.profiles.IciciCardSdtdra2023;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.regex.Pattern;

/** The explicit, ordered list of layout profiles. The first one that matches a document wins. */
public final class LayoutRegistry {

    private final List<LayoutProfile> profiles;

    public LayoutRegistry(List<LayoutProfile> profiles) {
        this.profiles = List.copyOf(profiles);
    }

    /** A named way to build a profile; the name is what an ERROR log line says when the build fails. */
    public record Entry(String name, Supplier<LayoutProfile> supplier) {
    }

    private static final Logger log = LoggerFactory.getLogger(LayoutRegistry.class);

    /** The application's profiles, built on first use (not in a static initializer on the parsing path). */
    private static final class Holder {
        static final LayoutRegistry DEFAULTS = fromEntries(List.of(
                new Entry("AxisCardDtda2024", AxisCardDtda2024::build),
                new Entry("AxisCardDtda2022", AxisCardDtda2022::build),
                new Entry("AxisCardDtdace2024a", AxisCardDtdace2024::buildA),
                new Entry("AxisCardDtdace2024b", AxisCardDtdace2024::buildB),
                new Entry("AxisCardDtdace2021", AxisCardDtdace2021::build),
                new Entry("YesCardDtda2025", YesCardDtda2025::build),
                new Entry("HdfcBankTdnwdcb2025", HdfcBankTdnwdcb2025::build),
                new Entry("IciciCardSdtdra2023", IciciCardSdtdra2023::build),
                new Entry("IciciCardSdtdaa2021", IciciCardSdtdaa2021::build),
                new Entry("HdfcCardDttdra2025", HdfcCardDttdra2025::build),
                new Entry("HdfcCardDttda2025", HdfcCardDttda2025::build),
                new Entry("HdfcCardDttdra2026", HdfcCardDttdra2026::build),
                new Entry("HdfcCardDtdap2022", HdfcCardDtdap2022::build),
                new Entry("HdfcCardDtda2018", HdfcCardDtda2018::build),
                new Entry("HdfcCardDtda2024", HdfcCardDtda2024::build)));
    }

    /** Every profile the application knows, in matching order. Never throws; a profile that fails to build is skipped. */
    public static LayoutRegistry defaults() {
        return Holder.DEFAULTS;
    }

    /**
     * Builds each profile inside its own try/catch (any Throwable but OutOfMemoryError): a profile that fails is logged
     * at ERROR by name and skipped, the others are kept.
     */
    public static LayoutRegistry fromEntries(List<Entry> entries) {
        List<LayoutProfile> built = new ArrayList<>();
        for (Entry e : entries) {
            try {
                LayoutProfile p = e.supplier().get();
                if (p != null) {
                    built.add(p);
                }
            } catch (OutOfMemoryError oom) {
                throw oom;
            } catch (Throwable t) {
                log.error("layout profile {} failed to build ({}) and is skipped", e.name(), t.getClass().getSimpleName());
            }
        }
        return new LayoutRegistry(built);
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
    public LayoutProfile matchLazy(List<Line> lines, Supplier<String> issuer) {
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
        for (Pattern bad : p.requiresNot()) {
            for (Line ln : lines) {
                if (bad.matcher(ln.text()).find()) {
                    return false;
                }
            }
        }
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
