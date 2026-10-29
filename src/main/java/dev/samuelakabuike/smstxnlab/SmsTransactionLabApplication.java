package dev.samuelakabuike.smstxnlab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class SmsTransactionLabApplication {

	public static void main(String[] args) {
		SpringApplication.run(SmsTransactionLabApplication.class, args);
	}

}
