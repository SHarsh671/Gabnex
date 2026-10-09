package dev.gabnex.marketdata;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Coordinates provider calls, symbol persistence, quote validation and snapshot caching. */
@Service
public class MarketDataService {
  private final JdbcTemplate db;
  private final MarketDataProvider provider;
  private final String apiKey;
  private final int cacheMinutes;
  public MarketDataService(JdbcTemplate db,MarketDataProvider provider,@Value("${market-data.api-key}") String apiKey,@Value("${market-data.cache-minutes}") int cacheMinutes) {
    this.db=db;this.provider=provider;this.apiKey=apiKey;this.cacheMinutes=cacheMinutes;
  }
  public int cacheMinutes(){return cacheMinutes;}
  private Map<String,Object> one(String sql,Object... args){var rows=db.queryForList(sql,args);return rows.isEmpty()?null:rows.get(0);}
  public Map<String,Object> status(){return Map.of("provider",provider.name(),"configured",!apiKey.isBlank(),"classification","END_OF_DAY","cacheMinutes",cacheMinutes);}
  public Map<String,Object> search(String query){
    if(apiKey.isBlank())return Map.of("available",false,"message","Configure MARKET_DATA_API_KEY to search supported symbols","results",List.of());
    var results=new ArrayList<Map<String,Object>>();
    for(var item:provider.search(query)){
      db.update("insert into asset(provider,provider_symbol,ticker,name,exchange,currency,asset_type) values('ALPHA_VANTAGE',?,?,?,?,?,?) on conflict(provider,provider_symbol) do update set ticker=excluded.ticker,name=excluded.name,exchange=excluded.exchange,currency=excluded.currency,asset_type=excluded.asset_type",item.symbol(),item.symbol(),item.name(),item.exchange(),item.currency(),item.type());
      var saved=one("select id from asset where provider='ALPHA_VANTAGE' and provider_symbol=?",item.symbol());
      results.add(Map.of("id",saved.get("id"),"symbol",item.symbol(),"name",item.name(),"type",item.type(),"exchange",item.exchange(),"currency",item.currency()));
    }
    return Map.of("available",true,"provider",provider.name(),"results",results);
  }
  public Map<String,Object> price(long assetId,boolean forceRefresh){
    var asset=one("select id,currency,provider_symbol from asset where id=?",assetId);if(asset==null)throw new NoSuchElementException("Asset not found");
    var last=one("select price,currency,provider,provider_timestamp,retrieved_at,classification from market_price_snapshot where asset_id=? order by retrieved_at desc limit 1",assetId);
    if(!forceRefresh&&last!=null&&((OffsetDateTime)last.get("retrieved_at")).isAfter(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(cacheMinutes))){var cached=new LinkedHashMap<String,Object>();cached.put("available",true);cached.put("price",last.get("price"));cached.put("currency",last.get("currency"));cached.put("provider",last.get("provider"));cached.put("retrievedAt",last.get("retrieved_at"));cached.put("providerTimestamp",last.get("provider_timestamp"));cached.put("classification",last.get("classification"));cached.put("stale",false);return cached;}
    if(apiKey.isBlank())return unavailable(last,"Market data is not configured. Set MARKET_DATA_API_KEY.");
    try{
      var quote=provider.latestQuote((String)asset.get("provider_symbol"));if(quote==null)return unavailable(last,"No valid quote was returned for this symbol.");
      BigDecimal price=quote.price();if(price==null||price.signum()<=0)return unavailable(last,"Provider returned an invalid quote.");
      OffsetDateTime retrieved=OffsetDateTime.now(ZoneOffset.UTC),providerTime=null;
      try{if(quote.providerTradingDay()!=null&&!quote.providerTradingDay().isBlank())providerTime=LocalDate.parse(quote.providerTradingDay()).atStartOfDay().atOffset(ZoneOffset.UTC);}catch(DateTimeException ignored){}
      db.update("insert into market_price_snapshot(asset_id,price,currency,provider,provider_timestamp,retrieved_at,classification) values(?,?,?, ?,?,?, 'END_OF_DAY')",assetId,price,asset.get("currency"),provider.name(),providerTime,retrieved);
      var result=new LinkedHashMap<String,Object>();result.put("available",true);result.put("price",price);result.put("currency",Objects.toString(asset.get("currency"),""));result.put("provider",provider.name());result.put("retrievedAt",retrieved);result.put("providerTimestamp",quote.providerTradingDay());result.put("classification","END_OF_DAY");result.put("stale",false);return result;
    }catch(Exception ex){return unavailable(last,"Market data could not be retrieved. The last saved quote, if any, is shown with its original timestamp.");}
  }
  private Map<String,Object> unavailable(Map<String,Object> last,String message){var result=new LinkedHashMap<String,Object>();result.put("available",false);result.put("message",message);if(last!=null){result.put("lastPrice",last.get("price"));result.put("currency",last.get("currency"));result.put("provider",last.get("provider"));result.put("retrievedAt",last.get("retrieved_at"));result.put("providerTimestamp",last.get("provider_timestamp"));result.put("stale",true);result.put("classification",last.get("classification"));}return result;}
}
