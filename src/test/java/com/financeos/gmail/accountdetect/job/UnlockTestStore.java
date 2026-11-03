package com.financeos.gmail.accountdetect.job;

import com.financeos.gmail.accountdetect.StatementContent;
import com.financeos.gmail.accountdetect.job.AccountDetectFact.UnlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** {@link InMemoryAccountDetectStore} plus the retry reads and writes (generations, cursor stamps, holder flags). */
class UnlockTestStore extends InMemoryAccountDetectStore {

    @Override
    public void requestUnlockRetry(UUID userId, boolean nameChanged) {
        state(userId).ifPresent(s -> {
            s.setUnlockGen(s.getUnlockGen() + 1);
            if (nameChanged) s.setHolderGen(s.getHolderGen() + 1);
        });
    }

    private static final Comparator<AccountDetectFact> NEWEST_FIRST = Comparator
            .comparing(AccountDetectFact::getInternalDate, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(AccountDetectFact::getMessageId);

    @Override
    public List<LockedFact> lockedFacts(UUID userId, int gen) {
        List<LockedFact> out = new ArrayList<>();
        for (AccountDetectFact f : rows(userId).stream().sorted(NEWEST_FIRST).toList()) {
            if ((f.getUnlockState() == UnlockState.LOCKED || f.getUnlockState() == UnlockState.FAILED) && f.getPdfAttachmentId() != null
                    && f.getUnlockTriedGen() < gen) {
                out.add(new LockedFact(f.getMessageId(), f.getInternalDate(), f.getPdfAttachmentId(), f.getUnlockHint(), FactsCodec.decode(f.getFacts())));
            }
        }
        return out;
    }

    @Override
    public List<OpenedFact> openedFacts(UUID userId, int gen) {
        List<OpenedFact> out = new ArrayList<>();
        for (AccountDetectFact f : rows(userId).stream().sorted(NEWEST_FIRST).toList()) {
            if (Boolean.TRUE.equals(f.getHasPdf()) && (f.getUnlockState() == UnlockState.NONE || f.getUnlockState() == UnlockState.UNLOCKED)
                    && f.getPdfAttachmentId() != null && f.getHolderCheckedGen() < gen) {
                FactsCodec.Facts facts = FactsCodec.decode(f.getFacts());
                if (facts.statements().isEmpty()) {
                    f.setHolderCheckedGen(gen);
                    continue;
                }
                out.add(new OpenedFact(f.getMessageId(), f.getInternalDate(), f.getPdfAttachmentId(), f.getUnlockState(), f.getUnlockHint(), facts));
            }
        }
        return out;
    }

    @Override
    public boolean retryPending(UUID userId) {
        AccountDetectState s = state(userId).orElse(null);
        if (s == null) return false;
        for (AccountDetectFact f : rows(userId)) {
            if (f.getPdfAttachmentId() == null) continue;
            boolean locked = f.getUnlockState() == UnlockState.LOCKED || f.getUnlockState() == UnlockState.FAILED;
            if (locked && f.getUnlockTriedGen() < s.getUnlockGen()) return true;
            boolean opened = Boolean.TRUE.equals(f.getHasPdf()) && (f.getUnlockState() == UnlockState.NONE || f.getUnlockState() == UnlockState.UNLOCKED);
            if (opened && f.getHolderCheckedGen() < s.getHolderGen()) return true;
        }
        return false;
    }

    @Override
    public void markUnlockTried(UUID userId, String messageId, int gen) {
        rows(userId).stream().filter(f -> f.getMessageId().equals(messageId)).forEach(f -> f.setUnlockTriedGen(gen));
    }

    @Override
    public void markHolderChecked(UUID userId, String messageId, int gen) {
        rows(userId).stream().filter(f -> f.getMessageId().equals(messageId)).forEach(f -> f.setHolderCheckedGen(gen));
    }

    @Override
    public void applyUnlock(UUID userId, String messageId, UnlockState state, String rule, StatementContent content) {
        for (AccountDetectFact f : rows(userId)) {
            if (!f.getMessageId().equals(messageId)) continue;
            f.setUnlockState(state);
            f.setUnlockRule(rule);
            if (content != null) {
                FactsCodec.Facts facts = FactsCodec.decode(f.getFacts());
                List<StatementContent> ss = new ArrayList<>(facts.statements());
                ss.removeIf(x -> content.originCluster() != null && content.originCluster().equals(x.originCluster()) && content.originDate().equals(x.originDate()));
                ss.add(content);
                f.setFacts(FactsCodec.encode(facts.derived(), ss, facts.bodyFetched(), facts.headerVote(), facts.incompleteRuns()));
            }
        }
    }

    @Override
    public boolean applyHolder(UUID userId, String messageId, String holder) {
        boolean changed = false;
        for (AccountDetectFact f : rows(userId)) {
            if (!f.getMessageId().equals(messageId)) continue;
            FactsCodec.Facts facts = FactsCodec.decode(f.getFacts());
            List<StatementContent> ss = new ArrayList<>();
            for (StatementContent c : facts.statements()) {
                changed |= !java.util.Objects.equals(c.holder(), holder);
                ss.add(c.withHolder(holder));
            }
            f.setFacts(FactsCodec.encode(facts.derived(), ss, facts.bodyFetched(), facts.headerVote(), facts.incompleteRuns()));
        }
        return changed;
    }
}
