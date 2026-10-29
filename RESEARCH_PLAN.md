# Evaluation record and remaining research

## Research question

Can a transactional, idempotent backend preserve wallet balance and ledger consistency when SMS-style requests are duplicated, delayed, reordered, or lost, while retaining a low-data handset interaction?

## Study boundaries

Compare the *documented 2021 implementation* with the *2026 redesign* only where evidence is available. Do not attribute new code, tests, or measurements to 2021. Use invented accounts and simulated messages; do not use real funds or personal data. Any statement about deployment or security should be qualified as prototype evidence.

## Completed synthetic pilot on PostgreSQL

On 29 October 2026, the redesign was exercised against PostgreSQL 18.4 with synthetic accounts and balances. The committed reproducibility package includes three trials with 10 requests per concurrent scenario and three with 30 requests per concurrent scenario. It records 120 baseline payments, 120 duplicate `PAY` deliveries grouped under six IDs, 120 competing `CONFIRM` deliveries for six intents, six forced expiries, and six injected credit-ledger failures. Every programmed balance and ledger invariant passed. These are local prototype observations, not claims about carrier delivery, production throughput, or general reliability. See `experiments/aggregate.json` and the raw CSV files.

## Experiments for a larger follow-up study

1. **Broader property tests:** all amount boundaries, account recovery, multiple simultaneous payments against one wallet, and ledger conservation under more adversarial schedules.
2. **Larger concurrency:** repeat at 100 and 1,000 concurrent requests on a stated machine and database configuration. Measure connection-pool saturation and lock contention.
3. **Delivery simulation:** vary duplicate, delay, reordering, and loss rates across a documented seeded workload. Measure completion rate, duplicate-settlement rate, expiry rate, and recovery behavior.
4. **Performance:** record p50/p95/p99 latency, throughput, error rate, database CPU, and lock contention under a stated machine, JDK, PostgreSQL version, dataset size, and workload. Run multiple trials and report confidence intervals or distributions.
5. **Security assessment:** document trust boundaries, abuse cases, HMAC replay attempts, PIN guessing, logs, authorization boundaries, and residual SMS interception/SIM-swap risk. Run dependency and static scans, then manually verify findings.

## Reproducibility package

Archive the exact Git commit, dependency versions, migration, test scripts, sanitized datasets, raw result tables, machine details, and analysis code. Preserve negative findings and limitations. Obtain written co-author/release agreement for any material derived from the jointly submitted 2021 report and disclose the original joint submission accurately.

## Publication threshold

The current repository supports a bounded, reproducible technical report about the stated local correctness scenarios. It does not yet support a general claim about real SMS networks, production security, or large-scale performance. A broader empirical journal or conference paper would require the follow-up studies and independent review. Zenodo can host a technical report; peer review requires submission to an appropriate venue and acceptance.
