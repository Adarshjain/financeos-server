package com.financeos.domain.instrument;

import com.financeos.core.security.UserContext;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Per-user instrument overrides ({@link UserInstrumentOverride}): a user's own asset class and display
 * fields (name, symbol, exchange, currency, type) for an instrument. Every reader of an instrument the
 * user sees — the instrument API, positions, trades, dividends, realised lots, reports, tax harvesting —
 * applies the reading user's overrides over the shared catalog, so one user's edit never changes what
 * another user sees.
 */
@Service
@Transactional
public class AssetClassOverrideService {

    private final UserInstrumentOverrideRepository repository;

    public AssetClassOverrideService(UserInstrumentOverrideRepository repository) {
        this.repository = repository;
    }

    /** Every asset-class override of {@code userId}, by instrument id (one query; empty without a user). */
    @Transactional(readOnly = true)
    public Map<UUID, AssetClass> forUser(@Nullable UUID userId) {
        Map<UUID, AssetClass> out = new HashMap<>();
        if (userId == null) {
            return out;
        }
        for (UserInstrumentOverride o : repository.findByUserId(userId)) {
            if (o.getAssetClass() != null) {
                out.put(o.getInstrumentId(), o.getAssetClass());
            }
        }
        return out;
    }

    /** {@code userId}'s asset-class override of one instrument, or null. */
    @Nullable
    @Transactional(readOnly = true)
    public AssetClass forUser(@Nullable UUID userId, UUID instrumentId) {
        if (userId == null || instrumentId == null) {
            return null;
        }
        return repository.findByUserIdAndInstrumentId(userId, instrumentId)
                .map(UserInstrumentOverride::getAssetClass)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public Map<UUID, AssetClass> forCurrentUser() {
        return forUser(UserContext.getCurrentUserId());
    }

    @Nullable
    @Transactional(readOnly = true)
    public AssetClass forCurrentUser(UUID instrumentId) {
        return forUser(UserContext.getCurrentUserId(), instrumentId);
    }

    /** All of {@code userId}'s overrides (one query; {@link InstrumentOverrides#NONE} without a user). */
    @Transactional(readOnly = true)
    public InstrumentOverrides overridesFor(@Nullable UUID userId) {
        return userId == null ? InstrumentOverrides.NONE : InstrumentOverrides.of(repository.findByUserId(userId));
    }

    /** The current user's overrides ({@link InstrumentOverrides#NONE} outside a signed-in request). */
    @Transactional(readOnly = true)
    public InstrumentOverrides overridesForCurrentUser() {
        return overridesFor(UserContext.getCurrentUserId());
    }

    /** Pins {@code userId}'s class for the instrument, or with null removes it (other fields stay). */
    public void set(UUID userId, UUID instrumentId, @Nullable AssetClass assetClass) {
        var existing = repository.findByUserIdAndInstrumentId(userId, instrumentId);
        if (assetClass == null) {
            existing.ifPresent(row -> {
                row.setAssetClass(null);
                saveOrDelete(row);
            });
            return;
        }
        UserInstrumentOverride row = existing.orElseGet(() -> new UserInstrumentOverride(userId, instrumentId));
        row.setAssetClass(assetClass);
        repository.save(row);
    }

    /**
     * Makes {@code userId}'s view of {@code instrument} show the given display values: each one that
     * differs from the catalog is stored as an override, each one equal to the catalog (or blank)
     * clears that override. The asset-class override is kept.
     */
    public void setDisplay(UUID userId, Instrument instrument, @Nullable String name, @Nullable String symbol,
                           @Nullable String exchange, @Nullable String currency, @Nullable InstrumentType type) {
        setDisplay(userId, instrument, name, symbol, exchange, currency, type, DisplayChanges.ALL);
    }

    /**
     * {@link #setDisplay(UUID, Instrument, String, String, String, String, InstrumentType)} for the
     * fields {@code only} names; every other field keeps whatever override the user already has on
     * {@code instrument} (or none).
     */
    public void setDisplay(UUID userId, Instrument instrument, @Nullable String name, @Nullable String symbol,
                           @Nullable String exchange, @Nullable String currency, @Nullable InstrumentType type,
                           DisplayChanges only) {
        if (!only.any()) {
            return;
        }
        var existing = repository.findByUserIdAndInstrumentId(userId, instrument.getId());
        UserInstrumentOverride row = existing.orElseGet(() -> new UserInstrumentOverride(userId, instrument.getId()));
        if (only.name()) {
            row.setName(differing(name, instrument.getName(), false));
        }
        if (only.symbol()) {
            row.setSymbol(differing(symbol, instrument.getSymbol(), true));
        }
        if (only.exchange()) {
            row.setExchange(differing(exchange, instrument.getExchange(), true));
        }
        if (only.currency()) {
            row.setCurrency(differing(currency, instrument.getCurrency() != null ? instrument.getCurrency() : "INR", true));
        }
        if (only.type()) {
            row.setType(type != null && type != instrument.getType() ? type : null);
        }
        if (existing.isEmpty() && row.isEmpty()) {
            return;
        }
        saveOrDelete(row);
    }

    /** Removes every override {@code userId} has on the instrument; whether there was any. */
    public boolean clear(UUID userId, UUID instrumentId) {
        var existing = repository.findByUserIdAndInstrumentId(userId, instrumentId);
        existing.ifPresent(repository::delete);
        return existing.isPresent();
    }

    /**
     * Moves {@code userId}'s asset-class override from one instrument to another they now hold instead
     * (kept only when the target has none of its own), and drops their overrides on the source. Display
     * overrides are not carried: they were relative to the source's catalog values.
     */
    public void moveAssetClass(UUID userId, UUID fromInstrumentId, UUID toInstrumentId) {
        var from = repository.findByUserIdAndInstrumentId(userId, fromInstrumentId);
        if (from.isEmpty()) {
            return;
        }
        AssetClass carried = from.get().getAssetClass();
        repository.delete(from.get());
        repository.flush();
        if (carried != null && forUser(userId, toInstrumentId) == null) {
            set(userId, toInstrumentId, carried);
        }
    }

    private void saveOrDelete(UserInstrumentOverride row) {
        if (row.isEmpty()) {
            repository.delete(row);
        } else {
            repository.save(row);
        }
    }

    /** {@code value} trimmed when it is set and differs from {@code catalog}; else null (no override). */
    @Nullable
    static String differing(@Nullable String value, @Nullable String catalog, boolean ignoreCase) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        if (catalog != null) {
            String c = catalog.trim();
            if (ignoreCase ? v.toLowerCase(Locale.ROOT).equals(c.toLowerCase(Locale.ROOT)) : Objects.equals(v, c)) {
                return null;
            }
        }
        return v;
    }
}
