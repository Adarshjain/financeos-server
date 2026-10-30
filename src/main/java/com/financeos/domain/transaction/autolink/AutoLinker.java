package com.financeos.domain.transaction.autolink;

import java.util.Set;
import java.util.UUID;

/**
 * One kind of automatic linking. {@link AutoLinkService} runs every registered linker in order, each in
 * its own transaction, with the user context set. A linker links the pairings that are unambiguous and
 * stores a suggestion for the rest; it owns (and recomputes) the suggestions of {@link #ownedKinds()}.
 */
public interface AutoLinker {

    /** The suggestion kinds this linker recomputes on every pass. */
    Set<SuggestionKind> ownedKinds();

    /** Runs one pass for {@code userId}. May throw; the service logs it and moves on to the next linker. */
    AutoLinkOutcome link(UUID userId);
}
