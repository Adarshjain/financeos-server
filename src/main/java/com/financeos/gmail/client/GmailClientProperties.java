package com.financeos.gmail.client;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Endpoints the Gmail integration talks to: the OAuth authorization/token servers used by the
 * connect flow and token refresh, and the Gmail API root. Defaults are Google's production
 * endpoints; the {@code GMAIL_AUTH_URL}, {@code GMAIL_TOKEN_URL} and {@code GMAIL_API_ROOT_URL}
 * env hooks in application.yml point them at a stub in the e2e profile.
 */
@Component
@ConfigurationProperties(prefix = "gmail")
@Getter
@Setter
public class GmailClientProperties {

    private Oauth oauth = new Oauth();
    private Api api = new Api();

    @Getter
    @Setter
    public static class Oauth {
        private String authorizationUrl = "https://accounts.google.com/o/oauth2/v2/auth";
        private String tokenUrl = "https://oauth2.googleapis.com/token";
    }

    @Getter
    @Setter
    public static class Api {
        /** Must end with a slash; the Gmail library appends the {@code gmail/v1/} service path. */
        private String rootUrl = "https://gmail.googleapis.com/";
    }
}
