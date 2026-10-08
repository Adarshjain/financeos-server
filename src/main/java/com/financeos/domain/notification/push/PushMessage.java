package com.financeos.domain.notification.push;

/**
 * What the service worker receives (JSON). {@code tag} makes a newer notification for the same
 * subject replace the older one instead of stacking; {@code url} is where a tap lands;
 * {@code quietWhenVisible} tells the worker to stay silent while a FinanceOS tab is in the
 * foreground (the page already shows the outcome, e.g. a job's toast).
 */
public record PushMessage(String title, String body, String url, String tag, boolean quietWhenVisible) {

    public PushMessage(String title, String body, String url, String tag) {
        this(title, body, url, tag, false);
    }
}
