package com.financeos.domain.instrument;

import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One user's instrument overrides ({@link UserInstrumentOverride}), loaded once and applied to every
 * instrument that user reads: each overridden display field replaces the shared catalog value, and
 * the asset / tax classification follows the user's asset class or, failing that, their type.
 * Immutable; {@link #NONE} applies nothing (no user, or a user without overrides).
 */
public final class InstrumentOverrides {

    /** Field names as reported in {@code InstrumentResponse.overriddenFields}. */
    public static final String NAME = "name";
    public static final String SYMBOL = "symbol";
    public static final String EXCHANGE = "exchange";
    public static final String CURRENCY = "currency";
    public static final String TYPE = "type";
    public static final String ASSET_CLASS = "assetClass";

    public static final InstrumentOverrides NONE = new InstrumentOverrides(Map.of());

    /** The overridden values of one instrument (each null = the catalog value applies). */
    public record Fields(@Nullable String name, @Nullable String symbol, @Nullable String exchange,
                         @Nullable String currency, @Nullable InstrumentType type,
                         @Nullable AssetClass assetClass) {

        static Fields of(UserInstrumentOverride row) {
            return new Fields(row.getName(), row.getSymbol(), row.getExchange(), row.getCurrency(), row.getType(),
                    row.getAssetClass());
        }

        /** The overridden fields, in a stable order. */
        public List<String> names() {
            List<String> out = new ArrayList<>(6);
            if (name != null) {
                out.add(NAME);
            }
            if (symbol != null) {
                out.add(SYMBOL);
            }
            if (exchange != null) {
                out.add(EXCHANGE);
            }
            if (currency != null) {
                out.add(CURRENCY);
            }
            if (type != null) {
                out.add(TYPE);
            }
            if (assetClass != null) {
                out.add(ASSET_CLASS);
            }
            return out;
        }
    }

    private final Map<UUID, Fields> byInstrument;

    private InstrumentOverrides(Map<UUID, Fields> byInstrument) {
        this.byInstrument = byInstrument;
    }

    public static InstrumentOverrides of(@Nullable Collection<UserInstrumentOverride> rows) {
        if (rows == null || rows.isEmpty()) {
            return NONE;
        }
        Map<UUID, Fields> map = new HashMap<>();
        for (UserInstrumentOverride row : rows) {
            if (row != null && row.getInstrumentId() != null && !row.isEmpty()) {
                map.put(row.getInstrumentId(), Fields.of(row));
            }
        }
        return map.isEmpty() ? NONE : new InstrumentOverrides(Map.copyOf(map));
    }

    /**
     * These overrides with {@code instrumentId}'s asset class set to {@code assetClass} (null removes
     * it); the other fields and instruments are kept.
     */
    public InstrumentOverrides withAssetClass(UUID instrumentId, @Nullable AssetClass assetClass) {
        Map<UUID, Fields> map = new HashMap<>(byInstrument);
        Fields f = map.get(instrumentId);
        Fields next = f == null
                ? new Fields(null, null, null, null, null, assetClass)
                : new Fields(f.name(), f.symbol(), f.exchange(), f.currency(), f.type(), assetClass);
        if (next.names().isEmpty()) {
            map.remove(instrumentId);
        } else {
            map.put(instrumentId, next);
        }
        return map.isEmpty() ? NONE : new InstrumentOverrides(Map.copyOf(map));
    }

    /** Null-safe: {@code overrides}, or {@link #NONE} when absent (e.g. from an unstubbed mock). */
    public static InstrumentOverrides orNone(@Nullable InstrumentOverrides overrides) {
        return overrides != null ? overrides : NONE;
    }

    public boolean isEmpty() {
        return byInstrument.isEmpty();
    }

    /** The instruments with any override. */
    public java.util.Set<UUID> instrumentIds() {
        return byInstrument.keySet();
    }

    /** The overrides of one instrument, or null when it has none. */
    @Nullable
    public Fields get(@Nullable UUID instrumentId) {
        return instrumentId == null ? null : byInstrument.get(instrumentId);
    }

    /** Names of the fields overridden for the instrument (empty when none). */
    public List<String> overriddenFields(@Nullable UUID instrumentId) {
        Fields f = get(instrumentId);
        return f == null ? List.of() : f.names();
    }

    public String name(Instrument instrument) {
        Fields f = get(instrument.getId());
        return f != null && f.name() != null ? f.name() : instrument.getName();
    }

    @Nullable
    public String symbol(Instrument instrument) {
        Fields f = get(instrument.getId());
        return f != null && f.symbol() != null ? f.symbol() : instrument.getSymbol();
    }

    @Nullable
    public String exchange(Instrument instrument) {
        Fields f = get(instrument.getId());
        return f != null && f.exchange() != null ? f.exchange() : instrument.getExchange();
    }

    public String currency(Instrument instrument) {
        Fields f = get(instrument.getId());
        if (f != null && f.currency() != null) {
            return f.currency();
        }
        return instrument.getCurrency() != null ? instrument.getCurrency() : "INR";
    }

    public InstrumentType type(Instrument instrument) {
        Fields f = get(instrument.getId());
        return f != null && f.type() != null ? f.type() : instrument.getType();
    }

    /** The user's own asset class for the instrument, or null. */
    @Nullable
    public AssetClass assetClass(@Nullable UUID instrumentId) {
        Fields f = get(instrumentId);
        return f != null ? f.assetClass() : null;
    }

    /** The instrument's asset and tax class as the user sees it (see {@link AssetClassifier#effective}). */
    public AssetClassifier.Classification classification(Instrument instrument) {
        Fields f = get(instrument.getId());
        return f == null
                ? AssetClassifier.effective(instrument)
                : AssetClassifier.effective(instrument, f.type(), f.assetClass());
    }

    /** The user's asset classes by instrument (the instruments with one only). */
    public Map<UUID, AssetClass> assetClasses() {
        Map<UUID, AssetClass> out = new HashMap<>();
        byInstrument.forEach((id, f) -> {
            if (f.assetClass() != null) {
                out.put(id, f.assetClass());
            }
        });
        return out;
    }
}
