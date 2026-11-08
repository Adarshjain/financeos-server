package com.financeos.gmail.accountdetect.job;

import com.financeos.gmail.accountdetect.scan.ScannedMail;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** {@link AccountDetectStore} over maps, with the table's unique (user_id, message_id) rule. */
class InMemoryAccountDetectStore extends AccountDetectStore {

    final Map<UUID, AccountDetectState> states = new HashMap<>();
    final Map<UUID, List<AccountDetectFact>> rows = new HashMap<>();

    InMemoryAccountDetectStore() {
        super(null, null);
    }

    @Override
    public Optional<AccountDetectState> state(UUID userId) {
        return Optional.ofNullable(states.get(userId));
    }

    @Override
    public AccountDetectState save(AccountDetectState state) {
        states.put(state.getUserId(), state);
        return state;
    }

    int factsLoads;   // full loads of a user's facts

    @Override
    public List<StoredFact> facts(UUID userId) {
        factsLoads++;
        List<AccountDetectFact> rs = new ArrayList<>(rows.getOrDefault(userId, List.of()));
        rs.sort(Comparator.comparing(AccountDetectFact::getInternalDate, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(AccountDetectFact::getMessageId));
        List<StoredFact> out = new ArrayList<>();
        for (AccountDetectFact f : rs) out.add(new StoredFact(f.getMessageId(), f.getInternalDate(), FactsCodec.decode(f.getFacts())));
        return out;
    }

    @Override
    public List<StoredFact> facts(UUID userId, java.util.Collection<String> messageIds) {
        Set<String> want = new HashSet<>(messageIds);
        return factsOrdered(userId).stream().filter(f -> want.contains(f.messageId())).toList();
    }

    private List<StoredFact> factsOrdered(UUID userId) {
        int before = factsLoads;
        List<StoredFact> out = facts(userId);
        factsLoads = before;
        return out;
    }

    @Override
    public Set<String> messageIds(UUID userId) {
        Set<String> ids = new HashSet<>();
        for (AccountDetectFact f : rows.getOrDefault(userId, List.of())) ids.add(f.getMessageId());
        return ids;
    }

    @Override
    public void saveAll(UUID userId, List<ScannedMail> mails, Map<String, Integer> incompleteRunsBefore) {
        List<AccountDetectFact> mine = rows.computeIfAbsent(userId, k -> new ArrayList<>());
        mine.removeIf(f -> incompleteRunsBefore.containsKey(f.getMessageId()) && mails.stream().anyMatch(m -> m.messageId().equals(f.getMessageId())));
        Set<String> have = messageIds(userId);
        for (ScannedMail m : mails) {
            if (!have.add(m.messageId())) throw new IllegalStateException("duplicate (user_id, message_id)");
            mine.add(toRow(userId, m, incompleteRuns(m, incompleteRunsBefore)));
        }
    }

    @Override
    public void deleteForUser(UUID userId) {
        rows.remove(userId);
        states.remove(userId);
    }

    List<AccountDetectFact> rows(UUID userId) {
        return rows.getOrDefault(userId, List.of());
    }
}
