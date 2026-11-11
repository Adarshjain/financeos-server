package com.financeos.sms.device;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** {@link GoogleIdTokenCheck} on Google's own verifier (signature against Google's cached keys, issuer, expiry, audience). */
@Component
public class GoogleIdTokenCheckImpl implements GoogleIdTokenCheck {

    private static final GsonFactory JSON = GsonFactory.getDefaultInstance();
    private static final NetHttpTransport TRANSPORT = new NetHttpTransport();

    private final ConcurrentMap<String, GoogleIdTokenVerifier> verifiers = new ConcurrentHashMap<>();

    @Override
    public Result check(String idToken, String audience) {
        if (audience == null || audience.isBlank()) {
            throw new UnavailableException("Google client id not configured", null);
        }
        GoogleIdToken parsed;
        try {
            parsed = GoogleIdToken.parse(JSON, idToken);
        } catch (IOException | RuntimeException e) {
            return Result.unreadable();
        }
        if (parsed == null || parsed.getPayload() == null) {
            return Result.unreadable();
        }
        GoogleIdToken.Payload p = parsed.getPayload();
        boolean valid;
        try {
            valid = verifiers.computeIfAbsent(audience, a -> new GoogleIdTokenVerifier.Builder(TRANSPORT, JSON)
                    .setAudience(List.of(a)).build()).verify(parsed);
        } catch (IOException e) {
            throw new UnavailableException("Google keys unavailable", e);
        } catch (GeneralSecurityException | RuntimeException e) {
            valid = false;
        }
        return new Result(valid, p.getSubject(), p.getEmail(), Boolean.TRUE.equals(p.getEmailVerified()));
    }
}
