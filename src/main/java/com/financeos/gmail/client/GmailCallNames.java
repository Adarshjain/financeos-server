package com.financeos.gmail.client;

import java.net.URI;

/** Maps a Gmail API request URL to a fixed call name for timing; the query string and ids never leave here. */
public final class GmailCallNames {

    public static final String MESSAGES_LIST = "messages_list";
    public static final String MESSAGES_GET = "messages_get";
    public static final String ATTACHMENTS_GET = "attachments_get";
    public static final String BATCH = "batch";
    public static final String HISTORY_LIST = "history_list";
    public static final String GET_PROFILE = "get_profile";
    public static final String OTHER = "other";

    /** Every name {@link #fromUrl} can return (the series-budget test counts these). */
    public static final java.util.List<String> ALL = java.util.List.of(
            MESSAGES_LIST, MESSAGES_GET, ATTACHMENTS_GET, BATCH, HISTORY_LIST, GET_PROFILE, OTHER);

    private GmailCallNames() {
    }

    public static String fromUrl(String url) {
        if (url == null) {
            return OTHER;
        }
        String path;
        try {
            path = URI.create(url).getPath();
        } catch (IllegalArgumentException e) {
            return OTHER;
        }
        return fromPath(path);
    }

    /** {@code path} is the URL path, e.g. {@code /gmail/v1/users/me/messages/abc}. */
    public static String fromPath(String path) {
        if (path == null || path.isBlank()) {
            return OTHER;
        }
        String[] seg = path.replaceAll("^/+|/+$", "").split("/");
        if (seg.length > 0 && "batch".equals(seg[0])) {
            return BATCH;
        }
        int users = -1;
        for (int i = 0; i < seg.length; i++) {
            if ("users".equals(seg[i])) {
                users = i;
                break;
            }
        }
        if (users < 0 || seg.length <= users + 2) {
            return OTHER;
        }
        int n = seg.length - (users + 2);
        String resource = seg[users + 2];
        switch (resource) {
            case "messages":
                if (n == 1) {
                    return MESSAGES_LIST;
                }
                if (n == 2) {
                    return MESSAGES_GET;
                }
                if (n == 4 && "attachments".equals(seg[users + 4])) {
                    return ATTACHMENTS_GET;
                }
                return OTHER;
            case "history":
                return n == 1 ? HISTORY_LIST : OTHER;
            case "profile":
                return n == 1 ? GET_PROFILE : OTHER;
            default:
                return OTHER;
        }
    }
}
