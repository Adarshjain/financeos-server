package com.financeos.domain.loan.schedule;

import com.financeos.api.loan.dto.InstallmentDto;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.investment.returncalc.XirrCalculator;
import com.financeos.domain.loan.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class LoanScheduleService {

    public ScheduleResult compute(Loan loan, List<LoanEvent> events, List<LoanPayment> payments, List<LoanCharge> charges) {
        return compute(loan, events, payments, charges, LocalDate.now());
    }

    public ScheduleResult compute(Loan loan, List<LoanEvent> events, List<LoanPayment> payments, List<LoanCharge> charges, LocalDate today) {
        if (loan == null) {
            throw new ValidationException("Loan cannot be null");
        }
        if (loan.getPrincipal() == null || loan.getPrincipal().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("Loan principal must be greater than zero");
        }
        if (loan.getTenureMonths() == null || loan.getTenureMonths() < 1 || loan.getTenureMonths() > 600) {
            throw new ValidationException("Loan tenure must be between 1 and 600 months");
        }
        if (loan.getAnnualRatePct() == null || loan.getAnnualRatePct().compareTo(BigDecimal.ZERO) <= 0 || loan.getAnnualRatePct().compareTo(new BigDecimal("60")) > 0) {
            throw new ValidationException("Annual rate percentage must be between 0 and 60%");
        }
        if (loan.getStartDate() == null || loan.getFirstEmiDate() == null) {
            throw new ValidationException("Loan start date and first EMI date are required");
        }
        if (loan.getFirstEmiDate().isBefore(loan.getStartDate())) {
            throw new ValidationException("First EMI date cannot be before loan start date");
        }

        List<LoanEvent> safeEvents = events != null ? new ArrayList<>(events) : Collections.emptyList();
        safeEvents.sort(Comparator.comparing(LoanEvent::getEffectiveDate)
                .thenComparing(e -> getEventTypePriority(e.getEventType())));

        Map<Integer, LoanPayment> paymentMap = payments != null
                ? payments.stream().collect(Collectors.toMap(LoanPayment::getInstallmentSeq, p -> p, (a, b) -> a))
                : Collections.emptyMap();

        List<LoanCharge> safeCharges = charges != null ? charges : Collections.emptyList();

        BigDecimal balance = loan.getPrincipal();
        BigDecimal ratePct = loan.getAnnualRatePct();
        BigDecimal emi = (loan.getEmiAmount() != null && loan.getEmiAmount().compareTo(BigDecimal.ZERO) > 0)
                ? loan.getEmiAmount()
                : calculateAnnuityEmi(balance, ratePct, loan.getTenureMonths());

        int remainingMonths = loan.getTenureMonths();
        int seq = 1;
        LocalDate dueDate = loan.getFirstEmiDate();

        List<InstallmentDto> installments = new ArrayList<>();
        int eventIdx = 0;
        boolean foreclosed = false;
        LocalDate foreclosureDate = null;

        BigDecimal rateAsOfToday = ratePct;
        BigDecimal emiAsOfToday = emi;

        while (balance.compareTo(BigDecimal.ZERO) > 0) {
            if (seq > 600) {
                throw new ValidationException("Schedule exceeds maximum allowed tenure of 600 installments");
            }

            // Apply events dated before the current installment's due date
            while (eventIdx < safeEvents.size() && safeEvents.get(eventIdx).getEffectiveDate().isBefore(dueDate)) {
                LoanEvent event = safeEvents.get(eventIdx++);
                if (event.getEventType() == LoanEventType.rate_change) {
                    ratePct = event.getNewAnnualRatePct();
                    AdjustmentMode mode = event.getAdjustmentMode() != null ? event.getAdjustmentMode() : AdjustmentMode.reduce_tenure;
                    if (mode == AdjustmentMode.reduce_emi) {
                        emi = event.getNewEmiOverride() != null
                                ? event.getNewEmiOverride()
                                : calculateAnnuityEmi(balance, ratePct, Math.max(1, remainingMonths));
                    }
                } else if (event.getEventType() == LoanEventType.prepayment) {
                    balance = balance.subtract(event.getAmount());
                    if (balance.compareTo(BigDecimal.ZERO) <= 0) {
                        balance = BigDecimal.ZERO;
                        break;
                    }
                    AdjustmentMode mode = event.getAdjustmentMode() != null ? event.getAdjustmentMode() : AdjustmentMode.reduce_tenure;
                    if (mode == AdjustmentMode.reduce_emi) {
                        emi = event.getNewEmiOverride() != null
                                ? event.getNewEmiOverride()
                                : calculateAnnuityEmi(balance, ratePct, Math.max(1, remainingMonths));
                    }
                } else if (event.getEventType() == LoanEventType.foreclosure) {
                    foreclosed = true;
                    foreclosureDate = event.getEffectiveDate();
                    break;
                }

                if (event.getEffectiveDate().isBefore(today) || event.getEffectiveDate().isEqual(today)) {
                    rateAsOfToday = ratePct;
                    emiAsOfToday = emi;
                }
            }

            if (foreclosed || balance.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }

            BigDecimal interest = balance.multiply(ratePct).divide(new BigDecimal("1200"), 2, RoundingMode.HALF_UP);
            if (emi.compareTo(interest) <= 0) {
                throw new ValidationException("EMI (" + emi + ") does not cover monthly interest (" + interest + ") — loan is non-amortizing");
            }

            BigDecimal principalPart = emi.subtract(interest).min(balance);
            BigDecimal closingBalance = balance.subtract(principalPart);
            BigDecimal emittedEmi = emi;

            if (closingBalance.compareTo(BigDecimal.ZERO) == 0) {
                principalPart = balance;
                emittedEmi = interest.add(principalPart);
                closingBalance = BigDecimal.ZERO;
            }

            LoanPayment payment = paymentMap.get(seq);
            String status;
            InstallmentDto.PaymentInfo paymentInfo = null;
            if (payment != null) {
                status = "settled";
                paymentInfo = new InstallmentDto.PaymentInfo(
                        payment.getId(),
                        payment.getPaymentDate(),
                        payment.getAmount(),
                        payment.getTransaction() != null ? payment.getTransaction().getId() : null
                );
            } else if (dueDate.isBefore(today)) {
                status = "overdue";
            } else {
                status = "upcoming";
            }

            InstallmentDto dto = new InstallmentDto(
                    seq,
                    dueDate,
                    balance.setScale(2, RoundingMode.HALF_UP),
                    emittedEmi.setScale(2, RoundingMode.HALF_UP),
                    interest.setScale(2, RoundingMode.HALF_UP),
                    principalPart.setScale(2, RoundingMode.HALF_UP),
                    closingBalance.setScale(2, RoundingMode.HALF_UP),
                    status,
                    paymentInfo
            );
            installments.add(dto);

            if (!dueDate.isAfter(today)) {
                rateAsOfToday = ratePct;
                emiAsOfToday = emittedEmi;
            }

            balance = closingBalance;
            seq++;
            remainingMonths = Math.max(1, remainingMonths - 1);
            dueDate = dueDate.plusMonths(1);
        }

        // Calculate derived stats
        BigDecimal outstandingPrincipal;
        if (foreclosed && foreclosureDate != null && !foreclosureDate.isAfter(today)) {
            outstandingPrincipal = BigDecimal.ZERO;
        } else if (installments.isEmpty()) {
            outstandingPrincipal = loan.getPrincipal();
        } else {
            InstallmentDto lastPastInstallment = null;
            for (InstallmentDto inst : installments) {
                if (!inst.dueDate().isAfter(today)) {
                    lastPastInstallment = inst;
                }
            }
            if (lastPastInstallment != null) {
                outstandingPrincipal = lastPastInstallment.closingBalance();
            } else {
                outstandingPrincipal = loan.getPrincipal();
            }
        }

        Integer totalInstallments = installments.size();
        Integer settledInstallments = (int) installments.stream().filter(i -> "settled".equals(i.status())).count();

        LocalDate nextDueDate = installments.stream()
                .filter(i -> !"settled".equals(i.status()))
                .map(InstallmentDto::dueDate)
                .findFirst()
                .orElse(null);

        LocalDate projectedEndDate = installments.isEmpty() ? null : installments.get(installments.size() - 1).dueDate();

        BigDecimal totalInterestPaid = installments.stream()
                .filter(i -> "settled".equals(i.status()))
                .map(InstallmentDto::interest)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalInterestRemaining = installments.stream()
                .filter(i -> !"settled".equals(i.status()))
                .map(InstallmentDto::interest)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Double effectiveAprPct = calculateEffectiveApr(loan, safeEvents, installments, safeCharges);

        return new ScheduleResult(
                installments,
                rateAsOfToday.setScale(2, RoundingMode.HALF_UP),
                emiAsOfToday.setScale(2, RoundingMode.HALF_UP),
                outstandingPrincipal.setScale(2, RoundingMode.HALF_UP),
                totalInstallments,
                settledInstallments,
                nextDueDate,
                projectedEndDate,
                totalInterestPaid.setScale(2, RoundingMode.HALF_UP),
                totalInterestRemaining.setScale(2, RoundingMode.HALF_UP),
                effectiveAprPct
        );
    }

    public BigDecimal calculateAnnuityEmi(BigDecimal principal, BigDecimal annualRatePct, int tenureMonths) {
        if (principal == null || principal.compareTo(BigDecimal.ZERO) <= 0 || tenureMonths <= 0) {
            return BigDecimal.ZERO;
        }
        if (annualRatePct == null || annualRatePct.compareTo(BigDecimal.ZERO) == 0) {
            return principal.divide(BigDecimal.valueOf(tenureMonths), 2, RoundingMode.HALF_UP);
        }
        MathContext mc = MathContext.DECIMAL64;
        BigDecimal r = annualRatePct.divide(BigDecimal.valueOf(1200), mc);
        BigDecimal onePlusR = BigDecimal.ONE.add(r, mc);
        BigDecimal factor = onePlusR.pow(tenureMonths, mc);
        BigDecimal numerator = principal.multiply(r, mc).multiply(factor, mc);
        BigDecimal denominator = factor.subtract(BigDecimal.ONE, mc);
        if (denominator.compareTo(BigDecimal.ZERO) == 0) {
            return principal.divide(BigDecimal.valueOf(tenureMonths), 2, RoundingMode.HALF_UP);
        }
        return numerator.divide(denominator, 2, RoundingMode.HALF_UP);
    }

    private int getEventTypePriority(LoanEventType type) {
        if (type == null) return 99;
        return switch (type) {
            case rate_change -> 1;
            case prepayment -> 2;
            case foreclosure -> 3;
        };
    }

    private Double calculateEffectiveApr(Loan loan, List<LoanEvent> events, List<InstallmentDto> installments, List<LoanCharge> charges) {
        List<XirrCalculator.Cashflow> cashflows = new ArrayList<>();
        // 1. Inflow: principal @ startDate
        cashflows.add(new XirrCalculator.Cashflow(loan.getStartDate(), loan.getPrincipal()));

        // 2. Outflow: charges @ chargeDate
        for (LoanCharge c : charges) {
            if (c.getAmount() != null && c.getAmount().compareTo(BigDecimal.ZERO) > 0) {
                cashflows.add(new XirrCalculator.Cashflow(c.getChargeDate(), c.getAmount().negate()));
            }
        }

        // 3. Outflow: installments (settled @ paymentDate, unsettled @ dueDate)
        for (InstallmentDto inst : installments) {
            if ("settled".equals(inst.status()) && inst.payment() != null) {
                cashflows.add(new XirrCalculator.Cashflow(inst.payment().paymentDate(), inst.payment().amount().negate()));
            } else {
                cashflows.add(new XirrCalculator.Cashflow(inst.dueDate(), inst.emi().negate()));
            }
        }

        // 4. Outflow: prepayments / foreclosures @ effectiveDate
        for (LoanEvent e : events) {
            if ((e.getEventType() == LoanEventType.prepayment || e.getEventType() == LoanEventType.foreclosure)
                    && e.getAmount() != null && e.getAmount().compareTo(BigDecimal.ZERO) > 0) {
                cashflows.add(new XirrCalculator.Cashflow(e.getEffectiveDate(), e.getAmount().negate()));
            }
        }

        Double rawXirr = XirrCalculator.calculateXirr(cashflows);
        if (rawXirr == null || Double.isNaN(rawXirr) || Double.isInfinite(rawXirr)) {
            return null;
        }
        return BigDecimal.valueOf(rawXirr)
                .multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
