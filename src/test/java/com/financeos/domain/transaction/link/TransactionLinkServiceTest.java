package com.financeos.domain.transaction.link;

import com.financeos.api.transactionlink.dto.CreateTransactionLinkRequest;
import com.financeos.api.transactionlink.dto.MemberRef;
import com.financeos.api.transactionlink.dto.MemberSummary;
import com.financeos.api.transactionlink.dto.TransactionLinkResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.category.Category;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionLinkServiceTest {

    @Mock
    private TransactionLinkRepository transactionLinkRepository;
    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private UserRepository userRepository;

    private TransactionLinkService transactionLinkService;

    private UUID userId;
    private User testUser;
    private Account account1;
    private Account account2;
    private Account ccAccount;

    @BeforeEach
    void setUp() {
        transactionLinkService = new TransactionLinkService(
                transactionLinkRepository,
                transactionRepository,
                userRepository
        );

        userId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);

        testUser = new User();
        testUser.setId(userId);

        lenient().when(userRepository.getReferenceById(userId)).thenReturn(testUser);

        account1 = new Account("Account 1", AccountType.bank_account);
        account1.setId(UUID.randomUUID());
        account1.setUser(testUser);

        account2 = new Account("Account 2", AccountType.bank_account);
        account2.setId(UUID.randomUUID());
        account2.setUser(testUser);

        ccAccount = new Account("Credit Card", AccountType.credit_card);
        ccAccount.setId(UUID.randomUUID());
        ccAccount.setUser(testUser);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private Transaction createTestTx(UUID id, Account account, TransactionType type, BigDecimal amount) {
        Transaction t = new Transaction(account, LocalDate.now(), amount, "Test", TransactionSource.manual, type, false, false);
        t.setId(id);
        t.setUser(testUser);
        return t;
    }

    @Test
    void createLink_transfer_valid() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        Transaction t1 = createTestTx(id1, account1, TransactionType.DEBIT, BigDecimal.valueOf(100));
        Transaction t2 = createTestTx(id2, account2, TransactionType.CREDIT, BigDecimal.valueOf(100));

        when(transactionRepository.findAllByIdIn(List.of(id1, id2))).thenReturn(List.of(t1, t2));
        when(transactionLinkRepository.findByMembers_Transaction_Id(any())).thenReturn(Optional.empty());
        when(transactionLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateTransactionLinkRequest request = new CreateTransactionLinkRequest(
                LinkType.TRANSFER,
                "Transfer note",
                false,
                List.of(new MemberRef(id1, true), new MemberRef(id2, false))
        );

        TransactionLinkResponse response = transactionLinkService.createLink(request, LinkOrigin.USER);

        assertThat(response.type()).isEqualTo(LinkType.TRANSFER);
        assertThat(response.note()).isEqualTo("Transfer note");
        assertThat(response.members()).hasSize(2);
        assertThat(response.members().stream().filter(MemberSummary::isAnchor).findFirst().get().roleLabel()).isEqualTo("Transfer out");
    }

    @Test
    void createLink_alreadyLinked_throwsValidationException() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        Transaction t1 = createTestTx(id1, account1, TransactionType.DEBIT, BigDecimal.valueOf(100));
        Transaction t2 = createTestTx(id2, account2, TransactionType.CREDIT, BigDecimal.valueOf(100));

        when(transactionRepository.findAllByIdIn(List.of(id1, id2))).thenReturn(List.of(t1, t2));

        TransactionLink existingLink = new TransactionLink();
        existingLink.setType(LinkType.TRANSFER);
        when(transactionLinkRepository.findByMembers_Transaction_Id(id1)).thenReturn(Optional.of(existingLink));

        CreateTransactionLinkRequest request = new CreateTransactionLinkRequest(
                LinkType.TRANSFER,
                null,
                false,
                List.of(new MemberRef(id1, true), new MemberRef(id2, false))
        );

        assertThatThrownBy(() -> transactionLinkService.createLink(request, LinkOrigin.USER))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("already in a TRANSFER link");
    }

    @Test
    void createLink_transfer_sameAccount_throwsValidationException() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        Transaction t1 = createTestTx(id1, account1, TransactionType.DEBIT, BigDecimal.valueOf(100));
        Transaction t2 = createTestTx(id2, account1, TransactionType.CREDIT, BigDecimal.valueOf(100));

        when(transactionRepository.findAllByIdIn(List.of(id1, id2))).thenReturn(List.of(t1, t2));
        when(transactionLinkRepository.findByMembers_Transaction_Id(any())).thenReturn(Optional.empty());

        CreateTransactionLinkRequest request = new CreateTransactionLinkRequest(
                LinkType.TRANSFER,
                null,
                false,
                List.of(new MemberRef(id1, true), new MemberRef(id2, false))
        );

        assertThatThrownBy(() -> transactionLinkService.createLink(request, LinkOrigin.USER))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("different accounts");
    }

    @Test
    void createLink_ccPayment_notCreditCardAccount_throwsValidationException() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        Transaction t1 = createTestTx(id1, account1, TransactionType.DEBIT, BigDecimal.valueOf(500));
        Transaction t2 = createTestTx(id2, account2, TransactionType.CREDIT, BigDecimal.valueOf(500));

        when(transactionRepository.findAllByIdIn(List.of(id1, id2))).thenReturn(List.of(t1, t2));

        CreateTransactionLinkRequest request = new CreateTransactionLinkRequest(
                LinkType.CC_PAYMENT,
                null,
                false,
                List.of(new MemberRef(id1, true), new MemberRef(id2, false))
        );

        assertThatThrownBy(() -> transactionLinkService.createLink(request, LinkOrigin.USER))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("credit card account");
    }

    @Test
    void createLink_refund_withCategoryAlignment() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        Transaction t1 = createTestTx(id1, account1, TransactionType.DEBIT, BigDecimal.valueOf(1000));
        Transaction t2 = createTestTx(id2, account1, TransactionType.CREDIT, BigDecimal.valueOf(200));

        Category cat = new Category("Shopping", null);
        cat.setId(UUID.randomUUID());
        t1.setCategories(Set.of(cat));

        when(transactionRepository.findAllByIdIn(List.of(id1, id2))).thenReturn(List.of(t1, t2));
        when(transactionLinkRepository.findByMembers_Transaction_Id(any())).thenReturn(Optional.empty());
        when(transactionLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateTransactionLinkRequest request = new CreateTransactionLinkRequest(
                LinkType.REFUND,
                "Partial refund",
                true,
                List.of(new MemberRef(id1, true), new MemberRef(id2, false))
        );

        TransactionLinkResponse response = transactionLinkService.createLink(request, LinkOrigin.USER);

        assertThat(response.type()).isEqualTo(LinkType.REFUND);
        verify(transactionRepository).save(t2);
        assertThat(t2.getCategories()).hasSize(1);
    }

    @Test
    void createLink_noAnchors_throwsValidationException() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        Transaction t1 = createTestTx(id1, account1, TransactionType.DEBIT, BigDecimal.valueOf(100));
        Transaction t2 = createTestTx(id2, account2, TransactionType.CREDIT, BigDecimal.valueOf(100));

        when(transactionRepository.findAllByIdIn(List.of(id1, id2))).thenReturn(List.of(t1, t2));
        when(transactionLinkRepository.findByMembers_Transaction_Id(any())).thenReturn(Optional.empty());

        CreateTransactionLinkRequest request = new CreateTransactionLinkRequest(
                LinkType.TRANSFER,
                null,
                false,
                List.of(new MemberRef(id1, false), new MemberRef(id2, false))
        );

        assertThatThrownBy(() -> transactionLinkService.createLink(request, LinkOrigin.USER))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("exactly one anchor");
    }

    @Test
    void createLink_twoAnchors_throwsValidationException() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        Transaction t1 = createTestTx(id1, account1, TransactionType.DEBIT, BigDecimal.valueOf(100));
        Transaction t2 = createTestTx(id2, account2, TransactionType.CREDIT, BigDecimal.valueOf(100));

        when(transactionRepository.findAllByIdIn(List.of(id1, id2))).thenReturn(List.of(t1, t2));
        when(transactionLinkRepository.findByMembers_Transaction_Id(any())).thenReturn(Optional.empty());

        CreateTransactionLinkRequest request = new CreateTransactionLinkRequest(
                LinkType.TRANSFER,
                null,
                false,
                List.of(new MemberRef(id1, true), new MemberRef(id2, true))
        );

        assertThatThrownBy(() -> transactionLinkService.createLink(request, LinkOrigin.USER))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("exactly one anchor");
    }

    @Test
    void createLink_reversal_valid() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        Transaction t1 = createTestTx(id1, account1, TransactionType.DEBIT, BigDecimal.valueOf(500));
        Transaction t2 = createTestTx(id2, account1, TransactionType.CREDIT, BigDecimal.valueOf(500));

        when(transactionRepository.findAllByIdIn(List.of(id1, id2))).thenReturn(List.of(t1, t2));
        when(transactionLinkRepository.findByMembers_Transaction_Id(any())).thenReturn(Optional.empty());
        when(transactionLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateTransactionLinkRequest request = new CreateTransactionLinkRequest(
                LinkType.REVERSAL,
                "Transaction reversal",
                false,
                List.of(new MemberRef(id1, true), new MemberRef(id2, false))
        );

        TransactionLinkResponse response = transactionLinkService.createLink(request, LinkOrigin.USER);

        assertThat(response.type()).isEqualTo(LinkType.REVERSAL);
        assertThat(response.members()).hasSize(2);
    }

    @Test
    void createLink_reversal_sameDirectionAsAnchor_throwsValidationException() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        Transaction t1 = createTestTx(id1, account1, TransactionType.DEBIT, BigDecimal.valueOf(500));
        Transaction t2 = createTestTx(id2, account1, TransactionType.DEBIT, BigDecimal.valueOf(500));

        when(transactionRepository.findAllByIdIn(List.of(id1, id2))).thenReturn(List.of(t1, t2));
        when(transactionLinkRepository.findByMembers_Transaction_Id(any())).thenReturn(Optional.empty());

        CreateTransactionLinkRequest request = new CreateTransactionLinkRequest(
                LinkType.REVERSAL,
                null,
                false,
                List.of(new MemberRef(id1, true), new MemberRef(id2, false))
        );

        assertThatThrownBy(() -> transactionLinkService.createLink(request, LinkOrigin.USER))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("opposite direction");
    }

    @Test
    void createLink_reversal_differentAccount_throwsValidationException() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        Transaction t1 = createTestTx(id1, account1, TransactionType.DEBIT, BigDecimal.valueOf(500));
        Transaction t2 = createTestTx(id2, account2, TransactionType.CREDIT, BigDecimal.valueOf(500));

        when(transactionRepository.findAllByIdIn(List.of(id1, id2))).thenReturn(List.of(t1, t2));
        when(transactionLinkRepository.findByMembers_Transaction_Id(any())).thenReturn(Optional.empty());

        CreateTransactionLinkRequest request = new CreateTransactionLinkRequest(
                LinkType.REVERSAL,
                null,
                false,
                List.of(new MemberRef(id1, true), new MemberRef(id2, false))
        );

        assertThatThrownBy(() -> transactionLinkService.createLink(request, LinkOrigin.USER))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("same account");
    }

    @Test
    void createLink_fee_validAnchorAndCounterpart() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        Transaction t1 = createTestTx(id1, account1, TransactionType.DEBIT, BigDecimal.valueOf(100));
        Transaction t2 = createTestTx(id2, account1, TransactionType.DEBIT, BigDecimal.valueOf(50));

        when(transactionRepository.findAllByIdIn(List.of(id1, id2))).thenReturn(List.of(t1, t2));
        when(transactionLinkRepository.findByMembers_Transaction_Id(any())).thenReturn(Optional.empty());
        when(transactionLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateTransactionLinkRequest request = new CreateTransactionLinkRequest(
                LinkType.FEE,
                "Transaction fee",
                false,
                List.of(new MemberRef(id1, true), new MemberRef(id2, false))
        );

        TransactionLinkResponse response = transactionLinkService.createLink(request, LinkOrigin.USER);

        assertThat(response.type()).isEqualTo(LinkType.FEE);
        assertThat(response.members()).hasSize(2);
    }

    @Test
    void createLink_fee_creditCounterpart_throwsValidationException() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        Transaction t1 = createTestTx(id1, account1, TransactionType.DEBIT, BigDecimal.valueOf(100));
        Transaction t2 = createTestTx(id2, account1, TransactionType.CREDIT, BigDecimal.valueOf(50));

        when(transactionRepository.findAllByIdIn(List.of(id1, id2))).thenReturn(List.of(t1, t2));
        when(transactionLinkRepository.findByMembers_Transaction_Id(any())).thenReturn(Optional.empty());

        CreateTransactionLinkRequest request = new CreateTransactionLinkRequest(
                LinkType.FEE,
                null,
                false,
                List.of(new MemberRef(id1, true), new MemberRef(id2, false))
        );

        assertThatThrownBy(() -> transactionLinkService.createLink(request, LinkOrigin.USER))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("FEE counterparts must be DEBIT");
    }

    @Test
    void createLink_emi_validAnchorAndCounterparts() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();

        Transaction t1 = createTestTx(id1, account1, TransactionType.DEBIT, BigDecimal.valueOf(1200));
        Transaction t2 = createTestTx(id2, account1, TransactionType.DEBIT, BigDecimal.valueOf(400));
        Transaction t3 = createTestTx(id3, account1, TransactionType.DEBIT, BigDecimal.valueOf(400));

        when(transactionRepository.findAllByIdIn(List.of(id1, id2, id3))).thenReturn(List.of(t1, t2, t3));
        when(transactionLinkRepository.findByMembers_Transaction_Id(any())).thenReturn(Optional.empty());
        when(transactionLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateTransactionLinkRequest request = new CreateTransactionLinkRequest(
                LinkType.EMI,
                "EMI for purchase",
                false,
                List.of(new MemberRef(id1, true), new MemberRef(id2, false), new MemberRef(id3, false))
        );

        TransactionLinkResponse response = transactionLinkService.createLink(request, LinkOrigin.USER);

        assertThat(response.type()).isEqualTo(LinkType.EMI);
        assertThat(response.members()).hasSize(3);
    }

    @Test
    void createLink_emi_creditCounterpart_throwsValidationException() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        Transaction t1 = createTestTx(id1, account1, TransactionType.DEBIT, BigDecimal.valueOf(1000));
        Transaction t2 = createTestTx(id2, account1, TransactionType.CREDIT, BigDecimal.valueOf(500));

        when(transactionRepository.findAllByIdIn(List.of(id1, id2))).thenReturn(List.of(t1, t2));
        when(transactionLinkRepository.findByMembers_Transaction_Id(any())).thenReturn(Optional.empty());

        CreateTransactionLinkRequest request = new CreateTransactionLinkRequest(
                LinkType.EMI,
                null,
                false,
                List.of(new MemberRef(id1, true), new MemberRef(id2, false))
        );

        assertThatThrownBy(() -> transactionLinkService.createLink(request, LinkOrigin.USER))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("EMI counterparts must be DEBIT");
    }

    @Test
    void createLink_refund_multipleCounterparts() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();

        Transaction t1 = createTestTx(id1, account1, TransactionType.DEBIT, BigDecimal.valueOf(1000));
        Transaction t2 = createTestTx(id2, account1, TransactionType.CREDIT, BigDecimal.valueOf(500));
        Transaction t3 = createTestTx(id3, account1, TransactionType.CREDIT, BigDecimal.valueOf(500));

        when(transactionRepository.findAllByIdIn(List.of(id1, id2, id3))).thenReturn(List.of(t1, t2, t3));
        when(transactionLinkRepository.findByMembers_Transaction_Id(any())).thenReturn(Optional.empty());
        when(transactionLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateTransactionLinkRequest request = new CreateTransactionLinkRequest(
                LinkType.REFUND,
                "Partial refunds",
                false,
                List.of(new MemberRef(id1, true), new MemberRef(id2, false), new MemberRef(id3, false))
        );

        TransactionLinkResponse response = transactionLinkService.createLink(request, LinkOrigin.USER);

        assertThat(response.type()).isEqualTo(LinkType.REFUND);
        assertThat(response.members()).hasSize(3);
    }

    @Test
    void createLink_transfer_noDifferenceInAmountAndDate() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        Transaction t1 = new Transaction(account1, LocalDate.now(), BigDecimal.valueOf(100), "Transfer out",
                TransactionSource.manual, TransactionType.DEBIT, false, false);
        t1.setId(id1);
        t1.setUser(testUser);

        Transaction t2 = new Transaction(account2, LocalDate.now().plusMonths(3), BigDecimal.valueOf(250), "Transfer in",
                TransactionSource.manual, TransactionType.CREDIT, false, false);
        t2.setId(id2);
        t2.setUser(testUser);

        when(transactionRepository.findAllByIdIn(List.of(id1, id2))).thenReturn(List.of(t1, t2));
        when(transactionLinkRepository.findByMembers_Transaction_Id(any())).thenReturn(Optional.empty());
        when(transactionLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateTransactionLinkRequest request = new CreateTransactionLinkRequest(
                LinkType.TRANSFER,
                "Transfer with different amounts and dates",
                false,
                List.of(new MemberRef(id1, true), new MemberRef(id2, false))
        );

        // Should succeed - amount and date differences are intentionally not validated
        TransactionLinkResponse response = transactionLinkService.createLink(request, LinkOrigin.USER);

        assertThat(response.type()).isEqualTo(LinkType.TRANSFER);
        assertThat(response.members()).hasSize(2);
    }

    @Test
    void createLink_refund_alignCategoriesDoeNotAffectIsExcluded() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        Transaction t1 = createTestTx(id1, account1, TransactionType.DEBIT, BigDecimal.valueOf(1000));
        Transaction t2 = createTestTx(id2, account1, TransactionType.CREDIT, BigDecimal.valueOf(1000));
        t2.setTransactionExcluded(false);

        Category cat = new Category("Purchase", null);
        cat.setId(UUID.randomUUID());
        t1.setCategories(Set.of(cat));

        when(transactionRepository.findAllByIdIn(List.of(id1, id2))).thenReturn(List.of(t1, t2));
        when(transactionLinkRepository.findByMembers_Transaction_Id(any())).thenReturn(Optional.empty());
        when(transactionLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateTransactionLinkRequest request = new CreateTransactionLinkRequest(
                LinkType.REFUND,
                "Refund with category alignment",
                true,
                List.of(new MemberRef(id1, true), new MemberRef(id2, false))
        );

        transactionLinkService.createLink(request, LinkOrigin.USER);

        // Verify categories were aligned
        assertThat(t2.getCategories()).hasSize(1);
        // Verify is_excluded was NOT changed
        assertThat(t2.isTransactionExcluded()).isFalse();
        verify(transactionRepository).save(t2);
    }

    @Test
    void autoDissolveLinksForDeletedTransactions_twoMemberLink_deleteOneMember() {
        UUID linkId = UUID.randomUUID();
        UUID txn1Id = UUID.randomUUID();
        UUID txn2Id = UUID.randomUUID();

        TransactionLink link = new TransactionLink();
        link.setId(linkId);

        Transaction txn1 = createTestTx(txn1Id, account1, TransactionType.DEBIT, BigDecimal.valueOf(100));
        Transaction txn2 = createTestTx(txn2Id, account2, TransactionType.CREDIT, BigDecimal.valueOf(100));

        TransactionLinkMember member1 = new TransactionLinkMember(link, txn1, true);
        TransactionLinkMember member2 = new TransactionLinkMember(link, txn2, false);
        link.getMembers().add(member1);
        link.getMembers().add(member2);

        when(transactionLinkRepository.findDistinctByMembers_Transaction_IdIn(List.of(txn1Id)))
                .thenReturn(List.of(link));

        transactionLinkService.autoDissolveLinksForDeletedTransactions(List.of(txn1Id));

        verify(transactionLinkRepository).delete(link);
    }

    @Test
    void autoDissolveLinksForDeletedTransactions_threeMemberLink_deleteOneCounterpart() {
        UUID linkId = UUID.randomUUID();
        UUID anchorId = UUID.randomUUID();
        UUID counterpart1Id = UUID.randomUUID();
        UUID counterpart2Id = UUID.randomUUID();

        TransactionLink link = new TransactionLink();
        link.setId(linkId);

        Transaction anchor = createTestTx(anchorId, account1, TransactionType.DEBIT, BigDecimal.valueOf(1000));
        Transaction counterpart1 = createTestTx(counterpart1Id, account1, TransactionType.CREDIT, BigDecimal.valueOf(500));
        Transaction counterpart2 = createTestTx(counterpart2Id, account1, TransactionType.CREDIT, BigDecimal.valueOf(500));

        TransactionLinkMember anchorMember = new TransactionLinkMember(link, anchor, true);
        TransactionLinkMember member1 = new TransactionLinkMember(link, counterpart1, false);
        TransactionLinkMember member2 = new TransactionLinkMember(link, counterpart2, false);

        link.getMembers().add(anchorMember);
        link.getMembers().add(member1);
        link.getMembers().add(member2);

        when(transactionLinkRepository.findDistinctByMembers_Transaction_IdIn(List.of(counterpart1Id)))
                .thenReturn(List.of(link));

        transactionLinkService.autoDissolveLinksForDeletedTransactions(List.of(counterpart1Id));

        // Link should NOT be dissolved - still has anchor and one counterpart
        verify(transactionLinkRepository, never()).delete(link);
    }

    @Test
    void autoDissolveLinksForDeletedTransactions_threeMemberLink_deleteAnchor() {
        UUID linkId = UUID.randomUUID();
        UUID anchorId = UUID.randomUUID();
        UUID counterpart1Id = UUID.randomUUID();
        UUID counterpart2Id = UUID.randomUUID();

        TransactionLink link = new TransactionLink();
        link.setId(linkId);

        Transaction anchor = createTestTx(anchorId, account1, TransactionType.DEBIT, BigDecimal.valueOf(1000));
        Transaction counterpart1 = createTestTx(counterpart1Id, account1, TransactionType.CREDIT, BigDecimal.valueOf(500));
        Transaction counterpart2 = createTestTx(counterpart2Id, account1, TransactionType.CREDIT, BigDecimal.valueOf(500));

        TransactionLinkMember anchorMember = new TransactionLinkMember(link, anchor, true);
        TransactionLinkMember member1 = new TransactionLinkMember(link, counterpart1, false);
        TransactionLinkMember member2 = new TransactionLinkMember(link, counterpart2, false);

        link.getMembers().add(anchorMember);
        link.getMembers().add(member1);
        link.getMembers().add(member2);

        when(transactionLinkRepository.findDistinctByMembers_Transaction_IdIn(List.of(anchorId)))
                .thenReturn(List.of(link));

        transactionLinkService.autoDissolveLinksForDeletedTransactions(List.of(anchorId));

        // Link should be dissolved - no anchor remaining
        verify(transactionLinkRepository).delete(link);
    }
}
