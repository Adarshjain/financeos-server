package com.financeos.domain.nps;

import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentLocalSearch;
import com.financeos.domain.instrument.InstrumentRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Maps a scheme name printed on an NPS statement to a catalog scheme code. First by the normalised NPS Trust
 * feed name; failing that by the same fund house (first word of its name), asset class, tier and plan variant.
 * A name that matches none, or more than one, is left unmapped.
 */
@Component
public class NpsSchemeResolver {

    private static final Pattern NPS_TRUST = Pattern.compile("\\bNPS TRUST\\b-?");
    private static final Pattern ACCOUNT = Pattern.compile("\\bA/C\\b");
    private static final Pattern LTD = Pattern.compile("\\bLTD\\b");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private final NpsSchemeRepository schemeRepository;
    private final InstrumentRepository instrumentRepository;

    public NpsSchemeResolver(NpsSchemeRepository schemeRepository, InstrumentRepository instrumentRepository) {
        this.schemeRepository = schemeRepository;
        this.instrumentRepository = instrumentRepository;
    }

    /** Upper case, "NPS TRUST" / "A/C" dropped, dashes and commas as spaces, LTD as LIMITED, spaces collapsed. */
    static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String s = name.toUpperCase(Locale.ROOT);
        s = NPS_TRUST.matcher(s).replaceAll(" ");
        s = ACCOUNT.matcher(s).replaceAll(" ");
        s = s.replace('-', ' ').replace(',', ' ').replace(".", "");
        s = LTD.matcher(s).replaceAll("LIMITED");
        return SPACES.matcher(s).replaceAll(" ").trim();
    }

    /** The printed names to their scheme codes; a name that could not be mapped is absent. */
    @Transactional(readOnly = true)
    public Map<String, String> resolve(Collection<String> names) {
        Map<String, String> out = new LinkedHashMap<>();
        if (names.isEmpty()) {
            return out;
        }
        List<NpsScheme> schemes = schemeRepository.findAll();
        Map<UUID, Instrument> instruments = new HashMap<>();
        for (List<UUID> chunk : InstrumentLocalSearch.chunks(schemes.stream().map(NpsScheme::getInstrumentId).toList())) {
            instrumentRepository.findAllById(chunk).forEach(i -> instruments.put(i.getId(), i));
        }
        Map<String, List<NpsScheme>> byName = new HashMap<>();
        for (NpsScheme s : schemes) {
            Instrument i = instruments.get(s.getInstrumentId());
            if (i != null) {
                byName.computeIfAbsent(normalize(i.getName()), k -> new ArrayList<>()).add(s);
            }
        }
        for (String name : names) {
            String code = resolveOne(name, schemes, byName);
            if (code != null) {
                out.put(name, code);
            }
        }
        return out;
    }

    private static String resolveOne(String name, List<NpsScheme> schemes, Map<String, List<NpsScheme>> byName) {
        String normalized = normalize(name);
        List<NpsScheme> exact = byName.getOrDefault(normalized, List.of());
        if (exact.size() == 1) {
            return exact.get(0).getSchemeCode();
        }
        if (exact.size() > 1) {
            return null;
        }
        NpsSchemeNameParser.Meta meta = NpsSchemeNameParser.parse(name);
        if (meta.assetClass() == null || meta.tier() == null) {
            return null;
        }
        List<String> matches = fallbackMatches(meta, normalized, schemes, false);
        if (matches.isEmpty() && (meta.variant() == NpsVariant.POP || meta.variant() == NpsVariant.NONE)) {
            // no exact plan variant for that fund house, class and tier: a POP name and a plain one are the same scheme
            matches = fallbackMatches(meta, normalized, schemes, true);
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static List<String> fallbackMatches(NpsSchemeNameParser.Meta meta, String normalized,
                                                List<NpsScheme> schemes, boolean popLikeNone) {
        List<String> matches = new ArrayList<>();
        for (NpsScheme s : schemes) {
            boolean sameVariant = meta.variant() == s.getVariant() || (popLikeNone && isPopOrNone(s.getVariant()));
            if (meta.assetClass().equals(s.getAssetClass()) && meta.tier() == s.getTier()
                    && sameVariant && samePfm(normalized, s.getPfmName())) {
                matches.add(s.getSchemeCode());
            }
        }
        return matches;
    }

    private static boolean isPopOrNone(NpsVariant v) {
        return v == NpsVariant.POP || v == NpsVariant.NONE;
    }

    /** The first word of the fund house's name (HDFC, ICICI, SBI...) appears as a word in the printed name. */
    private static boolean samePfm(String normalizedName, String pfmName) {
        String pfm = normalize(pfmName);
        if (pfm.isEmpty()) {
            return false;
        }
        String token = pfm.split(" ")[0];
        return token.length() >= 3 && (" " + normalizedName + " ").contains(" " + token + " ");
    }
}
