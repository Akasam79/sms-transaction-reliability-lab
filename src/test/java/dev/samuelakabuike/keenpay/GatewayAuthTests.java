package dev.samuelakabuike.keenpay;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class GatewayAuthTests {
    private final GatewayAuth auth = new GatewayAuth("test-gateway-secret", "test-operator-key",
        Clock.fixed(Instant.parse("2026-10-29T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void acceptsSignedBodyAndRejectsTampering() {
        String timestamp = "2026-10-29T12:00:00Z";
        String id = "message-1";
        String body = "{\"phone\":\"+2348012345678\",\"sms\":\"BALANCE 123456\"}";
        String signature = HexFormat.of().formatHex(GatewayAuth.hmac("test-gateway-secret", timestamp + "." + id + "." + body));
        auth.verifyGateway(timestamp, id, signature, body);
        assertThatThrownBy(() -> auth.verifyGateway(timestamp, id, signature, body + " "))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsOldMessageEvenWithValidSignature() {
        String old = "2026-10-29T11:54:59Z";
        String signature = HexFormat.of().formatHex(GatewayAuth.hmac("test-gateway-secret", old + ".id.{}"));
        assertThatThrownBy(() -> auth.verifyGateway(old, "id", signature, "{}"))
            .isInstanceOf(ResponseStatusException.class);
    }
}
