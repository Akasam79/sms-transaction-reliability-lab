package dev.samuelakabuike.smstxnlab;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class InboundService {
    private final JdbcTemplate jdbc;
    private final WalletService wallets;

    public InboundService(JdbcTemplate jdbc, WalletService wallets) {
        this.jdbc = jdbc;
        this.wallets = wallets;
    }

    @Transactional
    public String receive(String messageId, String phone, String sms) {
        if (messageId == null || !messageId.matches("[A-Za-z0-9._:-]{1,100}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid message ID.");
        // PostgreSQL serializes deliveries of the same message ID, including across app instances.
        // H2 remains a sequential developer/test database; concurrency claims use PostgreSQL only.
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            if (connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")) {
                try (var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                    statement.setString(1, messageId);
                    statement.execute();
                }
            }
            return null;
        });
        String digest = GatewayAuth.sha256(phone + "\n" + sms);
        List<MessageRecord> previous = jdbc.query("SELECT body_sha256, response_text FROM inbound_messages WHERE message_id = ?",
            (rs, row) -> new MessageRecord(rs.getString(1), rs.getString(2)), messageId);
        if (!previous.isEmpty()) {
            if (!previous.getFirst().digest().equals(digest))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Message ID reused with different content.");
            return previous.getFirst().response();
        }
        jdbc.update("INSERT INTO inbound_messages(message_id,phone,body_sha256) VALUES (?,?,?)", messageId, phone, digest);
        String response = wallets.execute(phone, sms);
        jdbc.update("UPDATE inbound_messages SET response_text = ? WHERE message_id = ?", response, messageId);
        return response;
    }

    private record MessageRecord(String digest, String response) { }
}
