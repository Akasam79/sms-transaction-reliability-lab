package dev.samuelakabuike.smstxnlab;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class AppSecurity {
    @Bean
    Clock clock() { return Clock.systemUTC(); }

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // Endpoint-specific gateway HMAC and operator-key checks run in the controllers.
        // This API is stateless and does not use cookies or browser sessions.
        return http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/error", "/actuator/health", "/api/v1/sms/inbound", "/api/v1/simulation/**").permitAll()
                .anyRequest().denyAll())
            .build();
    }
}
