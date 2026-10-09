package dev.gabnex.marketdata;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MarketDataServiceTest {
  static class FakeDb extends JdbcTemplate {
    final Deque<List<Map<String,Object>>> rows=new ArrayDeque<>(); final List<String> writes=new ArrayList<>();
    @Override public List<Map<String,Object>> queryForList(String sql,Object... args){return rows.isEmpty()?List.of():rows.removeFirst();}
    @Override public int update(String sql,Object... args){writes.add(sql);return 1;}
  }
  static class FakeProvider implements MarketDataProvider {
    int quoteCalls; Quote quote;
    public String name(){return "TestProvider";}
    public List<Symbol> search(String query){return List.of();}
    public Quote latestQuote(String symbol){quoteCalls++;return quote;}
  }
  private FakeDb database(List<Map<String,Object>>... rows){var db=new FakeDb();db.rows.addAll(Arrays.asList(rows));return db;}
  private Map<String,Object> asset(){return Map.of("id",1L,"currency","USD","provider_symbol","ABC");}

  @Test void missingKeyReturnsUnavailableWithoutCallingProviderOrSaving() {
    var db=database(List.of(asset()),List.of());var provider=new FakeProvider();
    var result=new MarketDataService(db,provider,"",1440).price(1,false);
    assertEquals(false,result.get("available"));assertTrue(result.get("message").toString().contains("not configured"));
    assertEquals(0,provider.quoteCalls);assertTrue(db.writes.isEmpty());
  }
  @Test void freshSnapshotIsReturnedWithoutProviderRequest() {
    var retrieved=OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2);
    var cached=Map.<String,Object>of("price",new BigDecimal("42.25"),"currency","USD","provider","Alpha Vantage","retrieved_at",retrieved,"classification","END_OF_DAY");
    var db=database(List.of(asset()),List.of(cached));var provider=new FakeProvider();
    var result=new MarketDataService(db,provider,"present",1440).price(1,false);
    assertEquals(new BigDecimal("42.25"),result.get("price"));assertEquals(false,result.get("stale"));assertEquals(0,provider.quoteCalls);
  }
  @Test void invalidProviderPriceIsRejectedWithoutSavingSnapshot() {
    var db=database(List.of(asset()),List.of());var provider=new FakeProvider();provider.quote=new MarketDataProvider.Quote(new BigDecimal("-2"),"2026-10-01");
    var result=new MarketDataService(db,provider,"present",1440).price(1,true);
    assertEquals(false,result.get("available"));assertTrue(result.get("message").toString().contains("valid quote"));assertTrue(db.writes.isEmpty());
  }
  @Test void providerFailureKeepsTheLastQuoteMarkedStale() {
    var retrieved=OffsetDateTime.now(ZoneOffset.UTC).minusDays(2);
    var last=Map.<String,Object>of("price",new BigDecimal("41"),"currency","USD","provider","TestProvider","retrieved_at",retrieved,"classification","END_OF_DAY");
    var db=database(List.of(asset()),List.of(last));var provider=new FakeProvider(){@Override public Quote latestQuote(String symbol){quoteCalls++;throw new IllegalStateException("provider down");}};
    var result=new MarketDataService(db,provider,"present",60).price(1,true);
    assertEquals(false,result.get("available"));assertEquals(true,result.get("stale"));assertEquals(new BigDecimal("41"),result.get("lastPrice"));assertEquals(retrieved,result.get("retrievedAt"));assertTrue(db.writes.isEmpty());
  }
  @Test void validQuoteIsPersistedWithProviderAndClassification() {
    var db=database(List.of(asset()),List.of());var provider=new FakeProvider();provider.quote=new MarketDataProvider.Quote(new BigDecimal("42.50"),"2026-10-01");
    var result=new MarketDataService(db,provider,"present",1440).price(1,true);
    assertEquals(true,result.get("available"));assertEquals(new BigDecimal("42.50"),result.get("price"));assertEquals("END_OF_DAY",result.get("classification"));assertEquals("TestProvider",result.get("provider"));assertEquals(1,db.writes.size());
  }
}
