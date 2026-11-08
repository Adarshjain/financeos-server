package com.financeos.gmail.accountdetect.job;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The by-ids facts load: chunks of at most 900 ids (Oracle's IN-list limit is 1000), one ordered list back. */
class AccountDetectStoreByIdsTest {

    private final UUID userId = UUID.randomUUID();

    private static AccountDetectFact row(String id, Instant date) {
        AccountDetectFact f = new AccountDetectFact();
        f.setMessageId(id);
        f.setInternalDate(date);
        f.setFacts(FactsCodec.encode(null, List.of(), false, null, 0));
        return f;
    }

    @SuppressWarnings("unchecked")
    @Test
    void idsAreLoadedInChunksOfAtMost900AndReturnedInStoreOrder() {
        AccountDetectFactRepository repo = mock(AccountDetectFactRepository.class);
        when(repo.findForUserAndMessageIds(eq(userId), any())).thenAnswer(inv -> {
            List<AccountDetectFact> rows = new ArrayList<>();
            for (String id : (Collection<String>) inv.getArgument(1)) rows.add(row(id, id.equals("id-0000") ? null : Instant.ofEpochSecond(2_000_000_000L - Long.parseLong(id.substring(3)))));
            return rows;
        });
        AccountDetectStore store = new AccountDetectStore(null, repo);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 2000; i++) ids.add(String.format("id-%04d", i));

        List<AccountDetectStore.StoredFact> out = store.facts(userId, ids);

        ArgumentCaptor<Collection<String>> cap = ArgumentCaptor.forClass(Collection.class);
        verify(repo, times(3)).findForUserAndMessageIds(eq(userId), cap.capture());
        assertThat(cap.getAllValues()).extracting(Collection::size).containsExactly(900, 900, 200);
        assertThat(out).hasSize(2000);
        assertThat(out.get(0).messageId()).isEqualTo("id-0000");   // null date first
        assertThat(out.get(1).messageId()).isEqualTo("id-1999");   // then oldest date (largest suffix) first
        assertThat(out.get(1999).messageId()).isEqualTo("id-0001");
    }

    @Test
    void noIdsMeansNoQuery() {
        AccountDetectFactRepository repo = mock(AccountDetectFactRepository.class);
        assertThat(new AccountDetectStore(null, repo).facts(userId, List.of())).isEmpty();
        verify(repo, times(0)).findForUserAndMessageIds(any(), any());
    }
}
