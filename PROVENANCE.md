# Relationship to the 2021 project

The 2021 FUTO report is a historical source. The original public code is `Akasam79/KeenPay`, commit `06f0ea6fb8e582b5a3c9d449bc42f5190fe1c620` (15 January 2022). This repository is a 2026 redesign by Samuel Chukwuebuka Akabuike. It must not be described as the original 2021 implementation.

| 2021 function/file | New implementation | Change |
| --- | --- | --- |
| `SciPaySMS.java` | `ApiController`, `InboundService` | Signed JSON gateway boundary, normalized commands, duplicate message handling |
| `Enrollment.java` | `WalletService.register` | Provisioning moved from plaintext SMS to controlled operator API; unique phone and merchant code |
| `Payments.java` | `WalletService.pay/confirm/cancel` | Pending intent, expiry, transactional settlement, balanced entries |
| `Wallet.java` | `WalletService.balance` | Integer minor units and PIN verification |
| `PinReset.java` | `WalletService.reset` | Bcrypt hash and retry lockout; no PIN echo |
| `Recharge.java` | `WalletService.fundForSimulation` | Explicit simulated funding; no claim of real-money top-up |
| `DbConnection.java` | Spring datasource + Flyway | External credentials and versioned schema |

The original report also described an Internet-independent *handset* experience. The backend and gateway still need connectivity. A future manuscript should distinguish the original report's manual API checks from new automated experiments performed with this repository.
