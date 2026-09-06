package com.financeos.core.oauth;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Google OAuth endpoints used by SSO (authorization, token exchange, userinfo) and by account
 * deletion (revoke). Defaults are Google's production endpoints; each can be pointed at a stub
 * (e.g. WireMock in the e2e profile) through the {@code GOOGLE_*_URL} env hooks in application.yml.
 */
@Component
@ConfigurationProperties(prefix = "google.oauth")
@Getter
@Setter
public class GoogleOAuthProperties {

    private String authorizationUrl = "https://accounts.google.com/o/oauth2/v2/auth";
    private String tokenUrl = "https://oauth2.googleapis.com/token";
    private String userInfoUrl = "https://www.googleapis.com/oauth2/v2/userinfo";
    private String revokeUrl = "https://oauth2.googleapis.com/revoke";
}
