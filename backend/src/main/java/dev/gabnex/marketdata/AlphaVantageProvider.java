package dev.gabnex.marketdata;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.util.UriComponentsBuilder;
import java.math.BigDecimal;
import java.util.*;

@Component
public class AlphaVantageProvider implements MarketDataProvider {
  private final RestClient http=buildClient();
  private final String baseUrl,key;
  public AlphaVantageProvider(@Value("${market-data.base-url}") String baseUrl,@Value("${market-data.api-key}") String key) { this.baseUrl=baseUrl;this.key=key; }
  private RestClient buildClient() {
    var client=java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build();
    var factory=new JdkClientHttpRequestFactory(client); factory.setReadTimeout(java.time.Duration.ofSeconds(5));
    return RestClient.builder().requestFactory(factory).build();
  }
  @Override public String name(){return "Alpha Vantage";}
  private java.net.URI uri(String function,String keyName,String value) { return UriComponentsBuilder.fromUriString(baseUrl).queryParam("function",function).queryParam(keyName,value).queryParam("apikey",key).build().encode().toUri(); }
  @Override public List<Symbol> search(String query) {
    if(key.isBlank()) return List.of();
    Map<?,?> body=http.get().uri(uri("SYMBOL_SEARCH","keywords",query)).retrieve().body(Map.class);
    Object matches=body==null?null:body.get("bestMatches"); if(!(matches instanceof List<?> list))return List.of(); var output=new ArrayList<Symbol>();
    for(Object item:list) if(item instanceof Map<?,?> m) {
      String symbol=value(m,"1. symbol"),name=value(m,"2. name"),type=value(m,"3. type"),normalized=type.toLowerCase(Locale.ROOT);
      if(!normalized.contains("equity")&&!normalized.contains("stock")&&!normalized.equals("etf")) continue;
      if(!symbol.isBlank()&&!name.isBlank())output.add(new Symbol(symbol,name,type,value(m,"4. region"),value(m,"8. currency")));
    }
    return output;
  }
  @Override public Quote latestQuote(String symbol) {
    if(key.isBlank())return null;
    Map<?,?> body=http.get().uri(uri("GLOBAL_QUOTE","symbol",symbol)).retrieve().body(Map.class);
    Object raw=body==null?null:body.get("Global Quote"); if(!(raw instanceof Map<?,?> quote)||quote.get("05. price")==null)return null;
    BigDecimal price;try{price=new BigDecimal(quote.get("05. price").toString());}catch(NumberFormatException e){return null;}
    if(price.signum()<=0)return null;return new Quote(price,value(quote,"07. latest trading day"));
  }
  private String value(Map<?,?> map,String key){return Objects.toString(map.get(key),"");}
}
