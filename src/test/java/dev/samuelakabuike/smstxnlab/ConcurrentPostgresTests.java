package dev.samuelakabuike.smstxnlab;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named = "SMSLAB_TEST_POSTGRES", matches = "true")
@SpringBootTest(properties = {"smstxnlab.gateway-secret=test-gateway-secret", "smstxnlab.operator-key=test-operator-key"})
class ConcurrentPostgresTests {
    private static final Pattern REFERENCE = Pattern.compile("[0-9a-f]{8}-[0-9a-f-]{27,}");

    @Autowired WalletService wallets;
    @Autowired InboundService inbound;
    @Autowired JdbcTemplate jdbc;

    @Test
    void concurrentDuplicateDeliveryCreatesOneIntentAndReturnsTheSameReply() throws Exception {
        var fixture = newFixture();
        String id = "same-" + UUID.randomUUID();
        String command = "PAY " + fixture.merchantCode() + " 20";
        var replies = inParallel(20, () -> inbound.receive(id, fixture.customerPhone(), command));
        assertThat(replies).hasSize(20).allMatch(replies.getFirst()::equals);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inbound_messages WHERE message_id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_intents WHERE payer_id = (SELECT id FROM accounts WHERE phone = ?)", Integer.class, fixture.customerPhone())).isEqualTo(1);
    }

    @Test
    void concurrentConfirmationsWithDistinctMessageIdsSettleOnce() throws Exception {
        var fixture = newFixture();
        String reply = inbound.receive("start-" + UUID.randomUUID(), fixture.customerPhone(), "PAY " + fixture.merchantCode() + " 20");
        var matcher = REFERENCE.matcher(reply);
        assertThat(matcher.find()).isTrue();
        String reference = matcher.group();
        var replies = inParallel(20, () -> inbound.receive("confirm-" + UUID.randomUUID(), fixture.customerPhone(), "CONFIRM " + reference + " 123456"));
        assertThat(replies.stream().filter(s -> s.contains("Payment completed")).count()).isEqualTo(1);
        assertThat(replies.stream().filter(s -> s.contains("already completed")).count()).isEqualTo(19);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE operation_id = ?", Integer.class, reference)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT sum(delta_minor) FROM ledger_entries WHERE operation_id = ?", Long.class, reference)).isZero();
        assertThat(balance(fixture.customerPhone())).isEqualTo(98_000L);
        assertThat(balance(fixture.merchantPhone())).isEqualTo(2_000L);
    }

    private Fixture newFixture() {
        String suffix = String.valueOf(System.nanoTime()).substring(5, 13);
        String customerPhone = "+23486" + suffix;
        String merchantPhone = "+23487" + suffix;
        wallets.register(customerPhone, "CUSTOMER", "Research Customer", "123456");
        var merchant = wallets.register(merchantPhone, "MERCHANT", "Research Merchant", "654321");
        wallets.fundForSimulation(customerPhone, 100_000L);
        return new Fixture(customerPhone, merchantPhone, merchant.merchantCode());
    }

    private long balance(String phone) {
        return jdbc.queryForObject("SELECT w.balance_minor FROM wallets w JOIN accounts a ON a.id = w.account_id WHERE a.phone = ?", Long.class, phone);
    }

    private static List<String> inParallel(int count, Callable<String> call) throws Exception {
        try (var executor = Executors.newFixedThreadPool(count)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(executor.submit(() -> { start.await(); return call.call(); }));
            }
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) results.add(future.get());
            return results;
        }
    }

    private record Fixture(String customerPhone, String merchantPhone, String merchantCode) { }
}
