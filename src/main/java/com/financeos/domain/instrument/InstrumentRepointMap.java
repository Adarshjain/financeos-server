package com.financeos.domain.instrument;

import com.financeos.core.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Where a user's imports of an instrument go after they changed its identifiers
 * ({@link UserInstrumentRepoint}). Every import path (preview, commit, broker reconciliation) applies
 * {@link #apply} after it matched an instrument by any means — ISIN, AMFI code, Yahoo symbol, ticker or
 * alias — so a re-import of a repointed instrument lands on (and is de-duplicated against) the holding
 * the user now has, not the shared row they moved off.
 */
@Service
@Transactional
public class InstrumentRepointMap {

    private static final Logger log = LoggerFactory.getLogger(InstrumentRepointMap.class);

    /** More hops than any flat map has; only a corrupt cycle would reach it. */
    private static final int MAX_HOPS = 16;

    private final UserInstrumentRepointRepository repository;
    private final InstrumentRepository instrumentRepository;

    public InstrumentRepointMap(UserInstrumentRepointRepository repository, InstrumentRepository instrumentRepository) {
        this.repository = repository;
        this.instrumentRepository = instrumentRepository;
    }

    /** The instrument {@code userId}'s imports of {@code instrumentId} go to; {@code instrumentId} when none. */
    @Transactional(readOnly = true)
    public UUID resolve(@Nullable UUID userId, @Nullable UUID instrumentId) {
        if (userId == null || instrumentId == null) {
            return instrumentId;
        }
        UUID current = instrumentId;
        Set<UUID> seen = new HashSet<>();
        for (int hop = 0; hop < MAX_HOPS && seen.add(current); hop++) {
            Optional<UserInstrumentRepoint> next = repository.findByUserIdAndFromInstrumentId(userId, current);
            if (next.isEmpty()) {
                return current;
            }
            current = next.get().getToInstrumentId();
        }
        log.warn("Instrument repoint chain of user {} from {} does not end; using {}", userId, instrumentId, current);
        return current;
    }

    /** {@code instrument} as {@code userId}'s imports see it: the instrument they were moved to, else itself. */
    @Nullable
    @Transactional(readOnly = true)
    public Instrument apply(@Nullable UUID userId, @Nullable Instrument instrument) {
        if (userId == null || instrument == null || instrument.getId() == null) {
            return instrument;
        }
        UUID target = resolve(userId, instrument.getId());
        if (target.equals(instrument.getId())) {
            return instrument;
        }
        return instrumentRepository.findById(target).orElse(instrument);
    }

    /** {@link #apply} for the signed-in user. */
    @Nullable
    @Transactional(readOnly = true)
    public Instrument applyForCurrentUser(@Nullable Instrument instrument) {
        return apply(UserContext.getCurrentUserId(), instrument);
    }

    /**
     * Records that {@code userId} moved from one instrument to another. The target is never itself
     * mapped away (a move back onto an instrument they left removes that record and adds none: a
     * revert, not a new move); earlier moves onto {@code from} are re-aimed at {@code to}, keeping
     * the map flat.
     */
    public void record(UUID userId, UUID from, UUID to) {
        if (from.equals(to)) {
            return;
        }
        boolean revert = false;
        Optional<UserInstrumentRepoint> leaving = repository.findByUserIdAndFromInstrumentId(userId, to);
        if (leaving.isPresent()) {
            revert = from.equals(leaving.get().getToInstrumentId());
            repository.delete(leaving.get());
            repository.flush();
        }
        for (UserInstrumentRepoint earlier : repository.findByUserIdAndToInstrumentId(userId, from)) {
            earlier.setToInstrumentId(to);
            repository.save(earlier);
        }
        if (!revert) {
            UserInstrumentRepoint row = repository.findByUserIdAndFromInstrumentId(userId, from)
                    .orElseGet(() -> new UserInstrumentRepoint(userId, from, to));
            row.setToInstrumentId(to);
            repository.save(row);
        }
        repository.flush();
    }
}
