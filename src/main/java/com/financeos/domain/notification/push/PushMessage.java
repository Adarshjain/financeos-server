package com.financeos.domain.notification.push;

/**
 * What the service worker receives (JSON). {@code tag} makes a newer notification for the same
 * subject replace the older one instead of stacking; {@code url} is where a tap lands.
 */
public record PushMessage(String title, String body, String url, String tag) {
}
