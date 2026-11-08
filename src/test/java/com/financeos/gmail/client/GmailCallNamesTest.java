package com.financeos.gmail.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GmailCallNamesTest {

    private static final String ROOT = "https://gmail.googleapis.com";

    @Test
    void namesTheKnownCalls() {
        assertThat(GmailCallNames.fromUrl(ROOT + "/gmail/v1/users/me/messages?q=from%3Aa%40b.com&maxResults=100")).isEqualTo("messages_list");
        assertThat(GmailCallNames.fromUrl(ROOT + "/gmail/v1/users/me/messages/18c3f?format=full")).isEqualTo("messages_get");
        assertThat(GmailCallNames.fromUrl(ROOT + "/gmail/v1/users/me/messages/18c3f/attachments/ANGj")).isEqualTo("attachments_get");
        assertThat(GmailCallNames.fromUrl(ROOT + "/batch/gmail/v1")).isEqualTo("batch");
        assertThat(GmailCallNames.fromUrl(ROOT + "/gmail/v1/users/me/history?startHistoryId=9")).isEqualTo("history_list");
        assertThat(GmailCallNames.fromUrl(ROOT + "/gmail/v1/users/me/profile")).isEqualTo("get_profile");
    }

    @Test
    void anythingElseIsOther() {
        assertThat(GmailCallNames.fromUrl("https://oauth2.googleapis.com/token")).isEqualTo("other");
        assertThat(GmailCallNames.fromUrl(ROOT + "/gmail/v1/users/me/labels")).isEqualTo("other");
        assertThat(GmailCallNames.fromUrl(ROOT + "/gmail/v1/users/me/messages/18c3f/modify")).isEqualTo("other");
        assertThat(GmailCallNames.fromUrl(ROOT + "/gmail/v1/users/me/messages/18c3f/attachments")).isEqualTo("other");
        assertThat(GmailCallNames.fromUrl(ROOT + "/gmail/v1/users/me")).isEqualTo("other");
        assertThat(GmailCallNames.fromUrl("not a url ::")).isEqualTo("other");
        assertThat(GmailCallNames.fromUrl(null)).isEqualTo("other");
        assertThat(GmailCallNames.fromPath("")).isEqualTo("other");
    }

    @Test
    void worksForOtherUserIdsAndTrailingSlash_andNeverReturnsAnIdOrQuery() {
        assertThat(GmailCallNames.fromPath("/gmail/v1/users/someone%40x.com/messages/")).isEqualTo("messages_list");
        assertThat(GmailCallNames.ALL).contains(GmailCallNames.fromUrl(ROOT + "/gmail/v1/users/me/messages/secretid?q=secret"));
    }
}
