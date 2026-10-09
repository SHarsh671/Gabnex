package dev.gabnex.transaction;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Weighted-average cost in transaction order. Fees are included in basis/proceeds. */
public final class WeightedAverageCost {
  private WeightedAverageCost() {}
  public record Result(BigDecimal quantity, BigDecimal costBasis, BigDecimal realizedPnl) {}
  public static Result apply(Result state, String type, BigDecimal quantity, BigDecimal price, BigDecimal fee) {
    BigDecimal q=state.quantity(), cost=state.costBasis(), realized=state.realizedPnl();
    if ("BUY".equals(type)) return new Result(q.add(quantity),cost.add(quantity.multiply(price).add(fee)),realized);
    if (!"SELL".equals(type) || q.compareTo(quantity)<0) throw new IllegalArgumentException("Sell quantity exceeds shares held");
    BigDecimal soldCost=quantity.compareTo(q)==0?cost:cost.divide(q,16,RoundingMode.HALF_EVEN).multiply(quantity);
    BigDecimal remain=q.subtract(quantity);
    return new Result(remain,remain.signum()==0?BigDecimal.ZERO:cost.subtract(soldCost),realized.add(quantity.multiply(price).subtract(fee).subtract(soldCost)));
  }
  public static Result empty() { return new Result(BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO); }
}
