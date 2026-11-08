package com.financeos.gmail.accountdetect.scan;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GmailMailSourceListTest {

    private static List<String> tokens(int n) {
        List<String> t = new ArrayList<>();
        for (int i = 0; i < n; i++) t.add("bank" + i + ".example.com");
        return t;
    }

    @Test
    void batchesByTokenCount() {
        List<String> q = GmailMailSource.listQueries(tokens(60), "");
        assertThat(q).hasSize(3);
        assertThat(q.get(0)).startsWith("from:(bank0.example.com OR bank1.example.com").endsWith("bank24.example.com)");
        assertThat(q.get(0).split(" OR ")).hasSize(25);
        assertThat(q.get(2).split(" OR ")).hasSize(10);
    }

    @Test
    void batchesByLength() {
        String longish = "a".repeat(100) + ".example.com";
        List<String> t = new ArrayList<>();
        for (int i = 0; i < 20; i++) t.add(i + longish);
        List<String> q = GmailMailSource.listQueries(t, " after:1");
        assertThat(q.size()).isGreaterThan(1);
        for (String s : q) assertThat(s.length()).isLessThanOrEqualTo(GmailMailSource.MAX_QUERY_LENGTH);
        assertThat(q.stream().mapToInt(s -> s.split(" OR ").length).sum()).isEqualTo(20);
    }

    @Test
    void oneTokenAndEmptyInput() {
        assertThat(GmailMailSource.listQueries(List.of("hdfcbank.net"), "")).containsExactly("from:(hdfcbank.net)");
        assertThat(GmailMailSource.listQueries(List.of(), " after:5")).isEmpty();
    }

    @Test
    void blankAndNullTokensAreSkipped() {
        assertThat(GmailMailSource.listQueries(Arrays.asList(null, "", "  ", "a.com", null, "b.com"), ""))
                .containsExactly("from:(a.com OR b.com)");
        assertThat(GmailMailSource.listQueries(Arrays.asList(null, " "), "")).isEmpty();
    }

    @Test
    void theSuffixEndsEveryQuery() {
        List<String> q = GmailMailSource.listQueries(tokens(30), " after:1700000000");
        assertThat(q).hasSize(2).allSatisfy(s -> assertThat(s).endsWith(") after:1700000000"));
    }

    @Test
    void aTokenLongerThanTheLimitGoesAlone() {
        String huge = "h".repeat(1500) + ".com";
        List<String> q = GmailMailSource.listQueries(List.of("a.com", huge, "b.com"), "");
        assertThat(q).containsExactly("from:(a.com)", "from:(" + huge + ")", "from:(b.com)");
    }

    @Test
    void listingManyTokensMakesFarFewerGmailListCalls() throws Exception {
        Gmail svc = mock(Gmail.class);
        Gmail.Users users = mock(Gmail.Users.class);
        Gmail.Users.Messages messages = mock(Gmail.Users.Messages.class);
        Gmail.Users.Messages.List list = mock(Gmail.Users.Messages.List.class, Answers.RETURNS_SELF);
        when(svc.users()).thenReturn(users);
        when(users.messages()).thenReturn(messages);
        when(messages.list("me")).thenReturn(list);
        when(list.execute()).thenReturn(new ListMessagesResponse().setMessages(List.of(new Message().setId("m1"))));

        MailSource.Listing l = new GmailMailSource(null, svc, 10, 10).list(tokens(99), Instant.ofEpochSecond(5));

        assertThat(l.ids()).containsExactly("m1");
        assertThat(l.calls()).isEqualTo(GmailMailSource.listQueries(tokens(99), " after:5").size()).isLessThan(99);
    }
}
