package com.financeos.domain.nps.gmail;

import com.financeos.core.exception.ValidationException;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.financeos.gmail.engine.GmailEngine;
import com.financeos.gmail.internal.GmailAttachment;
import com.financeos.gmail.internal.GmailMessage;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Fetches the statement PDF of a held mail from Gmail again (it is never stored). Connections are the user's own. */
@Component
public class NpsGmailPdfFetcher {

    private final GmailConnectionRepository connectionRepository;
    private final GmailEngine gmailEngine;

    public NpsGmailPdfFetcher(GmailConnectionRepository connectionRepository, GmailEngine gmailEngine) {
        this.connectionRepository = connectionRepository;
        this.gmailEngine = gmailEngine;
    }

    /** The user's Gmail connections of these rows, in one query; another user's connection id is not returned. */
    public Map<UUID, GmailConnection> connectionsOf(UUID userId, Collection<NpsGmailPending> rows) {
        Set<UUID> ids = rows.stream().map(NpsGmailPending::getConnectionId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, GmailConnection> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        for (GmailConnection c : connectionRepository.findAllById(List.copyOf(ids))) {
            if (c.getUser().getId().equals(userId)) {
                out.put(c.getId(), c);
            }
        }
        return out;
    }

    /**
     * The mail's PDF. A mail that is gone from the mailbox throws {@link com.financeos.gmail.internal.MessageGoneException};
     * a mail that has no PDF any more throws a {@link ValidationException}.
     */
    public byte[] fetch(GmailConnection connection, String messageId) {
        GmailMessage message = gmailEngine.fetchMessageDetails(connection, messageId);
        GmailAttachment pdf = NpsGmailHandler.pdfOf(message);
        if (pdf == null) {
            throw new ValidationException("The email has no statement attached any more.");
        }
        return gmailEngine.fetchAttachmentContent(connection, message.messageId(), pdf.attachmentId());
    }
}
