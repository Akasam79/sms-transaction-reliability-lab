package dev.samuelakabuike.smstxnlab;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {"smstxnlab.gateway-secret=test-gateway-secret", "smstxnlab.operator-key=test-operator-key"})
class WalletFlowTests {
    private static final Pattern REFERENCE = Pattern.compile("[0-9a-f]{8}-[0-9a-f-]{27,}");

    @Autowired WalletService wallets;
    @Autowired InboundService inbound;
    @Autowired JdbcTemplate jdbc;

    @Test
    void paymentIsAtomicAndCannotSettleTwice() {
        String suffix = String.valueOf(System.nanoTime()).substring(5, 13);
        String customerPhone = "+23480" + suffix;
        String merchantPhone = "+23481" + suffix;
        wallets.register(customerPhone, "CUSTOMER", "Ada Example", "123456");
        var merchant = wallets.register(merchantPhone, "MERCHANT", "Test Shop", "654321");
        wallets.fundForSimulation(customerPhone, 10_000L);

        String initiation = inbound.receive("msg-" + UUID.randomUUID(), customerPhone, "PAY " + merchant.merchantCode() + " 40");
        Matcher match = REFERENCE.matcher(initiation);
        assertThat(match.find()).isTrue();
        String reference = match.group();

        String confirmation = inbound.receive("msg-" + UUID.randomUUID(), customerPhone, "CONFIRM " + reference + " 123456");
        assertThat(confirmation).contains("Payment completed");
        assertThat(inbound.receive("msg-" + UUID.randomUUID(), customerPhone, "CONFIRM " + reference + " 123456"))
            .contains("already completed");
        assertThat(balance(customerPhone)).isEqualTo(6_000L);
        assertThat(balance(merchantPhone)).isEqualTo(4_000L);
        assertThat(jdbc.queryForObject("SELECT SUM(delta_minor) FROM ledger_entries WHERE operation_id = ?", Long.class, reference))
            .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries WHERE operation_id = ?", Integer.class, reference))
            .isEqualTo(2);
    }

    @Test
    void duplicateGatewayDeliveryReturnsOriginalReplyWithoutNewIntent() {
        String suffix = String.valueOf(System.nanoTime()).substring(5, 13);
        String customerPhone = "+23482" + suffix;
        String merchantPhone = "+23483" + suffix;
        wallets.register(customerPhone, "CUSTOMER", "Bola Example", "123456");
        var merchant = wallets.register(merchantPhone, "MERCHANT", "Demo Shop", "654321");
        wallets.fundForSimulation(customerPhone, 10_000L);
        String id = "dup-" + UUID.randomUUID();
        String command = "PAY " + merchant.merchantCode() + " 20";
        String first = inbound.receive(id, customerPhone, command);
        String second = inbound.receive(id, customerPhone, command);
        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_intents WHERE payer_id = (SELECT id FROM accounts WHERE phone = ?)", Integer.class, customerPhone))
            .isEqualTo(1);
    }

    @Test
    void rejectsInvalidAmountsAndLocksRepeatedWrongPins() {
        String phone = "+23484" + String.valueOf(System.nanoTime()).substring(5, 13);
        wallets.register(phone, "CUSTOMER", "Chika Example", "123456");
        String shopPhone = "+23485" + String.valueOf(System.nanoTime()).substring(5, 13);
        var shop = wallets.register(shopPhone, "MERCHANT", "Test Market", "654321");
        assertThat(wallets.execute(phone, "PAY " + shop.merchantCode() + " -5")).contains("Amount must");
        for (int i = 0; i < 5; i++) assertThat(wallets.execute(phone, "BALANCE 000000")).contains("Invalid PIN");
        assertThat(wallets.execute(phone, "BALANCE 123456")).contains("locked");
    }

    private long balance(String phone) {
        return jdbc.queryForObject("SELECT w.balance_minor FROM wallets w JOIN accounts a ON a.id = w.account_id WHERE a.phone = ?", Long.class, phone);
    }
}
