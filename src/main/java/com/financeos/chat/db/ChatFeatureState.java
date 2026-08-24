package com.financeos.chat.db;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ChatFeatureState {

    private final ChatProperties properties;

    public ChatFeatureState(ChatProperties properties) {
        this.properties = properties;
    }

    public boolean isEnabled() {
        if (!properties.isEnabled()) {
            return false;
        }
        String username = properties.getDatasource().getUsername();
        String password = properties.getDatasource().getPassword();
        return StringUtils.hasText(username) && StringUtils.hasText(password);
    }
}
