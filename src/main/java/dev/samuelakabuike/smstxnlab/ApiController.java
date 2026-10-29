package dev.samuelakabuike.smstxnlab;

import tools.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ApiController {
    private final WalletService wallets;
    private final InboundService inbound;
    private final GatewayAuth auth;
    private final ObjectMapper mapper;

    public ApiController(WalletService wallets, InboundService inbound, GatewayAuth auth, ObjectMapper mapper) {
        this.wallets = wallets;
        this.inbound = inbound;
        this.auth = auth;
        this.mapper = mapper;
    }

    public record SmsInput(@NotBlank String phone, @NotBlank String sms) { }
    public record AccountInput(@NotBlank String phone, @NotBlank String role,
                               @NotBlank String name, @NotBlank String pin) { }
    public record FundInput(@NotBlank String phone, @NotNull Long amountNaira) { }
    public record SmsOutput(String messageId, String reply) { }

    @PostMapping("/sms/inbound")
    public SmsOutput receive(@RequestHeader("X-SmsLab-Timestamp") String timestamp,
                             @RequestHeader("X-SmsLab-Message-Id") String messageId,
                             @RequestHeader("X-SmsLab-Signature") String signature,
                             @RequestBody String raw) throws Exception {
        auth.verifyGateway(timestamp, messageId, signature, raw);
        SmsInput input = mapper.readValue(raw, SmsInput.class);
        return new SmsOutput(messageId, inbound.receive(messageId, input.phone(), input.sms()));
    }

    @PostMapping("/simulation/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public WalletService.AccountView register(@RequestHeader("X-SmsLab-Operator-Key") String key,
                                              @Valid @RequestBody AccountInput input) {
        auth.verifyOperator(key);
        return wallets.register(input.phone(), input.role(), input.name(), input.pin());
    }

    @PostMapping("/simulation/fund")
    public Map<String, String> fund(@RequestHeader("X-SmsLab-Operator-Key") String key,
                                    @Valid @RequestBody FundInput input) {
        auth.verifyOperator(key);
        long minor;
        try { minor = Math.multiplyExact(input.amountNaira(), 100L); }
        catch (ArithmeticException ex) { throw new WalletService.DomainException("Amount is too large."); }
        return Map.of("message", wallets.fundForSimulation(input.phone(), minor));
    }

    @ExceptionHandler(WalletService.DomainException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> domainError(WalletService.DomainException ex) {
        return Map.of("error", ex.getMessage());
    }
}
