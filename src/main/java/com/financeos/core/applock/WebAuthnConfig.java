package com.financeos.core.applock;

import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.AttestationConveyancePreference;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The WebAuthn relying party for the app lock: the UI host, no attestation, our own credential store. */
@Configuration
public class WebAuthnConfig {

    @Bean
    public RelyingParty relyingParty(AppLockProperties props, AppLockCredentialRepository credentials) {
        return RelyingParty.builder()
                .identity(RelyingPartyIdentity.builder().id(props.getRpId()).name(props.getRpName()).build())
                .credentialRepository(credentials)
                .origins(props.originSet())
                .attestationConveyancePreference(AttestationConveyancePreference.NONE)
                .build();
    }
}
