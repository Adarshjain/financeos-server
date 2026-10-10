package com.financeos.domain.instrument;

import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pure rules that place an instrument in an {@link AssetClass} and a {@link TaxClass}.
 *
 * <ul>
 *   <li>Stocks are {@link AssetClass#EQUITY}.</li>
 *   <li>Mutual funds follow their AMFI scheme-category header (e.g.
 *       {@code "Open Ended Schemes(Equity Scheme - Large Cap Fund)"}): equity schemes are EQUITY,
 *       debt schemes DEBT, hybrid and solution-oriented schemes HYBRID, FoF Overseas INTERNATIONAL,
 *       Gold ETFs GOLD; index funds, other ETFs and domestic FoFs go by their name. A fund without a
 *       known category goes by its name too, else OTHER.</li>
 *   <li>ETFs go by their name (GOLD → GOLD, LIQUID/GILT/BOND/… → DEBT, NASDAQ/S&amp;P 500/HANG SENG/… →
 *       INTERNATIONAL, NIFTY/SENSEX/… → EQUITY), defaulting to EQUITY.</li>
 * </ul>
 * The tax class follows from the asset class: EQUITY → EQUITY_ORIENTED, DEBT → SPECIFIED_DEBT,
 * HYBRID → EQUITY_ORIENTED for aggressive / arbitrage / equity savings / balanced advantage / dynamic
 * asset allocation schemes and OTHER for the rest (conservative, balanced, multi-asset), everything
 * else OTHER. Listed shares are always EQUITY_ORIENTED.
 */
public final class AssetClassifier {

    /** An asset class with the tax class that goes with it. */
    public record Classification(AssetClass assetClass, TaxClass taxClass) {
    }

    /** "Open Ended Schemes(…)", "Close Ended Schemes (…)", "Interval Fund Schemes(…)". */
    private static final Pattern CATEGORY_HEADER = Pattern.compile(
            "(?i)^\\s*(open\\s*ended|close\\s*ended|interval\\s*fund)\\s+schemes?\\s*\\(.+\\)\\s*$");

    private static final List<String> EQUITY_ORIENTED_HYBRID = List.of(
            "aggressive", "arbitrage", "equity savings", "balanced advantage", "dynamic asset allocation");

    private static final Pattern GOLD = word("GOLD");
    private static final Pattern SILVER = word("SILVER");
    private static final Pattern INTERNATIONAL = Pattern.compile(
            "(NASDAQ|S&P\\s*500|HANG\\s*SENG|HANGSENG|\\bFANG|\\bMSCI\\b|\\bUSA?\\b|\\bU\\.S\\.|\\bGLOBAL\\b"
                    + "|\\bINTERNATIONAL\\b|\\bOVERSEAS\\b|\\bWORLD\\b|\\bCHINA\\b|\\bJAPAN\\b|\\bTAIWAN\\b"
                    + "|\\bEUROPE|\\bEMERGING\\s+MARKET|\\bGREATER\\s+CHINA)");
    private static final Pattern HYBRID = Pattern.compile(
            "(\\bHYBRID\\b|BALANCED\\s+ADVANTAGE|DYNAMIC\\s+ASSET|MULTI[\\s-]*ASSET|ASSET\\s+ALLOCATION|\\bARBITRAGE\\b"
                    + "|EQUITY\\s+SAVINGS|EQUITY\\s*(&|AND)\\s*DEBT)");
    private static final Pattern DEBT = Pattern.compile(
            "(\\bLIQUID|\\bGILT\\b|\\bG-?SEC|\\bSDL\\b|\\bBONDS?\\b|\\bIBX\\b|\\bTREASURY|\\bT-?BILL|MONEY\\s+MARKET"
                    + "|\\bOVERNIGHT\\b|\\bDEBT\\b|TARGET\\s+MATURITY|\\bDURATION\\b|CREDIT\\s+RISK|BANKING\\s*(&|AND)\\s*PSU"
                    + "|\\bFLOATER\\b|\\b1D\\s+RATE\\b)");
    private static final Pattern EQUITY = Pattern.compile(
            "(\\bNIFTY|\\bSENSEX\\b|\\bBSE\\b|\\bNSE\\b|\\bEQUITY\\b|\\bELSS\\b|TAX\\s*SAVER|BLUE\\s*CHIP"
                    + "|(LARGE|MID|SMALL|FLEXI|MULTI)\\s*-?\\s*CAP|\\bFOCUSED\\b|\\bVALUE\\b|\\bCONTRA\\b|DIVIDEND\\s+YIELD"
                    + "|\\bMOMENTUM\\b|\\bQUALITY\\b|\\bALPHA\\b|\\bLOW\\s+VOL|\\bCPSE\\b|\\bPSU\\s+BANK|\\bBANK\\b|\\bPHARMA\\b"
                    + "|\\bINFRA|\\bCONSUMPTION\\b|\\bMNC\\b|\\bMANUFACTURING\\b|\\bTHEMATIC\\b|\\bSECTOR|\\bINDEX\\b)");

    private AssetClassifier() {
    }

    private static Pattern word(String w) {
        return Pattern.compile("\\b" + w + "\\b");
    }

    /** Whether an AMFI NAV feed line (one without ';') is a scheme-category header. */
    public static boolean isCategoryHeader(@Nullable String line) {
        return line != null && !line.contains(";") && CATEGORY_HEADER.matcher(line).matches();
    }

    /** The asset class an AMFI scheme-category header implies; {@code name} settles the mixed ones. */
    public static AssetClass fromSchemeCategory(String category, @Nullable String name) {
        String c = category.toLowerCase(Locale.ROOT);
        if (c.contains("overseas")) {
            return AssetClass.INTERNATIONAL;
        }
        if (c.contains("gold etf")) {
            return AssetClass.GOLD;
        }
        if (c.contains("equity scheme") || c.contains("elss")) {
            return AssetClass.EQUITY;
        }
        if (c.contains("debt scheme") || c.contains("income") || c.contains("money market")
                || c.contains("liquid") || c.contains("gilt")) {
            return AssetClass.DEBT;
        }
        if (c.contains("hybrid scheme") || c.contains("solution oriented")) {
            return AssetClass.HYBRID;
        }
        AssetClass byName = fromName(name);
        if (byName != null) {
            return byName;
        }
        // Index funds and "other" ETFs are overwhelmingly equity index trackers.
        if (c.contains("index fund") || c.contains("etf")) {
            return AssetClass.EQUITY;
        }
        return AssetClass.OTHER;
    }

    /** The asset class an instrument's name implies, or null when nothing in it is telling. */
    @Nullable
    public static AssetClass fromName(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String n = name.toUpperCase(Locale.ROOT);
        if (GOLD.matcher(n).find()) {
            return AssetClass.GOLD;
        }
        if (SILVER.matcher(n).find()) {
            return AssetClass.OTHER;
        }
        if (INTERNATIONAL.matcher(n).find()) {
            return AssetClass.INTERNATIONAL;
        }
        if (HYBRID.matcher(n).find()) {
            return AssetClass.HYBRID;
        }
        if (DEBT.matcher(n).find()) {
            return AssetClass.DEBT;
        }
        if (EQUITY.matcher(n).find()) {
            return AssetClass.EQUITY;
        }
        return null;
    }

    /** The rule-based asset class (no manual override): see the class doc. */
    public static AssetClass classify(@Nullable InstrumentType type, @Nullable String schemeCategory,
                                      @Nullable String name) {
        if (type == InstrumentType.stock) {
            return AssetClass.EQUITY;
        }
        if (type == InstrumentType.mutual_fund) {
            if (schemeCategory != null && !schemeCategory.isBlank()) {
                return fromSchemeCategory(schemeCategory, name);
            }
            AssetClass byName = fromName(name);
            return byName != null ? byName : AssetClass.OTHER;
        }
        AssetClass byName = fromName(name);
        return byName != null ? byName : AssetClass.EQUITY;
    }

    /** The tax class that goes with {@code assetClass}: see the class doc. */
    public static TaxClass taxClass(@Nullable InstrumentType type, @Nullable AssetClass assetClass,
                                    @Nullable String schemeCategory, @Nullable String name) {
        if (type == InstrumentType.stock) {
            return TaxClass.EQUITY_ORIENTED;
        }
        if (assetClass == null) {
            return TaxClass.OTHER;
        }
        return switch (assetClass) {
            case EQUITY -> TaxClass.EQUITY_ORIENTED;
            case DEBT -> TaxClass.SPECIFIED_DEBT;
            case HYBRID -> isEquityOrientedHybrid(schemeCategory != null && !schemeCategory.isBlank()
                    ? schemeCategory : name) ? TaxClass.EQUITY_ORIENTED : TaxClass.OTHER;
            default -> TaxClass.OTHER;
        };
    }

    private static boolean isEquityOrientedHybrid(@Nullable String text) {
        if (text == null) {
            return false;
        }
        String t = text.toLowerCase(Locale.ROOT);
        return EQUITY_ORIENTED_HYBRID.stream().anyMatch(t::contains);
    }

    /** The instrument's global asset class: the stored one (AMFI or rule), else the rules applied now. */
    public static AssetClass effectiveAssetClass(Instrument instrument) {
        if (instrument.getAssetClass() != null) {
            return instrument.getAssetClass();
        }
        return classify(instrument.getType(), instrument.getSchemeCategory(), instrument.getName());
    }

    /** The instrument's effective asset class with its tax class (no per-user override). */
    public static Classification effective(Instrument instrument) {
        return effective(instrument, null);
    }

    /**
     * The instrument's effective asset class with its tax class for one user: their
     * {@code override} ({@link UserInstrumentOverride}) when they pinned one, else the global
     * classification ({@link #effectiveAssetClass}). The tax class follows the resulting asset class.
     */
    public static Classification effective(Instrument instrument, @Nullable AssetClass override) {
        return effective(instrument, null, override);
    }

    /**
     * The classification for one user who may also have overridden the instrument's type: their
     * asset class when pinned; else, when their type differs from the catalog's, the rules applied to
     * their type (the AMFI category only counts for a mutual fund); else the global classification.
     * The tax class follows the user's type and the resulting asset class.
     */
    public static Classification effective(Instrument instrument, @Nullable InstrumentType typeOverride,
                                           @Nullable AssetClass override) {
        InstrumentType type = typeOverride != null ? typeOverride : instrument.getType();
        boolean retyped = type != instrument.getType();
        String category = type == InstrumentType.mutual_fund ? instrument.getSchemeCategory() : null;
        AssetClass assetClass;
        if (override != null) {
            assetClass = override;
        } else if (retyped) {
            assetClass = classify(type, category, instrument.getName());
        } else {
            assetClass = effectiveAssetClass(instrument);
        }
        return new Classification(assetClass,
                taxClass(type, assetClass, retyped ? category : instrument.getSchemeCategory(), instrument.getName()));
    }
}
