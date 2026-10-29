# SmsTransactionLab: SMS wallet research prototype

This is a new Spring Boot implementation of the central idea in Samuel Chukwuebuka Akabuike's 2021 FUTO project, *Development of an Improved Internet Independent SMS-Based Payment Platform*. It is **not** the 2021 code ported unchanged, a real-money payment service, a deployed SMS gateway, or a peer-reviewed result. The [2021 project selections and 2026 technical note](https://doi.org/10.5281/zenodo.23766609) are available on Zenodo. The original NetBeans/JAX-RS code is archived separately at [Akasam79/KeenPay](https://github.com/Akasam79/KeenPay).

The handset can issue SMS-style commands without mobile data. The gateway and backend still require connectivity. This repository provides a signed HTTP gateway simulator so the payment state machine can be tested reproducibly before any real SMS provider is integrated.

## Implemented flows

- Operator-provisioned customer and merchant accounts, with a six-digit PIN hashed using bcrypt. Merchants receive a deterministic short code.
- Simulated wallet funding, capped at NGN 1,000,000 per operation. No real deposits or withdrawals.
- `PAY <merchant-code> <whole-naira-amount>` creates a pending payment with a ten-minute expiry.
- `CONFIRM <payment-UUID> <PIN>` transfers funds atomically and writes balanced debit/credit ledger entries.
- `BALANCE <PIN>`, `RESET <old-PIN> <new-PIN>`, and `CANCEL <payment-UUID>`.
- Gateway HMAC verification, five-minute timestamp window, and message-ID idempotency for duplicate delivery.
- PIN retry lockout after five failures for fifteen minutes, input validation, integer minor units, and database constraints.

**Security boundary:** SMS is not an encrypted channel, and this prototype still uses PINs in SMS commands to preserve the historical interaction. It must only be used with invented accounts and simulated balances. The gateway secret authenticates the HTTP simulator, not the handset or the SMS network. The operator endpoints are for controlled experiments and must never be publicly exposed. See [SECURITY.md](SECURITY.md).

## Open in IntelliJ IDEA

Open this folder as a Maven project. Use JDK 21 or newer. Spring Boot version is pinned in `pom.xml`. Configure these environment variables in the run configuration:

```text
SMSLAB_GATEWAY_SECRET=<a long random test secret>
SMSLAB_OPERATOR_KEY=<a separate long random test key>
```

The default H2 database is in memory and clears at restart. Run `SmsTransactionLabApplication` in IntelliJ, or run `./mvnw spring-boot:run`. Visit `http://localhost:8080/actuator/health` for a health check. No secrets are committed to the project.

For a persistent PostgreSQL experiment, set `SMSLAB_DB_URL`, `SMSLAB_DB_USER`, and `SMSLAB_DB_PASSWORD`. Flyway creates the schema. Treat PostgreSQL as a research database, not a production payment ledger.

## HTTP contract

Operator requests require `X-SmsLab-Operator-Key`:

```json
POST /api/v1/simulation/accounts
{"phone":"+2348012345678","role":"CUSTOMER","name":"Ada Example","pin":"123456"}
```

Use `role: "MERCHANT"` for a merchant; the response includes its short code. Fund a simulated account with:

```json
POST /api/v1/simulation/fund
{"phone":"+2348012345678","amountNaira":100}
```

Gateway delivery is `POST /api/v1/sms/inbound` with raw JSON body:

```json
{"phone":"+2348012345678","sms":"PAY M0000002 40"}
```

Set headers `X-SmsLab-Timestamp` to current UTC ISO-8601 time, `X-SmsLab-Message-Id` to a unique ID, and `X-SmsLab-Signature` to lowercase hex HMAC-SHA256 of `timestamp + "." + messageId + "." + rawBody`, using `SMSLAB_GATEWAY_SECRET`. A repeated message ID with identical content returns the original reply without executing the command again. Reuse with changed content is rejected.

Run `python3 scripts/demo.py` after setting the two environment variables to exercise the complete local flow.

## Reproduce the correctness experiment

Use a **dedicated, disposable PostgreSQL database** and start the application against it on a local port. `scripts/research_experiment.py` creates synthetic accounts, sends baseline and concurrent duplicate requests, forces a payment to expire, and briefly installs a PostgreSQL trigger that rejects a credit-ledger write to test transaction rollback. It removes the trigger after that scenario. Do not run it against any shared or production database.

```bash
createdb smstxnlab_research
export SMSLAB_DB_URL=jdbc:postgresql://127.0.0.1:5432/smstxnlab_research
export SMSLAB_DB_USER="$(whoami)"
export SMSLAB_GATEWAY_SECRET=replace-with-a-local-test-secret
export SMSLAB_OPERATOR_KEY=replace-with-another-local-test-key
./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=18080
```

In another terminal with the same two test secrets:

```bash
python3 scripts/research_experiment.py --trials 3 --requests 10 --output experiments/results_10
python3 scripts/research_experiment.py --trials 3 --requests 30 --output experiments/results_30
```

The script writes request-level CSV and JSON summaries. Its correctness assertions stop the run on a failed invariant. The latency figures describe local HTTP processing on one machine; they do not measure SMS-network delivery or a production deployment. `SMSLAB_RESEARCH_DB` may be set to a different dedicated database name.

## Evidence and provenance

See [PROVENANCE.md](PROVENANCE.md) for the mapping from 2021 functions to this new implementation, and [RESEARCH_PLAN.md](RESEARCH_PLAN.md) for the proposed evaluation. The code and baseline tests are engineering artifacts. Claims about throughput, security improvement, or field reliability require measured experiments and independent review.
