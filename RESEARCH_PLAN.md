# Proposed evaluation for a sole-authored technical paper

## Research question

Can a transactional, idempotent backend preserve wallet balance and ledger consistency when SMS-style requests are duplicated, delayed, reordered, or lost, while retaining a low-data handset interaction?

## Study boundaries

Compare the *documented 2021 implementation* with the *2026 redesign* only where evidence is available. Do not attribute new code, tests, or measurements to 2021. Use invented accounts and simulated messages; do not use real funds or personal data. Any statement about deployment or security should be qualified as prototype evidence.

## Experiments to run next

1. **Functional and property tests:** enrollment uniqueness, PIN lockout, expiry, cancellation, insufficient funds, all amount boundaries, and ledger conservation. For every settled payment, assert exactly one debit and credit whose sum is zero.
2. **Concurrency and fault injection on PostgreSQL:** send duplicate `PAY` messages with the same ID, repeated `CONFIRM` messages with different IDs, and simultaneous payments against one wallet. Inject failure after debit and verify rollback. Repeat with 10, 100, and 1,000 concurrent requests where hardware permits.
3. **Delivery simulation:** vary duplicate, delay, reordering, and loss rates across a documented seeded workload. Measure completion rate, duplicate-settlement rate, expiry rate, and recovery behavior.
4. **Performance:** record p50/p95/p99 latency, throughput, error rate, database CPU, and lock contention under a stated machine, JDK, PostgreSQL version, dataset size, and workload. Run multiple trials and report confidence intervals or distributions.
5. **Security assessment:** document trust boundaries, abuse cases, HMAC replay attempts, PIN guessing, logs, authorization boundaries, and residual SMS interception/SIM-swap risk. Run dependency and static scans, then manually verify findings.

## Reproducibility package

Archive the exact Git commit, dependency versions, migration, test scripts, sanitized datasets, raw result tables, machine details, and analysis code. Preserve negative findings and limitations. Obtain written co-author/release agreement for any material derived from the jointly submitted 2021 report and disclose the original joint submission accurately.

## Publication threshold

The current repository is an implemented research prototype with baseline checks, not yet a publication-ready empirical study. A manuscript should be written after the above tests produce verifiable data and after the author reviews all claims. Zenodo can host a technical report; peer review requires submission to an appropriate venue and acceptance.
