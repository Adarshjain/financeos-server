package com.financeos.domain.nps.gmail;

import com.financeos.core.time.AppTime;
import com.financeos.domain.nps.NpsAccount;
import com.financeos.domain.nps.NpsAccountRepository;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailProcessedStatus;
import com.financeos.gmail.engine.GmailEngine;
import com.financeos.gmail.ingest.GmailDocumentHandler;
import com.financeos.gmail.internal.GmailAttachment;
import com.financeos.gmail.internal.GmailMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Reads the Protean / NSDL NPS mails of a user's mailbox: the monthly statement PDF (imported straight away when its
 * PRAN is an account reading Gmail, otherwise held, see {@link NpsGmailStatementService}), the
 * contribution request confirmation (a PENDING entry) and the credit intimation (an ESTIMATED entry). Nothing here is
 * a bank transaction. The work runs in its own transaction so a failure never poisons the ingestion's. Never logs the
 * PRAN or the holder's name.
 */
@Component
public class NpsGmailHandler implements GmailDocumentHandler {

    private static final Logger log = LoggerFactory.getLogger(NpsGmailHandler.class);

    static final List<String> STATEMENT_SENDERS = List.of("nps-statements@mailer.proteantech.in",
            "nps-statements@proteantech.in", "cra@nsdl.co.in");
    static final List<String> CREDIT_SENDERS = List.of("nps-alerts@proteantech.in", "cra-alerts@nsdl.co.in");
    static final String REQUEST_SENDER = "enps-alerts@proteantech.in";
    private static final LocalDate BACKFILL_FROM = LocalDate.of(2013, 1, 1);

    private final NpsAccountRepository accountRepository;
    private final NpsGmailStatementService statementService;
    private final NpsAlertService alertService;
    private final GmailEngine gmailEngine;
    private final TransactionTemplate transactions;

    public NpsGmailHandler(NpsAccountRepository accountRepository, NpsGmailStatementService statementService,
                           NpsAlertService alertService, GmailEngine gmailEngine,
                           PlatformTransactionManager transactionManager) {
        this.accountRepository = accountRepository;
        this.statementService = statementService;
        this.alertService = alertService;
        this.gmailEngine = gmailEngine;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public boolean supports(String fromAddress, GmailMessage message) {
        String from = fromAddress == null ? "" : fromAddress.toLowerCase(Locale.ROOT);
        return STATEMENT_SENDERS.contains(from) || CREDIT_SENDERS.contains(from) || REQUEST_SENDER.equals(from);
    }

    @Override
    public List<String> senderAddresses() {
        return java.util.stream.Stream.of(STATEMENT_SENDERS, CREDIT_SENDERS, List.of(REQUEST_SENDER))
                .flatMap(List::stream).toList();
    }

    @Override
    public String senderName() {
        return "NPS (Protean)";
    }

    @Override
    public LocalDate backfillFrom() {
        return BACKFILL_FROM;
    }

    @Override
    public Handled handle(GmailConnection connection, GmailMessage message) {
        UUID userId = connection.getUser().getId();
        String from = fromOf(message);
        List<NpsAccount> accounts = accountRepository.findAllByUser_Id(userId);
        // turned off on every NPS account: the senders stay, nothing is written
        if (!accounts.isEmpty() && accounts.stream().noneMatch(NpsAccount::isReadGmail)) {
            return ignored("Reading NPS emails is off");
        }
        if (STATEMENT_SENDERS.contains(from)) {
            return statement(userId, connection, message);
        }
        return alert(userId, from, message);
    }

    // ------------------------------------------------------------------ statement

    private Handled statement(UUID userId, GmailConnection connection, GmailMessage message) {
        GmailAttachment pdf = pdfOf(message);
        if (pdf == null) {
            return ignored("No statement attached");
        }
        byte[] bytes = gmailEngine.fetchAttachmentContent(connection, message.messageId(), pdf.attachmentId());
        NpsGmailStatementService.Outcome outcome = transactions.execute(status ->
                statementService.process(userId, connection.getId(), message.messageId(), bytes, null));
        return switch (outcome.kind()) {
            case IMPORTED -> Handled.of(GmailProcessedStatus.NPS_IMPORTED);
            case PASSWORD_NEEDED -> Handled.of(GmailProcessedStatus.NPS_PASSWORD_NEEDED);
            case ACCOUNT_NEEDED -> Handled.of(GmailProcessedStatus.NPS_ACCOUNT_NEEDED);
            case IGNORED -> ignored(outcome.why());
        };
    }

    /** The first PDF attached to the mail, or null. */
    public static GmailAttachment pdfOf(GmailMessage message) {
        if (message.attachments() == null) {
            return null;
        }
        return message.attachments().stream()
                .filter(a -> a.filename() != null && a.filename().toLowerCase(Locale.ROOT).endsWith(".pdf")
                        && a.attachmentId() != null)
                .findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ alerts

    private Handled alert(UUID userId, String from, GmailMessage message) {
        String text = NpsAlertParser.text(message.bodyText(), message.bodyHtml());
        if (text.isEmpty()) {
            text = NpsAlertParser.text(message.snippet(), null);
        }
        LocalDate mailDate = message.internalDate() == null ? AppTime.today()
                : LocalDate.ofInstant(message.internalDate(), AppTime.zone());
        NpsAlertService.Result result;
        if (REQUEST_SENDER.equals(from)) {
            Optional<NpsAlertParser.Request> request = NpsAlertParser.request(text);
            if (request.isEmpty()) {
                return ignored("Not a contribution request confirmation");
            }
            result = transactions.execute(status -> alertService.applyRequest(userId, request.get(), mailDate));
        } else {
            Optional<NpsAlertParser.Credit> credit = NpsAlertParser.credit(text);
            if (credit.isEmpty()) {
                return ignored("Not a contribution credit intimation");
            }
            result = transactions.execute(status -> alertService.applyCredit(userId, credit.get()));
        }
        if (result == NpsAlertService.Result.IGNORED) {
            log.info("NPS alert ignored: no single account reading Gmail matches it");
            return ignored("No single NPS account matches this alert");
        }
        return Handled.of(GmailProcessedStatus.NPS_ENTRY);
    }

    private static Handled ignored(String why) {
        return new Handled(GmailProcessedStatus.NPS_IGNORED, why);
    }

    private static String fromOf(GmailMessage message) {
        String from = message.from() == null ? "" : message.from();
        int start = from.indexOf('<');
        int end = from.indexOf('>');
        String address = start != -1 && end > start ? from.substring(start + 1, end) : from;
        return address.trim().toLowerCase(Locale.ROOT);
    }
}
