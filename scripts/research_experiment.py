#!/usr/bin/env python3
"""Run a synthetic correctness experiment against a local PostgreSQL-backed app.

Use a dedicated test database. The trigger used for rollback testing is installed
briefly and removed in a finally block. No real accounts or payment data are used.
"""

import argparse
import csv
import hashlib
import hmac
import json
import os
import platform
import re
import subprocess
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen


BASE = os.environ.get("SMSLAB_BASE_URL", "http://127.0.0.1:18080")
OPERATOR = os.environ["SMSLAB_OPERATOR_KEY"]
GATEWAY = os.environ["SMSLAB_GATEWAY_SECRET"]
DATABASE = os.environ.get("SMSLAB_RESEARCH_DB", "smstxnlab_research")
REFERENCE = re.compile(r"[0-9a-f]{8}-[0-9a-f-]{27,}")


def sql(statement):
    result = subprocess.run(["psql", "-X", "-A", "-t", "-d", DATABASE, "-c", statement],
                            capture_output=True, text=True, check=True)
    return result.stdout.strip()


def post(path, body, headers):
    data = json.dumps(body, separators=(",", ":")).encode()
    request = Request(BASE + path, data=data, headers={"Content-Type": "application/json", **headers})
    start = time.perf_counter_ns()
    try:
        with urlopen(request, timeout=30) as response:
            status, payload = response.status, response.read().decode()
    except HTTPError as error:
        status, payload = error.code, error.read().decode()
    duration = (time.perf_counter_ns() - start) / 1_000_000
    try:
        content = json.loads(payload)
    except json.JSONDecodeError:
        content = {"raw": payload[:300]}
    return status, content, round(duration, 3)


def operator(path, body):
    return post(path, body, {"X-SmsLab-Operator-Key": OPERATOR})


def sms(phone, command, message_id=None):
    message_id = message_id or "exp-" + uuid.uuid4().hex
    body = {"phone": phone, "sms": command}
    raw = json.dumps(body, separators=(",", ":")).encode()
    timestamp = datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
    payload = timestamp.encode() + b"." + message_id.encode() + b"." + raw
    signature = hmac.new(GATEWAY.encode(), payload, hashlib.sha256).hexdigest()
    request = Request(BASE + "/api/v1/sms/inbound", data=raw,
                      headers={"Content-Type": "application/json", "X-SmsLab-Timestamp": timestamp,
                               "X-SmsLab-Message-Id": message_id, "X-SmsLab-Signature": signature})
    start = time.perf_counter_ns()
    try:
        with urlopen(request, timeout=30) as response:
            status, payload = response.status, response.read().decode()
    except HTTPError as error:
        status, payload = error.code, error.read().decode()
    duration = (time.perf_counter_ns() - start) / 1_000_000
    try:
        content = json.loads(payload)
    except json.JSONDecodeError:
        content = {"raw": payload[:300]}
    return status, content, round(duration, 3)


def reply(result):
    return result[1].get("reply", "")


def reference(result):
    match = REFERENCE.search(reply(result))
    if result[0] != 200 or not match:
        raise AssertionError(f"No payment reference: {result}")
    return match.group()


def fixture():
    suffix = f"{uuid.uuid4().int % 100_000_000:08d}"
    customer, merchant = "+23490" + suffix, "+23491" + suffix
    for phone, role, name, pin in [(customer, "CUSTOMER", "Synthetic Customer", "123456"),
                                   (merchant, "MERCHANT", "Synthetic Merchant", "654321")]:
        result = operator("/api/v1/simulation/accounts", {"phone": phone, "role": role, "name": name, "pin": pin})
        if result[0] != 201:
            raise AssertionError(f"Registration failed: {result}")
        if role == "MERCHANT":
            code = result[1]["merchantCode"]
    funded = operator("/api/v1/simulation/fund", {"phone": customer, "amountNaira": 100000})
    if funded[0] != 200:
        raise AssertionError(f"Funding failed: {funded}")
    return customer, merchant, code


def balance(phone):
    return int(sql("SELECT w.balance_minor FROM wallets w JOIN accounts a ON a.id=w.account_id "
                   f"WHERE a.phone='{phone}'"))


def ledger(reference_id):
    value = sql(f"SELECT count(*),coalesce(sum(delta_minor),0) FROM ledger_entries WHERE operation_id='{reference_id}'")
    return tuple(map(int, value.split("|")))


def record(rows, trial, scenario, index, result):
    rows.append({"trial": trial, "scenario": scenario, "request": index, "http_status": result[0],
                 "latency_ms": result[2], "reply": reply(result) or result[1].get("error", "")})


def run_trial(trial, count, rows):
    customer, merchant, code = fixture()
    initial_customer, initial_merchant = balance(customer), balance(merchant)
    assert (initial_customer, initial_merchant) == (10_000_000, 0)

    # Baseline: several independent intents, confirmed once each.
    for i in range(count):
        created = sms(customer, f"PAY {code} 1")
        record(rows, trial, "baseline_pay", i, created)
        payment_id = reference(created)
        confirmed = sms(customer, f"CONFIRM {payment_id} 123456")
        record(rows, trial, "baseline_confirm", i, confirmed)
        assert confirmed[0] == 200 and "Payment completed" in reply(confirmed)
        assert ledger(payment_id) == (2, 0)

    # Duplicate gateway delivery: same ID and body arrive simultaneously.
    duplicate_id = "duplicate-" + uuid.uuid4().hex
    command = f"PAY {code} 2"
    with ThreadPoolExecutor(max_workers=count) as pool:
        duplicate_results = list(pool.map(lambda _: sms(customer, command, duplicate_id), range(count)))
    for i, result in enumerate(duplicate_results): record(rows, trial, "duplicate_pay", i, result)
    assert all(result[0] == 200 and reply(result) == reply(duplicate_results[0]) for result in duplicate_results)
    duplicate_ref = reference(duplicate_results[0])
    assert sql(f"SELECT count(*) FROM payment_intents WHERE id='{duplicate_ref}'") == "1"

    # Competing confirmations: different delivery IDs for one payment.
    with ThreadPoolExecutor(max_workers=count) as pool:
        competing = list(pool.map(lambda _: sms(customer, f"CONFIRM {duplicate_ref} 123456"), range(count)))
    for i, result in enumerate(competing): record(rows, trial, "competing_confirm", i, result)
    assert sum("Payment completed" in reply(result) for result in competing) == 1
    assert sum("already completed" in reply(result) for result in competing) == count - 1
    assert ledger(duplicate_ref) == (2, 0)

    # Lost/delayed delivery: do not send a confirmation until the stored expiry has passed.
    delayed_ref = reference(sms(customer, f"PAY {code} 3"))
    assert sql(f"SELECT status FROM payment_intents WHERE id='{delayed_ref}'") == "PENDING"
    sql(f"UPDATE payment_intents SET expires_at=now()-interval '1 second' WHERE id='{delayed_ref}'")
    delayed = sms(customer, f"CONFIRM {delayed_ref} 123456")
    record(rows, trial, "expired_confirm", 0, delayed)
    assert "expired" in reply(delayed).lower()
    assert ledger(delayed_ref) == (0, 0)
    assert sql(f"SELECT status FROM payment_intents WHERE id='{delayed_ref}'") == "EXPIRED"

    # Failure after wallet debit/credit updates: database trigger rejects CREDIT ledger row.
    failure_ref = reference(sms(customer, f"PAY {code} 4"))
    before = (balance(customer), balance(merchant))
    sql("CREATE OR REPLACE FUNCTION research_fail_credit() RETURNS trigger LANGUAGE plpgsql "
        "AS $$ BEGIN IF NEW.entry_type='CREDIT' THEN RAISE EXCEPTION 'injected credit failure'; "
        "END IF; RETURN NEW; END $$")
    sql("CREATE TRIGGER research_fail_credit_trigger BEFORE INSERT ON ledger_entries "
        "FOR EACH ROW EXECUTE FUNCTION research_fail_credit()")
    try:
        failed = sms(customer, f"CONFIRM {failure_ref} 123456")
        record(rows, trial, "injected_failure", 0, failed)
        assert failed[0] == 500
        assert (balance(customer), balance(merchant)) == before
        assert ledger(failure_ref) == (0, 0)
        assert sql(f"SELECT status FROM payment_intents WHERE id='{failure_ref}'") == "PENDING"
    finally:
        sql("DROP TRIGGER IF EXISTS research_fail_credit_trigger ON ledger_entries")
        sql("DROP FUNCTION IF EXISTS research_fail_credit()")

    assert balance(customer) == initial_customer - (count + 2) * 100
    assert balance(merchant) == initial_merchant + (count + 2) * 100
    return {"trial": trial, "baseline_settled": count, "duplicate_delivery_count": count,
            "competing_confirmation_count": count, "duplicate_settlements": 0,
            "expired_without_settlement": True, "rollback_preserved_balances": True,
            "final_customer_minor": balance(customer), "final_merchant_minor": balance(merchant)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--trials", type=int, default=3)
    parser.add_argument("--requests", type=int, default=10)
    parser.add_argument("--output", type=Path, default=Path("experiments/results"))
    args = parser.parse_args()
    if args.trials < 1 or args.requests < 2 or args.requests > 50:
        parser.error("Use at least one trial and 2 to 50 requests per scenario")
    args.output.mkdir(parents=True, exist_ok=True)
    rows = []
    summaries = [run_trial(i + 1, args.requests, rows) for i in range(args.trials)]
    with (args.output / "raw_requests.csv").open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    metadata = {"date_utc": datetime.now(timezone.utc).isoformat(), "database": DATABASE,
                "postgresql": sql("SELECT version()"), "python": platform.python_version(),
                "operating_system": platform.system() + " " + platform.release(),
                "machine_architecture": platform.machine(), "logical_cpus": os.cpu_count(),
                "java": subprocess.run(["java", "-version"], capture_output=True, text=True, check=True).stderr.splitlines()[0],
                "git_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
                "trials": args.trials, "requests_per_scenario": args.requests,
                "note": "Synthetic accounts and balances; local HTTP and PostgreSQL; no real SMS or money.",
                "summaries": summaries}
    (args.output / "summary.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(json.dumps({"trials": args.trials, "requests": args.requests, "checks_passed": len(summaries),
                      "raw_requests": len(rows), "output": str(args.output)}))


if __name__ == "__main__":
    main()
