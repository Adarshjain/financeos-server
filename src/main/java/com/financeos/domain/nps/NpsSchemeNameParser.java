package com.financeos.domain.nps;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads asset class, tier and plan variant out of an NPS Trust scheme name; the file has no such columns. */
public final class NpsSchemeNameParser {

    /** What a name says; every field may be absent (null class / tier, NONE variant). */
    public record Meta(String assetClass, NpsSchemeTier tier, NpsVariant variant) {
    }

    private static final Pattern ASSET_CLASS = Pattern.compile("\\bSCHEME\\s+([ECGA])\\b");
    private static final Pattern TIER_II = Pattern.compile("\\bTIER\\s*II\\b");
    private static final Pattern TIER_I = Pattern.compile("\\bTIER\\s*I\\b");
    private static final Pattern TAX_SAVER = Pattern.compile("\\bTAX\\s+SAVER\\b");
    private static final Pattern TRAILING_VARIANT = Pattern.compile("\\b(DIRECT|GS|POP)\\s*$");

    private NpsSchemeNameParser() {
    }

    public static Meta parse(String schemeName) {
        String name = schemeName == null ? "" : schemeName.toUpperCase(Locale.ROOT).trim();
        Matcher cls = ASSET_CLASS.matcher(name);
        String assetClass = cls.find() ? cls.group(1) : null;
        NpsSchemeTier tier = null;
        if (TAX_SAVER.matcher(name).find()) {
            tier = NpsSchemeTier.TTS;
        } else if (TIER_II.matcher(name).find()) {
            tier = NpsSchemeTier.II;
        } else if (TIER_I.matcher(name).find()) {
            tier = NpsSchemeTier.I;
        }
        NpsVariant variant = NpsVariant.NONE;
        Matcher v = TRAILING_VARIANT.matcher(name);
        if (v.find()) {
            variant = NpsVariant.valueOf(v.group(1));
        }
        return new Meta(assetClass, tier, variant);
    }

    /** The fund-house id the history URL wants: "PFM" plus digits 3-5 of the scheme code (SM008001 gives PFM008). */
    public static String pfmCode(String schemeCode) {
        if (schemeCode == null || schemeCode.length() < 5) {
            return null;
        }
        return "PFM" + schemeCode.substring(2, 5);
    }
}
