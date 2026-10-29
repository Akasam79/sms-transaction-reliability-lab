# Security scope and known limitations

This repository handles **fictional accounts and balances only**. Do not enter a real PIN, BVN, bank account, phone number, or personal payment data. Do not connect it to a real SMS provider or move real funds.

The prototype hashes PINs, limits failed attempts, authenticates simulated gateway calls with HMAC, limits timestamp skew, deduplicates gateway messages, validates amounts, uses integer kobo, and settles each payment in one database transaction. Those controls support controlled experiments; they do not establish regulatory compliance or production readiness.

The key unresolved risk is the SMS channel itself. `CONFIRM`, `BALANCE`, and `RESET` carry PINs in message text. A carrier, device, SMS app, gateway, or phone thief may read or replay them. The HTTP gateway signature only proves the configured gateway sent a request. It does not prove the handset owner authored an SMS. Stronger authentication would require a redesigned user interaction and independent security review.

Other work before any external deployment: real gateway verification and threat modelling; per-sender and per-IP rate limiting; secure key management and rotation; PIN and message-body redaction from logs; separate operator identity and authorization; TLS enforcement; account recovery; device/SIM-swap controls; privacy review; concurrency and fault-injection testing on PostgreSQL; reconciliation with an external settlement source; disaster recovery; monitoring; and compliance with applicable payment and data-protection requirements.

The original public NetBeans repository contains a hardcoded database credential. Treat it as compromised and rotate it if it remains valid. This new repository does not include that credential or reuse its database.
