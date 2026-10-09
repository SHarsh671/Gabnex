package dev.gabnex.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests using an in-memory H2 database (PostgreSQL mode) and MockMvc.
 * The test profile has no Alpha Vantage API key, so the provider returns unavailable
 * before making any HTTP request. Provider behavior is covered by MarketDataServiceTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiIntegrationTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  @Autowired ObjectMapper json;

  @BeforeEach
  void cleanDb() {
    // Delete in dependency order so FK constraints are satisfied.
    db.update("DELETE FROM market_price_snapshot");
    db.update("DELETE FROM watchlist_item");
    db.update("DELETE FROM investment_transaction");
    db.update("DELETE FROM portfolio");
    db.update("DELETE FROM asset");
    db.update("DELETE FROM app_user");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Helpers
  // ─────────────────────────────────────────────────────────────────────────────

  private String body(Object o) throws Exception { return json.writeValueAsString(o); }

  /** Registers a user and returns the JWT token. */
  private String registerAndLogin(String name, String email, String password) throws Exception {
    mvc.perform(post("/api/v1/auth/register")
        .contentType(MediaType.APPLICATION_JSON)
        .content(body(Map.of("name", name, "email", email, "password", password))))
       .andExpect(status().isCreated());
    var result = mvc.perform(post("/api/v1/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content(body(Map.of("email", email, "password", password))))
       .andExpect(status().isOk())
       .andReturn();
    var tree = json.readTree(result.getResponse().getContentAsString());
    return tree.get("token").asText();
  }

  private String auth(String token) { return "Bearer " + token; }

  /** Creates a portfolio and returns its numeric ID. */
  private long createPortfolio(String token, String name) throws Exception {
    var result = mvc.perform(post("/api/v1/portfolios")
        .header("Authorization", auth(token))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body(Map.of("name", name, "baseCurrency", "USD"))))
       .andExpect(status().isCreated())
       .andReturn();
    return json.readTree(result.getResponse().getContentAsString()).get("id").asLong();
  }

  /** Inserts an asset directly into the DB and returns its ID. */
  private long insertAsset(String symbol, String name) {
    db.update("INSERT INTO asset(provider_symbol,ticker,name,exchange,currency,asset_type,provider) " +
              "VALUES(?,?,?,?,?,?,?)",
        symbol, symbol, name, "NASDAQ", "USD", "Equity", "ALPHA_VANTAGE");
    return db.queryForObject("SELECT id FROM asset WHERE provider_symbol=?", Long.class, symbol);
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Authentication tests
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void registration_withValidData_returns201AndToken() throws Exception {
    mvc.perform(post("/api/v1/auth/register")
        .contentType(MediaType.APPLICATION_JSON)
        .content(body(Map.of("name","Alice","email","alice@example.com","password","password1234"))))
       .andExpect(status().isCreated())
       .andExpect(jsonPath("$.token").isString())
       .andExpect(jsonPath("$.user.email").value("alice@example.com"))
       .andExpect(jsonPath("$.user.password_hash").doesNotExist());
  }

  @Test
  void registration_withDuplicateEmail_returns409() throws Exception {
    mvc.perform(post("/api/v1/auth/register")
        .contentType(MediaType.APPLICATION_JSON)
        .content(body(Map.of("name","A","email","dup@example.com","password","password1234"))))
       .andExpect(status().isCreated());
    mvc.perform(post("/api/v1/auth/register")
        .contentType(MediaType.APPLICATION_JSON)
        .content(body(Map.of("name","B","email","dup@example.com","password","password1234"))))
       .andExpect(status().isConflict())
       .andExpect(jsonPath("$.error").isString());
  }

  @Test
  void registration_withShortPassword_returns400() throws Exception {
    mvc.perform(post("/api/v1/auth/register")
        .contentType(MediaType.APPLICATION_JSON)
        .content(body(Map.of("name","A","email","short@example.com","password","short"))))
       .andExpect(status().isBadRequest())
       .andExpect(jsonPath("$.error").isString());
  }

  @Test
  void login_withWrongPassword_returns401() throws Exception {
    mvc.perform(post("/api/v1/auth/register")
        .contentType(MediaType.APPLICATION_JSON)
        .content(body(Map.of("name","Bob","email","bob@example.com","password","correctpassword"))))
       .andExpect(status().isCreated());
    mvc.perform(post("/api/v1/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content(body(Map.of("email","bob@example.com","password","wrongpassword"))))
       .andExpect(status().isUnauthorized())
       .andExpect(jsonPath("$.error").isString());
  }

  @Test
  void me_withValidToken_returnsUserProfile() throws Exception {
    String token = registerAndLogin("Carol", "carol@example.com", "mypassword123");
    mvc.perform(get("/api/v1/auth/me").header("Authorization", auth(token)))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.email").value("carol@example.com"))
       .andExpect(jsonPath("$.password_hash").doesNotExist());
  }

  @Test
  void me_withoutToken_returns401() throws Exception {
    mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Portfolio tests
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void createPortfolio_andListIt() throws Exception {
    String token = registerAndLogin("Dave", "dave@example.com", "davepassword1");
    mvc.perform(post("/api/v1/portfolios")
        .header("Authorization", auth(token))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body(Map.of("name","My ISA","baseCurrency","USD"))))
       .andExpect(status().isCreated())
       .andExpect(jsonPath("$.name").value("My ISA"))
       .andExpect(jsonPath("$.base_currency").value("USD"));
    mvc.perform(get("/api/v1/portfolios").header("Authorization", auth(token)))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$", hasSize(1)))
       .andExpect(jsonPath("$[0].name").value("My ISA"));
  }

  @Test
  void portfolioOwnership_userCannotSeeAnotherUsersPortfolio() throws Exception {
    String tokenA = registerAndLogin("Eve", "eve@example.com", "evepassword1");
    String tokenB = registerAndLogin("Frank", "frank@example.com", "frankpassword1");
    long portfolioId = createPortfolio(tokenA, "Eve's portfolio");

    mvc.perform(get("/api/v1/portfolios/" + portfolioId)
        .header("Authorization", auth(tokenB)))
       .andExpect(status().isNotFound());
  }

  @Test
  void portfolioOwnership_userCannotDeleteAnotherUsersPortfolio() throws Exception {
    String tokenA = registerAndLogin("Grace", "grace@example.com", "gracepassword1");
    String tokenB = registerAndLogin("Hank", "hank@example.com", "hankpassword123");
    long portfolioId = createPortfolio(tokenA, "Grace's portfolio");

    mvc.perform(delete("/api/v1/portfolios/" + portfolioId)
        .header("Authorization", auth(tokenB)))
       .andExpect(status().isNotFound());
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Transaction and holdings tests
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void addBuyTransaction_holdingAppearsWithCorrectQuantityAndCost() throws Exception {
    String token = registerAndLogin("Ivy", "ivy@example.com", "ivypassword123");
    long pid = createPortfolio(token, "Ivy's portfolio");
    long assetId = insertAsset("AAPL", "Apple Inc.");

    // Record a BUY: 10 shares at $150, $2 fee → cost basis = $1502
    mvc.perform(post("/api/v1/portfolios/" + pid + "/transactions")
        .header("Authorization", auth(token))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body(Map.of(
            "assetId", assetId,
            "type", "BUY",
            "quantity", "10",
            "executionPrice", "150",
            "fees", "2",
            "occurredAt", "2026-01-01T10:00:00Z"))))
       .andExpect(status().isCreated());

    mvc.perform(get("/api/v1/portfolios/" + pid + "/holdings")
        .header("Authorization", auth(token)))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$", hasSize(1)))
       .andExpect(jsonPath("$[0].ticker").value("AAPL"))
       .andExpect(jsonPath("$[0].quantity").value(10))
       // costBasis = 10 * 150 + 2 = 1502
       .andExpect(jsonPath("$[0].costBasis").value(1502.0))
       // averageCost = 1502 / 10 = 150.2
       .andExpect(jsonPath("$[0].averageCost").value(150.2))
       // No market price stored yet
       .andExpect(jsonPath("$[0].marketPrice").doesNotExist());
  }

  @Test
  void sellMoreThanHeld_returns400() throws Exception {
    String token = registerAndLogin("Jack", "jack@example.com", "jackpassword1");
    long pid = createPortfolio(token, "Jack's portfolio");
    long assetId = insertAsset("MSFT", "Microsoft Corp.");

    // Buy 5 shares
    mvc.perform(post("/api/v1/portfolios/" + pid + "/transactions")
        .header("Authorization", auth(token))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body(Map.of(
            "assetId", assetId, "type", "BUY",
            "quantity", "5", "executionPrice", "200",
            "fees", "0", "occurredAt", "2026-01-01T10:00:00Z"))))
       .andExpect(status().isCreated());

    // Attempt to sell 10 shares (more than held)
    mvc.perform(post("/api/v1/portfolios/" + pid + "/transactions")
        .header("Authorization", auth(token))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body(Map.of(
            "assetId", assetId, "type", "SELL",
            "quantity", "10", "executionPrice", "210",
            "fees", "0", "occurredAt", "2026-01-02T10:00:00Z"))))
       .andExpect(status().isBadRequest())
       .andExpect(jsonPath("$.error").isString());
  }

  @Test
  void buyAndPartialSell_realizedPnlCalculatedCorrectly() throws Exception {
    String token = registerAndLogin("Kate", "kate@example.com", "katepassword1");
    long pid = createPortfolio(token, "Kate's portfolio");
    long assetId = insertAsset("GOOG", "Alphabet Inc.");

    // Buy 10 @ 100, fee 2 → basis = 1002
    mvc.perform(post("/api/v1/portfolios/" + pid + "/transactions")
        .header("Authorization", auth(token))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body(Map.of(
            "assetId", assetId, "type", "BUY",
            "quantity", "10", "executionPrice", "100",
            "fees", "2", "occurredAt", "2026-01-01T10:00:00Z"))))
       .andExpect(status().isCreated());

    // Sell 5 @ 120, fee 1 → proceeds = 599, soldCost = 1002/10*5 = 501 → realized = 98
    mvc.perform(post("/api/v1/portfolios/" + pid + "/transactions")
        .header("Authorization", auth(token))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body(Map.of(
            "assetId", assetId, "type", "SELL",
            "quantity", "5", "executionPrice", "120",
            "fees", "1", "occurredAt", "2026-01-10T10:00:00Z"))))
       .andExpect(status().isCreated());

    mvc.perform(get("/api/v1/portfolios/" + pid + "/holdings")
        .header("Authorization", auth(token)))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$[0].quantity").value(5))
       .andExpect(jsonPath("$[0].realizedPnl").value(98.0));
  }

  @Test
  void deleteTransaction_holdingRecalculates() throws Exception {
    String token = registerAndLogin("Leo", "leo@example.com", "leopassword123");
    long pid = createPortfolio(token, "Leo's portfolio");
    long assetId = insertAsset("AMZN", "Amazon.com Inc.");

    // Buy 10
    mvc.perform(post("/api/v1/portfolios/" + pid + "/transactions")
        .header("Authorization", auth(token))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body(Map.of(
            "assetId", assetId, "type", "BUY",
            "quantity", "10", "executionPrice", "100",
            "fees", "0", "occurredAt", "2026-01-01T10:00:00Z"))))
       .andExpect(status().isCreated());

    // Buy another 5
    var r2 = mvc.perform(post("/api/v1/portfolios/" + pid + "/transactions")
        .header("Authorization", auth(token))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body(Map.of(
            "assetId", assetId, "type", "BUY",
            "quantity", "5", "executionPrice", "110",
            "fees", "0", "occurredAt", "2026-01-05T10:00:00Z"))))
       .andExpect(status().isCreated())
       .andReturn();
    long tx2Id = json.readTree(r2.getResponse().getContentAsString()).get("id").asLong();

    // Delete the second buy — should leave only 10 shares
    mvc.perform(delete("/api/v1/transactions/" + tx2Id)
        .header("Authorization", auth(token)))
       .andExpect(status().isNoContent());

    mvc.perform(get("/api/v1/portfolios/" + pid + "/holdings")
        .header("Authorization", auth(token)))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$[0].quantity").value(10));
  }

  @Test
  void transactionOwnership_userCannotDeleteAnotherUsersTransaction() throws Exception {
    String tokenA = registerAndLogin("Mia", "mia@example.com", "miapassword123");
    String tokenB = registerAndLogin("Ned", "ned@example.com", "nedpassword123");
    long pidA = createPortfolio(tokenA, "Mia's portfolio");
    long assetId = insertAsset("META", "Meta Platforms Inc.");

    var r = mvc.perform(post("/api/v1/portfolios/" + pidA + "/transactions")
        .header("Authorization", auth(tokenA))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body(Map.of(
            "assetId", assetId, "type", "BUY",
            "quantity", "1", "executionPrice", "300",
            "fees", "0", "occurredAt", "2026-01-01T10:00:00Z"))))
       .andExpect(status().isCreated())
       .andReturn();
    long txId = json.readTree(r.getResponse().getContentAsString()).get("id").asLong();

    mvc.perform(delete("/api/v1/transactions/" + txId)
        .header("Authorization", auth(tokenB)))
       .andExpect(status().isNotFound());
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Transaction filtering and pagination
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void transactionFilter_byType_returnsOnlyMatchingRows() throws Exception {
    String token = registerAndLogin("Ona", "ona@example.com", "onapassword123");
    long pid = createPortfolio(token, "Ona's portfolio");
    long assetId = insertAsset("TSLA", "Tesla Inc.");

    // Buy then Sell
    mvc.perform(post("/api/v1/portfolios/" + pid + "/transactions")
        .header("Authorization", auth(token))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body(Map.of("assetId", assetId, "type", "BUY",
            "quantity", "5", "executionPrice", "200",
            "fees", "0", "occurredAt", "2026-01-01T10:00:00Z"))))
       .andExpect(status().isCreated());
    mvc.perform(post("/api/v1/portfolios/" + pid + "/transactions")
        .header("Authorization", auth(token))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body(Map.of("assetId", assetId, "type", "SELL",
            "quantity", "2", "executionPrice", "250",
            "fees", "0", "occurredAt", "2026-02-01T10:00:00Z"))))
       .andExpect(status().isCreated());

    mvc.perform(get("/api/v1/portfolios/" + pid + "/transactions?type=BUY")
        .header("Authorization", auth(token)))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$", hasSize(1)))
       .andExpect(jsonPath("$[0].type").value("BUY"));

    mvc.perform(get("/api/v1/portfolios/" + pid + "/transactions?type=SELL")
        .header("Authorization", auth(token)))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$", hasSize(1)))
       .andExpect(jsonPath("$[0].type").value("SELL"));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Watchlist tests
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void watchlist_addAndRemove() throws Exception {
    String token = registerAndLogin("Pat", "pat@example.com", "patpassword123");
    long assetId = insertAsset("NVDA", "NVIDIA Corp.");

    // Add to watchlist
    mvc.perform(post("/api/v1/watchlist")
        .header("Authorization", auth(token))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body(Map.of("assetId", assetId))))
       .andExpect(status().isCreated());

    // Confirm it appears
    mvc.perform(get("/api/v1/watchlist").header("Authorization", auth(token)))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$", hasSize(1)))
       .andExpect(jsonPath("$[0].ticker").value("NVDA"));

    // Get the watchlist item ID
    var wl = mvc.perform(get("/api/v1/watchlist").header("Authorization", auth(token)))
                .andReturn();
    long wlId = json.readTree(wl.getResponse().getContentAsString()).get(0).get("id").asLong();

    // Remove it
    mvc.perform(delete("/api/v1/watchlist/" + wlId)
        .header("Authorization", auth(token)))
       .andExpect(status().isNoContent());

    mvc.perform(get("/api/v1/watchlist").header("Authorization", auth(token)))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$", hasSize(0)));
  }

  @Test
  void watchlist_duplicateEntryIsIdempotent() throws Exception {
    String token = registerAndLogin("Quinn", "quinn@example.com", "quinnpassword1");
    long assetId = insertAsset("AMD", "Advanced Micro Devices.");

    mvc.perform(post("/api/v1/watchlist")
        .header("Authorization", auth(token))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body(Map.of("assetId", assetId))))
       .andExpect(status().isCreated());

    // Second add should not duplicate
    mvc.perform(post("/api/v1/watchlist")
        .header("Authorization", auth(token))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body(Map.of("assetId", assetId))))
       .andExpect(status().isCreated());

    mvc.perform(get("/api/v1/watchlist").header("Authorization", auth(token)))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$", hasSize(1)));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Market data endpoint tests
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void priceEndpoint_withNoApiKey_returnsUnavailableNotFakePrice() throws Exception {
    String token = registerAndLogin("Rex", "rex@example.com", "rexpassword123");
    long assetId = insertAsset("SPY", "SPDR S&P 500 ETF");

    // The test config has market-data.api-key: '' so provider is not configured.
    var result = mvc.perform(get("/api/v1/assets/" + assetId + "/price")
        .header("Authorization", auth(token)))
       .andExpect(status().isOk())
       // Must explicitly say unavailable — not a fake price
       .andExpect(jsonPath("$.available").value(false))
       .andExpect(jsonPath("$.price").doesNotExist())
       .andReturn();
  }

  @Test
  void marketDataStatus_returnsProviderInfo() throws Exception {
    String token = registerAndLogin("Sam", "sam@example.com", "sampassword123");
    mvc.perform(get("/api/v1/market-data/status").header("Authorization", auth(token)))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.provider").value("Alpha Vantage"))
       .andExpect(jsonPath("$.configured").value(false)); // no key in test config
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Dashboard tests
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void dashboard_emptyPortfolio_returnsZeroMetrics() throws Exception {
    String token = registerAndLogin("Uma", "uma@example.com", "umapassword123");
    long pid = createPortfolio(token, "Uma's empty portfolio");

    mvc.perform(get("/api/v1/portfolios/" + pid + "/dashboard")
        .header("Authorization", auth(token)))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.costBasis").value(0))
       .andExpect(jsonPath("$.realizedPnl").value(0))
       .andExpect(jsonPath("$.holdings").isArray())
       .andExpect(jsonPath("$.holdings", hasSize(0)));
  }

  @Test
  void allocation_withNoPrices_returnsIncompleteFlag() throws Exception {
    String token = registerAndLogin("Val", "val@example.com", "valpassword123");
    long pid = createPortfolio(token, "Val's portfolio");
    long assetId = insertAsset("GE", "General Electric");

    mvc.perform(post("/api/v1/portfolios/" + pid + "/transactions")
        .header("Authorization", auth(token))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body(Map.of("assetId", assetId, "type", "BUY",
            "quantity", "10", "executionPrice", "80",
            "fees", "1", "occurredAt", "2026-01-01T10:00:00Z"))))
       .andExpect(status().isCreated());

    mvc.perform(get("/api/v1/portfolios/" + pid + "/allocation")
        .header("Authorization", auth(token)))
       .andExpect(status().isOk())
       // Since no price snapshot exists, allocation cannot be complete
       .andExpect(jsonPath("$.complete").value(false));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Request validation tests
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void addTransaction_withFuturDate_returns400() throws Exception {
    String token = registerAndLogin("Wes", "wes@example.com", "wespassword123");
    long pid = createPortfolio(token, "Wes's portfolio");
    long assetId = insertAsset("ORCL", "Oracle Corp.");

    mvc.perform(post("/api/v1/portfolios/" + pid + "/transactions")
        .header("Authorization", auth(token))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body(Map.of(
            "assetId", assetId, "type", "BUY",
            "quantity", "1", "executionPrice", "100",
            "fees", "0", "occurredAt", "2099-01-01T00:00:00Z"))))
       .andExpect(status().isBadRequest())
       .andExpect(jsonPath("$.error").isString());
  }

  @Test
  void addTransaction_withNegativeQuantity_returns400() throws Exception {
    String token = registerAndLogin("Xia", "xia@example.com", "xiapassword123");
    long pid = createPortfolio(token, "Xia's portfolio");
    long assetId = insertAsset("CRM", "Salesforce Inc.");

    mvc.perform(post("/api/v1/portfolios/" + pid + "/transactions")
        .header("Authorization", auth(token))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body(Map.of(
            "assetId", assetId, "type", "BUY",
            "quantity", "-5", "executionPrice", "100",
            "fees", "0", "occurredAt", "2026-01-01T10:00:00Z"))))
       .andExpect(status().isBadRequest())
       .andExpect(jsonPath("$.error").isString());
  }

  @Test
  void actuatorHealth_isPubliclyAccessible() throws Exception {
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }
}

