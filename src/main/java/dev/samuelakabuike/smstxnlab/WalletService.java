package dev.samuelakabuike.smstxnlab;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;

@Service
public class WalletService {
    private static final Pattern PHONE = Pattern.compile("\\+[1-9][0-9]{7,14}");
    private static final Pattern PIN = Pattern.compile("[0-9]{6}");
    private static final Pattern NAME = Pattern.compile("[\\p{L}][\\p{L} .'-]{1,99}");
    private static final long MAX_AMOUNT_MINOR = 100_000_000L; // NGN 1,000,000 per operation

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final int pendingMinutes;

    public WalletService(JdbcTemplate jdbc, PasswordEncoder encoder, Clock clock,
                         @Value("${smstxnlab.pending-minutes:10}") int pendingMinutes) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.clock = clock;
        this.pendingMinutes = pendingMinutes;
    }

    public record AccountView(long id, String phone, String role, String displayName, String merchantCode) { }
    private record Account(long id, String phone, String role, String name, String pinHash,
                           String merchantCode, int failedAttempts, OffsetDateTime lockedUntil) { }
    private record Intent(String id, long payerId, long payeeId, long amountMinor, String status,
                          OffsetDateTime expiresAt) { }

    @Transactional
    public AccountView register(String phone, String role, String name, String pin) {
        if (phone == null || !PHONE.matcher(phone).matches()) throw new DomainException("Use an E.164 phone number, for example +2348012345678.");
        if (role == null || !(role.equalsIgnoreCase("CUSTOMER") || role.equalsIgnoreCase("MERCHANT"))) throw new DomainException("Role must be CUSTOMER or MERCHANT.");
        if (name == null || !NAME.matcher(name).matches()) throw new DomainException("Name must contain 2 to 100 letters/spaces.");
        validatePin(pin);
        if (!jdbc.queryForList("SELECT id FROM accounts WHERE phone = ?", phone).isEmpty()) throw new DomainException("Phone number is already registered.");
        String normalizedRole = role.toUpperCase(Locale.ROOT);
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO accounts(phone, role, display_name, pin_hash) VALUES (?,?,?,?)", new String[]{"id"});
            statement.setString(1, phone);
            statement.setString(2, normalizedRole);
            statement.setString(3, name.trim());
            statement.setString(4, encoder.encode(pin));
            return statement;
        }, keys);
        long id = keys.getKey().longValue();
        String code = normalizedRole.equals("MERCHANT") ? "M" + String.format("%07d", id) : null;
        if (code != null) jdbc.update("UPDATE accounts SET merchant_code = ? WHERE id = ?", code, id);
        jdbc.update("INSERT INTO wallets(account_id, balance_minor) VALUES (?,0)", id);
        return new AccountView(id, phone, normalizedRole, name.trim(), code);
    }

    @Transactional
    public String fundForSimulation(String phone, long amountMinor) {
        validateAmount(amountMinor);
        Account account = findByPhone(phone);
        jdbc.update("UPDATE wallets SET balance_minor = balance_minor + ?, version = version + 1 WHERE account_id = ?", amountMinor, account.id());
        jdbc.update("INSERT INTO ledger_entries(operation_id, account_id, delta_minor, entry_type) VALUES (?,?,?,'TEST_FUND')",
                    UUID.randomUUID().toString(), account.id(), amountMinor);
        return "Simulation wallet funded with " + formatNaira(amountMinor) + ".";
    }

    @Transactional
    public String execute(String phone, String body) {
        if (phone == null || !PHONE.matcher(phone).matches()) return "Invalid sender phone format.";
        if (body == null || body.isBlank() || body.length() > 160) return "Message must contain 1 to 160 characters.";
        String[] parts = body.trim().split("\\s+");
        try {
            return switch (parts[0].toUpperCase(Locale.ROOT)) {
                case "PAY" -> pay(phone, parts);
                case "CONFIRM" -> confirm(phone, parts);
                case "BALANCE" -> balance(phone, parts);
                case "RESET" -> reset(phone, parts);
                case "CANCEL" -> cancel(phone, parts);
                default -> "Unknown command. Use PAY, CONFIRM, BALANCE, RESET or CANCEL.";
            };
        } catch (DomainException | IllegalArgumentException ex) {
            return ex.getMessage();
        }
    }

    private String pay(String phone, String[] parts) {
        if (parts.length != 3) return "Format: PAY <merchant-code> <whole-naira-amount>.";
        Account payer = findByPhone(phone);
        if (!payer.role().equals("CUSTOMER")) return "Only customers can initiate merchant payments.";
        List<Account> merchants = jdbc.query("SELECT * FROM accounts WHERE merchant_code = ?", this::mapAccount, parts[1].toUpperCase(Locale.ROOT));
        if (merchants.isEmpty()) return "Merchant code not found.";
        long amount = parseNaira(parts[2]);
        if (balanceOf(payer.id()) < amount) return "Insufficient balance.";
        Account merchant = merchants.getFirst();
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO payment_intents(id,payer_id,payee_id,amount_minor,status,expires_at) VALUES (?,?,?,?,'PENDING',?)",
                    id, payer.id(), merchant.id(), amount, now().plusMinutes(pendingMinutes));
        return "Pay " + merchant.name() + " " + formatNaira(amount) + "? Reply CONFIRM " + id + " <6-digit-PIN> within " + pendingMinutes + " minutes, or CANCEL " + id + ".";
    }

    private String confirm(String phone, String[] parts) {
        if (parts.length != 3) return "Format: CONFIRM <payment-id> <6-digit-PIN>.";
        String paymentId = parseUuid(parts[1]);
        Intent intent = findIntentForUpdate(paymentId);
        Account payer = findByPhoneForUpdate(phone);
        if (intent.payerId() != payer.id()) return "Payment does not belong to this sender.";
        if (intent.status().equals("SETTLED")) return "Payment already completed; no second debit was made.";
        if (!intent.status().equals("PENDING")) return "Payment is no longer pending.";
        if (!intent.expiresAt().isAfter(now())) {
            jdbc.update("UPDATE payment_intents SET status = 'EXPIRED' WHERE id = ?", paymentId);
            return "Payment expired. Start again with PAY.";
        }
        if (!verifyPin(payer, parts[2])) return "Invalid PIN or account temporarily locked.";

        // Lock both wallets in account-id order to avoid concurrent lost updates and reduce deadlocks.
        long first = Math.min(intent.payerId(), intent.payeeId());
        long second = Math.max(intent.payerId(), intent.payeeId());
        lockWallet(first);
        lockWallet(second);
        if (balanceOf(intent.payerId()) < intent.amountMinor()) return "Insufficient balance at confirmation.";
        int debited = jdbc.update("UPDATE wallets SET balance_minor = balance_minor - ?, version = version + 1 WHERE account_id = ? AND balance_minor >= ?",
                                  intent.amountMinor(), intent.payerId(), intent.amountMinor());
        if (debited != 1) throw new IllegalStateException("Debit failed after balance check.");
        int credited = jdbc.update("UPDATE wallets SET balance_minor = balance_minor + ?, version = version + 1 WHERE account_id = ?",
                                   intent.amountMinor(), intent.payeeId());
        if (credited != 1) throw new IllegalStateException("Credit failed after debit.");
        jdbc.update("INSERT INTO ledger_entries(operation_id,account_id,delta_minor,entry_type) VALUES (?,?,?,'DEBIT')",
                    paymentId, intent.payerId(), -intent.amountMinor());
        jdbc.update("INSERT INTO ledger_entries(operation_id,account_id,delta_minor,entry_type) VALUES (?,?,?,'CREDIT')",
                    paymentId, intent.payeeId(), intent.amountMinor());
        jdbc.update("UPDATE payment_intents SET status = 'SETTLED', settled_at = ? WHERE id = ?", now(), paymentId);
        return "Payment completed: " + formatNaira(intent.amountMinor()) + ". Reference " + paymentId + ".";
    }

    private String balance(String phone, String[] parts) {
        if (parts.length != 2) return "Format: BALANCE <6-digit-PIN>.";
        Account account = findByPhoneForUpdate(phone);
        if (!verifyPin(account, parts[1])) return "Invalid PIN or account temporarily locked.";
        return "Simulation balance: " + formatNaira(balanceOf(account.id())) + ".";
    }

    private String reset(String phone, String[] parts) {
        if (parts.length != 3) return "Format: RESET <old-PIN> <new-6-digit-PIN>.";
        Account account = findByPhoneForUpdate(phone);
        validatePin(parts[2]);
        if (!verifyPin(account, parts[1])) return "Invalid PIN or account temporarily locked.";
        jdbc.update("UPDATE accounts SET pin_hash = ?, failed_pin_attempts = 0, locked_until = NULL WHERE id = ?",
                    encoder.encode(parts[2]), account.id());
        return "PIN changed. Never share it with anyone.";
    }

    private String cancel(String phone, String[] parts) {
        if (parts.length != 2) return "Format: CANCEL <payment-id>.";
        Account account = findByPhone(phone);
        Intent intent = findIntentForUpdate(parseUuid(parts[1]));
        if (intent.payerId() != account.id()) return "Payment does not belong to this sender.";
        if (!intent.status().equals("PENDING")) return "Payment is no longer pending.";
        jdbc.update("UPDATE payment_intents SET status = 'CANCELLED' WHERE id = ?", intent.id());
        return "Payment cancelled.";
    }

    private boolean verifyPin(Account account, String pin) {
        if (account.lockedUntil() != null && account.lockedUntil().isAfter(now())) return false;
        if (pin != null && PIN.matcher(pin).matches() && encoder.matches(pin, account.pinHash())) {
            jdbc.update("UPDATE accounts SET failed_pin_attempts = 0, locked_until = NULL WHERE id = ?", account.id());
            return true;
        }
        int attempts = account.failedAttempts() + 1;
        OffsetDateTime locked = attempts >= 5 ? now().plusMinutes(15) : null;
        jdbc.update("UPDATE accounts SET failed_pin_attempts = ?, locked_until = ? WHERE id = ?", attempts >= 5 ? 0 : attempts, locked, account.id());
        return false;
    }

    private void lockWallet(long id) {
        jdbc.queryForObject("SELECT balance_minor FROM wallets WHERE account_id = ? FOR UPDATE", Long.class, id);
    }

    private long balanceOf(long id) {
        Long value = jdbc.queryForObject("SELECT balance_minor FROM wallets WHERE account_id = ?", Long.class, id);
        if (value == null) throw new IllegalStateException("Wallet missing for account " + id);
        return value;
    }

    private Account findByPhone(String phone) {
        List<Account> rows = jdbc.query("SELECT * FROM accounts WHERE phone = ?", this::mapAccount, phone);
        if (rows.isEmpty()) throw new DomainException("Account not found.");
        return rows.getFirst();
    }

    private Account findByPhoneForUpdate(String phone) {
        List<Account> rows = jdbc.query("SELECT * FROM accounts WHERE phone = ? FOR UPDATE", this::mapAccount, phone);
        if (rows.isEmpty()) throw new DomainException("Account not found.");
        return rows.getFirst();
    }

    private Account mapAccount(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Account(rs.getLong("id"), rs.getString("phone"), rs.getString("role"),
            rs.getString("display_name"), rs.getString("pin_hash"), rs.getString("merchant_code"),
            rs.getInt("failed_pin_attempts"), rs.getObject("locked_until", OffsetDateTime.class));
    }

    private Intent findIntentForUpdate(String id) {
        List<Intent> rows = jdbc.query("SELECT * FROM payment_intents WHERE id = ? FOR UPDATE", (rs, row) ->
            new Intent(rs.getString("id"), rs.getLong("payer_id"), rs.getLong("payee_id"),
                rs.getLong("amount_minor"), rs.getString("status"), rs.getObject("expires_at", OffsetDateTime.class)), id);
        if (rows.isEmpty()) throw new DomainException("Payment not found.");
        return rows.getFirst();
    }

    private static void validatePin(String pin) {
        if (pin == null || !PIN.matcher(pin).matches()) throw new DomainException("PIN must be exactly six digits.");
    }

    private static void validateAmount(long amount) {
        if (amount <= 0 || amount > MAX_AMOUNT_MINOR) throw new DomainException("Amount must be from NGN 1 to NGN 1,000,000.");
    }

    private static long parseNaira(String input) {
        if (input == null || !input.matches("[1-9][0-9]{0,6}")) throw new DomainException("Amount must be a whole naira value from 1 to 1,000,000.");
        long amount = Math.multiplyExact(Long.parseLong(input), 100);
        validateAmount(amount);
        return amount;
    }

    private static String parseUuid(String input) {
        try { return UUID.fromString(input).toString(); }
        catch (IllegalArgumentException ex) { throw new DomainException("Invalid payment reference."); }
    }

    private static String formatNaira(long minor) {
        return "NGN " + BigDecimal.valueOf(minor, 2).toPlainString();
    }

    private OffsetDateTime now() { return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC); }

    public static class DomainException extends RuntimeException {
        public DomainException(String message) { super(message); }
    }
}
