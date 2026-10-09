# Gabnex Project Status

**Assessed:** 2026-10-10  
**Scope:** Read the full original specification in `hello_world.txt`; inspected the current source tree and ran the available test/build commands. `hello_world.txt` was left unchanged.

## Verified in this workspace

- Backend: `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ./backend/mvnw -B -f backend/pom.xml verify` completed successfully. Surefire reports 33 tests: 24 API integration, 5 market-data service, and 4 weighted-average-cost tests; 0 failures/errors/skips.
- Frontend: `cd frontend && npm test` passed 4 tests. `npm run build` succeeded with Vite 6.4.3. Vite reported the generated JS chunk is over 500 kB; this is a bundle-size warning, not a build failure.
- The API integration tests use H2 in PostgreSQL mode and make no Alpha Vantage calls. The test profile has no market-data key. Mockito is configured to use the subclass mock maker because inline attach is not available in this local macOS/JDK setup.
- A frontend `package-lock.json` exists. CI and the frontend Docker build now use `npm ci`. CI backend invocation now uses the checked-in Maven wrapper.
- `git`/GitHub Actions was not run from this workspace. Docker and Docker Compose are not installed (`docker: command not found`), so images, Compose startup, Postgres migrations, and the production profile have not been runtime-verified.

## What is implemented

- Spring Boot 3.3.5 / Java 21 backend with PostgreSQL JDBC persistence, Flyway schema migration, BCrypt authentication, JWT bearer auth, owner-scoped resources, exception handling, OpenAPI, and Actuator health endpoint.
- Portfolio CRUD, transaction CRUD/filtering/sorting/pagination, holdings derived from transaction history, dashboard/allocation, asset search/details/prices, watchlists, and market-data status endpoints.
- Immutable `BigDecimal` weighted-average-cost calculations. Fees affect cost/proceeds; sells are checked against historical holdings; realized P&L and derived holdings are covered by unit and API integration tests.
- Alpha Vantage provider integration using symbol search and global quote. Responses are validated and saved with source, retrieval time, and an end-of-day classification. Missing credentials/provider failures do not create fake prices; unavailable/stale responses are explicit. The actual provider is not reachable/verified here because no personal key was configured and no live call was made.
- React 18/Vite frontend with auth, dashboard, portfolios, holdings, transaction history and forms, asset search/watchlist, settings, responsive styling, and quote freshness/unavailable labels.
- Dockerfiles, Compose definition, GitHub Actions workflow, `.env.example`, README, and MIT license are present. Their runtime/deployment behavior is not verified unless called out above.

## Fixes made in this continuation

- Fixed API integration test startup on this machine by selecting Mockito's subclass mock maker; removed a misleading test that stubbed a provider while the test key was deliberately absent.
- Fixed H2/PostgreSQL test portability in watchlist insertion: removed PostgreSQL-only `ON CONFLICT` syntax so the integration test can exercise the endpoint with H2. Duplicate additions remain idempotent in the sequential API test.
- Adjusted JSON numeric assertions to match Jackson's decimal values.
- Updated CI to use the Maven wrapper and `npm ci`; updated the frontend Dockerfile to install from the lockfile.
- Corrected README statements about the lockfile and the scope/database used by integration tests.

## Known gaps and next steps

1. **Run a PostgreSQL-backed deployment check.** Install Docker Desktop/Compose (or provide a reachable PostgreSQL instance), then run `cp .env.example .env`, set a strong `JWT_SECRET` and local DB credentials, and run `docker compose up --build`. Confirm Flyway V1 applies and exercise registration, transaction CRUD, and health/API endpoints. This is the largest remaining verification gap.
2. **Configure a genuine Alpha Vantage key for manual provider verification.** Obtain it from the provider's own signup/account flow, place it only in local `.env` as `MARKET_DATA_API_KEY`, restart the backend, and check `/api/v1/market-data/status`, asset search, and quote refresh. The project deliberately does not contain a key. No live quote is asserted by current tests.
3. Test the GitHub Actions workflow on a push/PR. This workspace has not run hosted CI.
4. Improve integration test coverage: asset search/refresh provider HTTP parsing using a local stub server, portfolio update/delete and transaction edit, and concurrency for duplicate watchlist adds. The current test profile uses H2; it cannot prove PostgreSQL-specific migration/runtime behavior.
5. Consider splitting the large frontend entry file and code-splitting Recharts. Current production build succeeds but emits a >500 kB chunk warning.
6. Deployment is not done. No secrets, provider key, or public endpoint have been provisioned.

## Environment facts

- Java 21.0.2 and Node 20.19.6/npm 10.8.2 are available.
- Maven is available through `backend/mvnw`; use `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home` in this environment.
- Docker is unavailable on PATH. No actual PostgreSQL server or live Alpha Vantage request was used for verification.
- The package's default quote mode is end-of-day; the frontend must not present these as live prices. No synthetic production quote/history data should be introduced.

## Re-run checks

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ./backend/mvnw -B -f backend/pom.xml verify
cd frontend && npm ci && npm test && npm run build
```
