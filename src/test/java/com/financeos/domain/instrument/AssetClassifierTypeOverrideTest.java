package com.financeos.domain.instrument;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link AssetClassifier#effective(Instrument, InstrumentType, AssetClass)}: a user's type keeps the classes consistent. */
class AssetClassifierTypeOverrideTest {

    private static Instrument instrument(InstrumentType type, String name, String category, AssetClass stored) {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setType(type);
        i.setName(name);
        i.setSchemeCategory(category);
        i.setAssetClass(stored);
        return i;
    }

    @Test
    void noTypeOverrideIsTheGlobalClassification() {
        Instrument fund = instrument(InstrumentType.mutual_fund, "Axis Liquid", "Open Ended Schemes(Debt Scheme - Liquid Fund)",
                AssetClass.DEBT);
        assertEquals(AssetClassifier.effective(fund), AssetClassifier.effective(fund, null, null));
        assertEquals(AssetClassifier.effective(fund), AssetClassifier.effective(fund, InstrumentType.mutual_fund, null),
                "the same type as the catalog is no override");
    }

    @Test
    void aFundRetypedAsAStockIsEquity() {
        Instrument fund = instrument(InstrumentType.mutual_fund, "Axis Liquid", "Open Ended Schemes(Debt Scheme - Liquid Fund)",
                AssetClass.DEBT);
        AssetClassifier.Classification c = AssetClassifier.effective(fund, InstrumentType.stock, null);
        assertEquals(AssetClass.EQUITY, c.assetClass(), "the stored DEBT class belonged to the fund type");
        assertEquals(TaxClass.EQUITY_ORIENTED, c.taxClass());
    }

    @Test
    void aStockRetypedAsAnEtfUsesTheNameRules() {
        Instrument stock = instrument(InstrumentType.stock, "Nippon India Gold BeES", null, AssetClass.EQUITY);
        AssetClassifier.Classification c = AssetClassifier.effective(stock, InstrumentType.etf, null);
        assertEquals(AssetClass.GOLD, c.assetClass());
        assertEquals(TaxClass.OTHER, c.taxClass());
    }

    @Test
    void anEtfRetypedAsAFundIgnoresNoCategoryAndUsesItsName() {
        Instrument etf = instrument(InstrumentType.etf, "SBI Gilt Fund", null, AssetClass.DEBT);
        AssetClassifier.Classification c = AssetClassifier.effective(etf, InstrumentType.mutual_fund, null);
        assertEquals(AssetClass.DEBT, c.assetClass());
        assertEquals(TaxClass.SPECIFIED_DEBT, c.taxClass());
    }

    @Test
    void thePinnedClassWinsAndTheTaxClassFollowsTheUsersType() {
        Instrument stock = instrument(InstrumentType.stock, "Infosys", null, AssetClass.EQUITY);
        AssetClassifier.Classification asFund = AssetClassifier.effective(stock, InstrumentType.mutual_fund, AssetClass.DEBT);
        assertEquals(AssetClass.DEBT, asFund.assetClass());
        assertEquals(TaxClass.SPECIFIED_DEBT, asFund.taxClass(), "no longer a stock, so debt is slab-taxed");

        AssetClassifier.Classification stillStock = AssetClassifier.effective(stock, null, AssetClass.DEBT);
        assertEquals(AssetClass.DEBT, stillStock.assetClass());
        assertEquals(TaxClass.EQUITY_ORIENTED, stillStock.taxClass(), "a stock is equity-oriented whatever the class");
    }
}
