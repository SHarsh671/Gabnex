package dev.gabnex.transaction;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class WeightedAverageCostTest {
  private BigDecimal d(String x) { return new BigDecimal(x); }
  private void assertDecimal(String expected,BigDecimal actual) { assertEquals(0,d(expected).compareTo(actual)); }
  @Test void weightedCostFeesAndPartialSale() {
    var s=WeightedAverageCost.empty();
    s=WeightedAverageCost.apply(s,"BUY",d("10"),d("10"),d("2"));
    s=WeightedAverageCost.apply(s,"BUY",d("10"),d("20"),d("2"));
    assertDecimal("304",s.costBasis());
    s=WeightedAverageCost.apply(s,"SELL",d("5"),d("25"),d("1"));
    assertDecimal("15",s.quantity());
    assertDecimal("228",s.costBasis());
    assertDecimal("48",s.realizedPnl());
  }
  @Test void liquidationClearsResidualBasisAndUsesNetProceeds() {
    var s=WeightedAverageCost.apply(WeightedAverageCost.empty(),"BUY",d("3"),d("10"),d("1"));
    s=WeightedAverageCost.apply(s,"SELL",d("3"),d("12"),d("2"));
    assertDecimal("0",s.quantity()); assertDecimal("0",s.costBasis()); assertDecimal("3",s.realizedPnl());
  }
  @Test void rejectsOversell() { assertThrows(IllegalArgumentException.class,()->WeightedAverageCost.apply(WeightedAverageCost.empty(),"SELL",d("1"),d("12"),d("0"))); }
  @Test void replayAfterEditingAnEarlierBuyRecalculatesOpenAndRealizedAmounts() {
    var s=WeightedAverageCost.empty();
    s=WeightedAverageCost.apply(s,"BUY",d("5"),d("12"),d("0"));
    s=WeightedAverageCost.apply(s,"BUY",d("5"),d("20"),d("0"));
    s=WeightedAverageCost.apply(s,"SELL",d("4"),d("30"),d("0"));
    assertDecimal("6",s.quantity());
    assertDecimal("96",s.costBasis());
    assertDecimal("56",s.realizedPnl());
  }
}
