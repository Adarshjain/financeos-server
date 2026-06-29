package com.financeos.gmail.ingest;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.api.gmail.dto.GmailSenderRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class SenderAllowlistService {

    private final GmailSenderRepository gmailSenderRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;

    public SenderAllowlistService(GmailSenderRepository gmailSenderRepository,
                                  AccountRepository accountRepository,
                                  UserRepository userRepository) {
        this.gmailSenderRepository = gmailSenderRepository;
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<GmailSender> getSenders(UUID userId) {
        return gmailSenderRepository.findByUserId(userId);
    }

    public GmailSender createSender(UUID userId, GmailSenderRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        GmailSender sender = new GmailSender();
        sender.setUser(user);
        sender.setName(request.name());
        sender.setSenderAddress(request.senderAddress().trim().toLowerCase());
        sender.setPurpose(request.purpose());
        sender.setAttachmentPattern(request.attachmentPattern());
        sender.setStatementFormat(request.statementFormat());
        sender.setEnabled(request.enabled() != null ? request.enabled() : true);

        if (request.accountId() != null) {
            Account account = accountRepository.findById(request.accountId())
                    .orElseThrow(() -> new IllegalArgumentException("Account not found: " + request.accountId()));
            sender.setAccount(account);
        }

        return gmailSenderRepository.save(sender);
    }

    public GmailSender updateSender(UUID userId, UUID senderId, GmailSenderRequest request) {
        GmailSender sender = gmailSenderRepository.findById(senderId)
                .orElseThrow(() -> new IllegalArgumentException("Sender not found: " + senderId));

        if (!sender.getUser().getId().equals(userId)) {
            throw new SecurityException("Unauthorized access to Gmail sender");
        }

        sender.setName(request.name());
        sender.setSenderAddress(request.senderAddress().trim().toLowerCase());
        sender.setPurpose(request.purpose());
        sender.setAttachmentPattern(request.attachmentPattern());
        sender.setStatementFormat(request.statementFormat());
        if (request.enabled() != null) {
            sender.setEnabled(request.enabled());
        }

        if (request.accountId() != null) {
            Account account = accountRepository.findById(request.accountId())
                    .orElseThrow(() -> new IllegalArgumentException("Account not found: " + request.accountId()));
            sender.setAccount(account);
        } else {
            sender.setAccount(null);
        }

        return gmailSenderRepository.save(sender);
    }

    public void deleteSender(UUID userId, UUID senderId) {
        GmailSender sender = gmailSenderRepository.findById(senderId)
                .orElseThrow(() -> new IllegalArgumentException("Sender not found: " + senderId));

        if (!sender.getUser().getId().equals(userId)) {
            throw new SecurityException("Unauthorized access to Gmail sender");
        }

        gmailSenderRepository.delete(sender);
    }
}
