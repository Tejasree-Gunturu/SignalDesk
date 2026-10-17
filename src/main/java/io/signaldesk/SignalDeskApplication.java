package io.signaldesk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
public class SignalDeskApplication {
    public static void main(String[] args) {
        SpringApplication.run(SignalDeskApplication.class, args);
    }
}
