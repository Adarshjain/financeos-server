package com.financeos.domain.investment.returncalc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

public class XirrCalculator {

    public record Cashflow(
            LocalDate date,
            BigDecimal amount
    ) {}

    public static Double calculateXirr(List<Cashflow> cashflows) {
        if (cashflows == null || cashflows.size() < 2) {
            return null;
        }

        boolean hasPositive = false;
        boolean hasNegative = false;
        for (Cashflow c : cashflows) {
            if (c.amount() != null) {
                if (c.amount().compareTo(BigDecimal.ZERO) > 0) {
                    hasPositive = true;
                } else if (c.amount().compareTo(BigDecimal.ZERO) < 0) {
                    hasNegative = true;
                }
            }
        }

        if (!hasPositive || !hasNegative) {
            return null;
        }

        LocalDate startDate = cashflows.get(0).date();
        for (Cashflow c : cashflows) {
            if (c.date().isBefore(startDate)) {
                startDate = c.date();
            }
        }

        double rate = 0.10; // 10% initial guess
        int maxIter = 100;
        double tol = 1e-7;

        for (int i = 0; i < maxIter; i++) {
            if (rate <= -0.9999) {
                break;
            }

            double fValue = 0.0;
            double fDerivative = 0.0;
            boolean invalidStep = false;

            for (Cashflow c : cashflows) {
                if (c.amount() == null || c.amount().compareTo(BigDecimal.ZERO) == 0) {
                    continue;
                }
                double days = ChronoUnit.DAYS.between(startDate, c.date());
                double years = days / 365.0;
                double amount = c.amount().doubleValue();

                double denom = Math.pow(1.0 + rate, years);
                if (Double.isNaN(denom) || Double.isInfinite(denom) || denom == 0.0) {
                    invalidStep = true;
                    break;
                }

                fValue += amount / denom;
                fDerivative -= years * amount / (denom * (1.0 + rate));
            }

            if (invalidStep) {
                break;
            }

            if (Math.abs(fValue) < tol) {
                return rate;
            }

            if (Math.abs(fDerivative) < 1e-12) {
                break;
            }

            double newRate = rate - fValue / fDerivative;
            if (Double.isNaN(newRate) || Double.isInfinite(newRate) || newRate <= -0.9999) {
                break;
            }

            if (Math.abs(newRate - rate) < tol) {
                return newRate;
            }

            rate = newRate;
        }

        // Fallback to Bisection search
        return calculateBisection(cashflows, startDate);
    }

    private static Double calculateBisection(List<Cashflow> cashflows, LocalDate startDate) {
        double low = -0.9999;
        double high = 10.0; // 1000%
        double tol = 1e-6;

        double fLow = evaluateF(cashflows, startDate, low);
        double fHigh = evaluateF(cashflows, startDate, high);

        if (fLow * fHigh > 0) {
            return null;
        }

        double mid = 0.0;
        for (int i = 0; i < 100; i++) {
            mid = (low + high) / 2.0;
            double fMid = evaluateF(cashflows, startDate, mid);

            if (Math.abs(fMid) < tol || (high - low) / 2.0 < tol) {
                return mid;
            }

            if (fLow * fMid < 0) {
                high = mid;
                fHigh = fMid;
            } else {
                low = mid;
                fLow = fMid;
            }
        }

        return mid;
    }

    private static double evaluateF(List<Cashflow> cashflows, LocalDate startDate, double rate) {
        double sum = 0.0;
        for (Cashflow c : cashflows) {
            if (c.amount() == null || c.amount().compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            double days = ChronoUnit.DAYS.between(startDate, c.date());
            double years = days / 365.0;
            double amount = c.amount().doubleValue();
            sum += amount / Math.pow(1.0 + rate, years);
        }
        return sum;
    }
}
