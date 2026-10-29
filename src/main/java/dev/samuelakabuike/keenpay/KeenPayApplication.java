package dev.samuelakabuike.keenpay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class KeenPayApplication {

	public static void main(String[] args) {
		SpringApplication.run(KeenPayApplication.class, args);
	}

}
