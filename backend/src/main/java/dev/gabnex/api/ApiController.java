package dev.gabnex.api;

import dev.gabnex.security.SecurityConfig.JwtService;
import dev.gabnex.transaction.WeightedAverageCost;
import dev.gabnex.marketdata.MarketDataService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.math.*;
import java.time.*;
import java.util.*;

@RestController
@Validated
@RequestMapping("/api/v1")
public class ApiController {
  private final JdbcTemplate db; private final PasswordEncoder passwords; private final JwtService jwt;
  private final MarketDataService marketData;
  public ApiController(JdbcTemplate db, PasswordEncoder passwords, JwtService jwt, MarketDataService marketData) {
    this.db=db;this.passwords=passwords;this.jwt=jwt;this.marketData=marketData;
  }
  public record Register(@NotBlank @Size(max=120) String name, @Email @NotBlank String email, @NotBlank @Size(min=10,max=72) String password) {}
  public record Login(@Email @NotBlank String email, @NotBlank String password) {}
  public record PortfolioInput(@NotBlank @Size(max=120) String name, @Pattern(regexp="USD|EUR|GBP|CAD|AUD") String baseCurrency) {}
  public record TxInput(@NotNull Long assetId, @NotNull @Pattern(regexp="BUY|SELL") String type, @NotNull @DecimalMin(value="0.00000001") BigDecimal quantity, @NotNull @DecimalMin(value="0.00000001") BigDecimal executionPrice, @DecimalMin("0") BigDecimal fees, @NotNull OffsetDateTime occurredAt, @Size(max=1000) String notes) {}
  public record WatchInput(@NotNull Long assetId) {}
  private long uid(Authentication a) { return ((Number)a.getPrincipal()).longValue(); }
  private Map<String,Object> one(String sql,Object... args) { var x=db.queryForList(sql,args); return x.isEmpty()?null:x.get(0); }
  private void owns(long id,long user) { if(one("select id from portfolio where id=? and owner_id=?",id,user)==null) throw new NoSuchElementException("Portfolio not found"); }

  @PostMapping("/auth/register") @Transactional
  public ResponseEntity<?> register(@Valid @RequestBody Register in) {
    if(one("select id from app_user where lower(email)=lower(?)",in.email())!=null) return ResponseEntity.status(409).body(Map.of("error","Email is already registered"));
    db.update("insert into app_user(name,email,password_hash) values(?,?,?)",in.name().trim(),in.email().toLowerCase(),passwords.encode(in.password()));
    var u=one("select id,name,email from app_user where lower(email)=lower(?)",in.email()); return ResponseEntity.status(201).body(authBody(u));
  }
  @PostMapping("/auth/login") public Object login(@Valid @RequestBody Login in) {
    var u=one("select id,name,email,password_hash from app_user where lower(email)=lower(?)",in.email());
    if(u==null || !passwords.matches(in.password(),(String)u.get("password_hash"))) return ResponseEntity.status(401).body(Map.of("error","Email or password is incorrect"));
    return authBody(u);
  }
  private Map<String,Object> authBody(Map<String,Object> u) { long id=((Number)u.get("id")).longValue(); return Map.of("token",jwt.create(id,(String)u.get("email")),"user",Map.of("id",id,"name",u.get("name"),"email",u.get("email"))); }
  @PostMapping("/auth/logout") public Object logout() { return Map.of("message","Signed out. Remove the bearer token from the client."); }
  @GetMapping("/auth/me") public Object me(Authentication a) { return one("select id,name,email,created_at from app_user where id=?",uid(a)); }
  @GetMapping("/market-data/status") public Object marketDataStatus() { return marketData.status(); }

  @GetMapping("/portfolios") public Object portfolios(Authentication a) { return db.queryForList("select id,name,base_currency,created_at,updated_at from portfolio where owner_id=? order by id",uid(a)); }
  @PostMapping("/portfolios") @Transactional public ResponseEntity<?> createPortfolio(Authentication a,@Valid @RequestBody PortfolioInput in) {
    String currency=in.baseCurrency()==null?"USD":in.baseCurrency(); db.update("insert into portfolio(owner_id,name,base_currency) values(?,?,?)",uid(a),in.name().trim(),currency);
    return ResponseEntity.status(201).body(one("select id,name,base_currency,created_at from portfolio where owner_id=? order by id desc limit 1",uid(a)));
  }
  @GetMapping("/portfolios/{id}") public Object portfolio(Authentication a,@PathVariable long id) { owns(id,uid(a)); return one("select id,name,base_currency,created_at,updated_at from portfolio where id=?",id); }
  @PutMapping("/portfolios/{id}") public Object updatePortfolio(Authentication a,@PathVariable long id,@Valid @RequestBody PortfolioInput in) { owns(id,uid(a));var old=one("select base_currency from portfolio where id=?",id);String requested=in.baseCurrency()==null?old.get("base_currency").toString().trim():in.baseCurrency();if(!Objects.equals(old.get("base_currency").toString().trim(),requested)&&one("select id from investment_transaction where portfolio_id=? limit 1",id)!=null)throw new IllegalArgumentException("Base currency cannot change after transactions have been recorded");db.update("update portfolio set name=?,base_currency=?,updated_at=now() where id=?",in.name().trim(),requested,id);return portfolio(a,id); }
  @DeleteMapping("/portfolios/{id}") public ResponseEntity<?> deletePortfolio(Authentication a,@PathVariable long id) { owns(id,uid(a)); db.update("delete from portfolio where id=?",id); return ResponseEntity.noContent().build(); }

  @GetMapping("/assets/search") public Object search(@RequestParam @Size(min=1,max=80) String query) { return marketData.search(query); }
  @GetMapping("/assets/{id}") public Map<String,Object> asset(@PathVariable long id) { var a=one("select id,ticker,name,exchange,currency,asset_type,provider_symbol from asset where id=?",id); if(a==null) throw new NoSuchElementException("Asset not found"); return a; }
  @GetMapping("/assets/{id}/price") public Object price(@PathVariable long id) { return marketData.price(id,false); }
  @PostMapping("/assets/{id}/refresh-price") public Object refresh(@PathVariable long id) { return marketData.price(id,true); }

  private long upsertAsset(long id) { if(one("select id from asset where id=?",id)==null) throw new IllegalArgumentException("Unknown asset"); return id; }
  private long validateAssetCurrency(long portfolio,long assetId) {
    var currencies=one("select p.base_currency,a.currency from portfolio p cross join asset a where p.id=? and a.id=?",portfolio,assetId);
    if(currencies==null)throw new IllegalArgumentException("Unknown portfolio or asset"); String base=Objects.toString(currencies.get("base_currency"),"").trim(),assetCurrency=Objects.toString(currencies.get("currency"),"").trim();
    if(!assetCurrency.isBlank()&&!base.equalsIgnoreCase(assetCurrency))throw new IllegalArgumentException("Asset currency must match the portfolio base currency; FX conversion is not supported");return assetId;
  }
  private void validateTransactionDate(TxInput in) { if(in.occurredAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1)))throw new IllegalArgumentException("Transaction date cannot be in the future"); }
  @GetMapping("/portfolios/{id}/transactions") public Object transactions(Authentication a,@PathVariable long id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size,@RequestParam(required=false) @Pattern(regexp="BUY|SELL") String type,@RequestParam(defaultValue="occurredAt") @Pattern(regexp="occurredAt|type|quantity") String sort,@RequestParam(defaultValue="DESC") @Pattern(regexp="ASC|DESC") String direction,@RequestParam(required=false) LocalDate fromDate,@RequestParam(required=false) LocalDate toDate) {
    owns(id,uid(a));if(page<0||page>100000||size<1||size>100)throw new IllegalArgumentException("Invalid pagination");if(fromDate!=null&&toDate!=null&&fromDate.isAfter(toDate))throw new IllegalArgumentException("fromDate must be on or before toDate");if(toDate!=null&&toDate.isAfter(LocalDate.now(ZoneOffset.UTC)))throw new IllegalArgumentException("toDate cannot be in the future");String order=switch(sort){case "type"->"t.type";case "quantity"->"t.quantity";default->"t.occurred_at";};var args=new ArrayList<Object>();args.add(id);StringBuilder filter=new StringBuilder();if(type!=null){filter.append(" and t.type=?");args.add(type);}if(fromDate!=null){filter.append(" and t.occurred_at>=?");args.add(fromDate.atStartOfDay().atOffset(ZoneOffset.UTC));}if(toDate!=null){filter.append(" and t.occurred_at<?");args.add(toDate.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC));}args.add(size);args.add(page*size);return db.queryForList("select t.id,t.type,t.quantity,t.execution_price,t.fees,t.occurred_at,t.notes,a.id asset_id,a.ticker,a.name asset_name from investment_transaction t join asset a on a.id=t.asset_id where t.portfolio_id=?"+filter+" order by "+order+" "+direction+",t.id desc limit ? offset ?",args.toArray());
  }
  @PostMapping("/portfolios/{id}/transactions") @Transactional public ResponseEntity<?> addTransaction(Authentication a,@PathVariable long id,@Valid @RequestBody TxInput in) { owns(id,uid(a));validateTransactionDate(in);validateAssetCurrency(id,upsertAsset(in.assetId()));validateSell(id,in,null);db.update("insert into investment_transaction(portfolio_id,asset_id,type,quantity,execution_price,fees,occurred_at,notes) values(?,?,?,?,?,?,?,?)",id,in.assetId(),in.type(),in.quantity(),in.executionPrice(),in.fees()==null?BigDecimal.ZERO:in.fees(),in.occurredAt(),in.notes());return ResponseEntity.status(201).body(db.queryForList("select * from investment_transaction where portfolio_id=? order by id desc limit 1",id).getFirst()); }
  private void validateSell(long portfolio,TxInput in,Long exclude) {
    var history=db.queryForList("select type,quantity,occurred_at,id from investment_transaction where portfolio_id=? and asset_id=?"+(exclude==null?"":" and id<>?"),exclude==null?new Object[]{portfolio,in.assetId()}:new Object[]{portfolio,in.assetId(),exclude});
    history.add(Map.of("type",in.type(),"quantity",in.quantity(),"occurred_at",in.occurredAt(),"id",exclude==null?Long.MAX_VALUE:exclude));
    history.sort(Comparator.comparing((Map<String,Object> r)->eventInstant(r.get("occurred_at"))).thenComparing(r->((Number)r.get("id")).longValue()));
    BigDecimal shares=BigDecimal.ZERO;
    for(var row:history){BigDecimal q=(BigDecimal)row.get("quantity"); if("BUY".equals(row.get("type"))) shares=shares.add(q); else { if(shares.compareTo(q)<0) throw new IllegalArgumentException("Transaction history would sell more shares than held on that date"); shares=shares.subtract(q); }}
  }
  private Instant eventInstant(Object value) { if(value instanceof OffsetDateTime t)return t.toInstant(); if(value instanceof java.sql.Timestamp t)return t.toInstant(); if(value instanceof Instant t)return t; return OffsetDateTime.parse(value.toString()).toInstant(); }
  @GetMapping("/transactions/{id}") public Object transaction(Authentication a,@PathVariable long id) { var t=one("select t.*,a.ticker,a.name asset_name from investment_transaction t join portfolio p on p.id=t.portfolio_id join asset a on a.id=t.asset_id where t.id=? and p.owner_id=?",id,uid(a)); if(t==null) throw new NoSuchElementException("Transaction not found"); return t; }
  @PutMapping("/transactions/{id}") @Transactional public Object updateTransaction(Authentication a,@PathVariable long id,@Valid @RequestBody TxInput in) { var old=one("select t.portfolio_id,t.asset_id from investment_transaction t join portfolio p on p.id=t.portfolio_id where t.id=? and p.owner_id=?",id,uid(a)); if(old==null) throw new NoSuchElementException("Transaction not found");long portfolio=((Number)old.get("portfolio_id")).longValue();long oldAsset=((Number)old.get("asset_id")).longValue();validateTransactionDate(in);validateAssetCurrency(portfolio,upsertAsset(in.assetId()));validateSell(portfolio,in,id);if(oldAsset!=in.assetId())validateExistingHistory(portfolio,oldAsset,id);db.update("update investment_transaction set asset_id=?,type=?,quantity=?,execution_price=?,fees=?,occurred_at=?,notes=?,updated_at=now() where id=?",in.assetId(),in.type(),in.quantity(),in.executionPrice(),in.fees()==null?BigDecimal.ZERO:in.fees(),in.occurredAt(),in.notes(),id);return transaction(a,id); }
  @DeleteMapping("/transactions/{id}") @Transactional public ResponseEntity<?> deleteTransaction(Authentication a,@PathVariable long id) { var t=one("select t.portfolio_id,t.asset_id from investment_transaction t join portfolio p on p.id=t.portfolio_id where t.id=? and p.owner_id=?",id,uid(a)); if(t==null)throw new NoSuchElementException("Transaction not found");validateExistingHistory(((Number)t.get("portfolio_id")).longValue(),((Number)t.get("asset_id")).longValue(),id);db.update("delete from investment_transaction where id=?",id);return ResponseEntity.noContent().build(); }
  private void validateExistingHistory(long portfolio,long asset,Long excluded) {
    var rows=db.queryForList("select type,quantity,occurred_at,id from investment_transaction where portfolio_id=? and asset_id=? and id<>? order by occurred_at,id",portfolio,asset,excluded); BigDecimal shares=BigDecimal.ZERO;
    for(var row:rows){BigDecimal q=(BigDecimal)row.get("quantity");if("BUY".equals(row.get("type")))shares=shares.add(q);else{if(shares.compareTo(q)<0)throw new IllegalArgumentException("This change would leave a sale without enough shares on its date");shares=shares.subtract(q);}}
  }

  @GetMapping("/portfolios/{id}/holdings") public Object holdings(Authentication a,@PathVariable long id) { owns(id,uid(a)); return holdingsData(id); }
  @GetMapping("/portfolios/{id}/holdings/{assetId}") public Object holding(Authentication a,@PathVariable long id,@PathVariable long assetId) { owns(id,uid(a)); return holdingsData(id).stream().filter(x->Objects.equals(((Number)x.get("assetId")).longValue(),assetId)).findFirst().orElseThrow(()->new NoSuchElementException("Holding not found")); }
  private List<Map<String,Object>> holdingsData(long id) {
    var rows=db.queryForList("select a.id asset_id,a.ticker,a.name,a.currency,t.type,t.quantity,t.execution_price,t.fees,t.occurred_at from investment_transaction t join asset a on a.id=t.asset_id where t.portfolio_id=? order by t.occurred_at,t.id",id);
    class H { WeightedAverageCost.Result state=WeightedAverageCost.empty(); String ticker,name,currency; }
    var hs=new LinkedHashMap<Long,H>();
    for(var r:rows){ long aid=((Number)r.get("asset_id")).longValue(); H h=hs.computeIfAbsent(aid,k->new H()); h.ticker=(String)r.get("ticker");h.name=(String)r.get("name");h.currency=(String)r.get("currency"); BigDecimal q=(BigDecimal)r.get("quantity"), p=(BigDecimal)r.get("execution_price"), fee=(BigDecimal)r.get("fees");
      h.state=WeightedAverageCost.apply(h.state,(String)r.get("type"),q,p,fee);
    }
    var out=new ArrayList<Map<String,Object>>(); for(var e:hs.entrySet()){H h=e.getValue();BigDecimal qty=h.state.quantity(),cost=h.state.costBasis(); if(qty.signum()==0) continue; var p=one("select price,currency,provider,retrieved_at,classification from market_price_snapshot where asset_id=? order by retrieved_at desc limit 1",e.getKey()); var m=new LinkedHashMap<String,Object>();m.put("assetId",e.getKey());m.put("ticker",h.ticker);m.put("name",h.name);m.put("currency",h.currency);m.put("quantity",qty);m.put("averageCost",cost.divide(qty,8,RoundingMode.HALF_EVEN));m.put("costBasis",cost);m.put("realizedPnl",h.state.realizedPnl());
      if(p!=null){BigDecimal price=(BigDecimal)p.get("price");Object retrieved=p.get("retrieved_at");boolean stale=eventInstant(retrieved).isBefore(Instant.now().minus(Duration.ofMinutes(marketData.cacheMinutes())));m.put("marketPrice",price);m.put("marketValue",price.multiply(qty));m.put("unrealizedPnl",price.multiply(qty).subtract(cost));m.put("priceRetrievedAt",retrieved);m.put("priceProvider",p.get("provider"));m.put("priceClassification",p.get("classification"));m.put("priceStale",stale);} else {m.put("marketPrice",null);m.put("marketValue",null);m.put("unrealizedPnl",null);m.put("priceUnavailable",true);m.put("priceStale",true);} out.add(m); }
    return out;
  }
  @GetMapping("/portfolios/{id}/allocation") public Object allocation(Authentication a,@PathVariable long id) { owns(id,uid(a));var hs=holdingsData(id);boolean incomplete=hs.stream().anyMatch(h->h.get("marketValue")==null||Boolean.TRUE.equals(h.get("priceStale")));BigDecimal total=hs.stream().filter(h->h.get("marketValue")!=null).map(h->(BigDecimal)h.get("marketValue")).reduce(BigDecimal.ZERO,BigDecimal::add);return Map.of("complete",!incomplete,"totalAvailableMarketValue",total,"items",hs.stream().map(h->{var x=new HashMap<>(h);if(total.signum()>0&&h.get("marketValue")!=null)x.put("allocationPercent",((BigDecimal)h.get("marketValue")).multiply(BigDecimal.valueOf(100)).divide(total,2,RoundingMode.HALF_UP));else x.put("allocationPercent",null);return x;}).toList()); }
  @GetMapping("/portfolios/{id}/dashboard") public Object dashboard(Authentication a,@PathVariable long id) { owns(id,uid(a));var h=holdingsData(id);boolean complete=h.stream().allMatch(x->x.get("marketValue")!=null&&!Boolean.TRUE.equals(x.get("priceStale")));BigDecimal basis=h.stream().map(x->(BigDecimal)x.get("costBasis")).reduce(BigDecimal.ZERO,BigDecimal::add);BigDecimal realized=realizedForPortfolio(id);BigDecimal value=h.stream().filter(x->x.get("marketValue")!=null).map(x->(BigDecimal)x.get("marketValue")).reduce(BigDecimal.ZERO,BigDecimal::add);var recent=db.queryForList("select t.id,t.type,t.quantity,t.execution_price,t.fees,t.occurred_at,a.id asset_id,a.ticker from investment_transaction t join asset a on a.id=t.asset_id where t.portfolio_id=? order by t.occurred_at desc limit 5",id);var watch=db.queryForList("select w.id,a.id asset_id,a.ticker,a.name,a.currency from watchlist_item w join asset a on a.id=w.asset_id where w.user_id=(select owner_id from portfolio where id=?) order by w.created_at desc limit 5",id);var m=new HashMap<String,Object>();m.put("marketValue",complete?value:null);m.put("marketValueComplete",complete);m.put("costBasis",basis);m.put("unrealizedPnl",complete?value.subtract(basis):null);m.put("realizedPnl",realized);m.put("overallPnl",complete?value.subtract(basis).add(realized):null);m.put("holdings",h);m.put("recentTransactions",recent);m.put("watchlist",watch);return m; }
  private BigDecimal realizedForPortfolio(long portfolio) {
    var rows=db.queryForList("select asset_id,type,quantity,execution_price,fees from investment_transaction where portfolio_id=? order by occurred_at,id",portfolio);var states=new HashMap<Long,WeightedAverageCost.Result>();
    for(var r:rows){long asset=((Number)r.get("asset_id")).longValue();var previous=states.getOrDefault(asset,WeightedAverageCost.empty());states.put(asset,WeightedAverageCost.apply(previous,(String)r.get("type"),(BigDecimal)r.get("quantity"),(BigDecimal)r.get("execution_price"),(BigDecimal)r.get("fees")));}
    return states.values().stream().map(WeightedAverageCost.Result::realizedPnl).reduce(BigDecimal.ZERO,BigDecimal::add);
  }
  @GetMapping("/watchlist") public Object watchlist(Authentication a) { return db.queryForList("select w.id,a.id asset_id,a.ticker,a.name,a.exchange,a.currency,w.created_at from watchlist_item w join asset a on a.id=w.asset_id where w.user_id=? order by w.created_at desc",uid(a)); }
  @PostMapping("/watchlist") public ResponseEntity<?> addWatch(Authentication a,@Valid @RequestBody WatchInput in) { upsertAsset(in.assetId()); long userId=uid(a); int exists=db.queryForObject("select count(*) from watchlist_item where user_id=? and asset_id=?",Integer.class,userId,in.assetId());if(exists==0)try{db.update("insert into watchlist_item(user_id,asset_id) values(?,?)",userId,in.assetId());}catch(DuplicateKeyException ignored){/* Concurrent duplicate add is idempotent; unique key remains authoritative. */}return ResponseEntity.status(201).body(watchlist(a)); }
  @DeleteMapping("/watchlist/{id}") public ResponseEntity<?> removeWatch(Authentication a,@PathVariable long id) { int n=db.update("delete from watchlist_item where id=? and user_id=?",id,uid(a));if(n==0)throw new NoSuchElementException("Watchlist item not found");return ResponseEntity.noContent().build(); }
}
