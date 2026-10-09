package dev.gabnex.marketdata;

import java.math.BigDecimal;
import java.util.List;

public interface MarketDataProvider {
  record Symbol(String symbol, String name, String type, String exchange, String currency) {}
  record Quote(BigDecimal price, String providerTradingDay) {}
  String name();
  List<Symbol> search(String query);
  Quote latestQuote(String symbol);
}
