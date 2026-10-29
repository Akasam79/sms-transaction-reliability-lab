package dev.samuelakabuike.keenpay;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class GatewayAuth {
    private final String gatewaySecret;
    private final String operatorKey;
    private final Clock clock;

    public GatewayAuth(@Value("${keenpay.gateway-secret:}") String gatewaySecret,
                       @Value("${keenpay.operator-key:}") String operatorKey, Clock clock) {
        this.gatewaySecret = gatewaySecret;
        this.operatorKey = operatorKey;
        this.clock = clock;
    }

    public void verifyGateway(String timestamp, String messageId, String signature, String rawBody) {
        if (gatewaySecret.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Gateway secret is not configured.");
        try {
            Instant time = Instant.parse(timestamp);
            if (Duration.between(time, clock.instant()).abs().compareTo(Duration.ofMinutes(5)) > 0)
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Gateway timestamp outside five-minute window.");
            byte[] expected = hmac(gatewaySecret, timestamp + "." + messageId + "." + rawBody);
            byte[] actual = HexFormat.of().parseHex(signature);
            if (!MessageDigest.isEqual(expected, actual))
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid gateway signature.");
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid gateway authentication.");
        }
    }

    public void verifyOperator(String supplied) {
        if (operatorKey.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Operator key is not configured.");
        if (supplied == null || !MessageDigest.isEqual(operatorKey.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid operator key.");
    }

    public static byte[] hmac(String secret, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) { throw new IllegalStateException("HMAC unavailable", ex); }
    }

    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }
}
