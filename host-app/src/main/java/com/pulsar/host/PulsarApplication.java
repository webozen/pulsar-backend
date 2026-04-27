package com.pulsar.host;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.pulsar")
@EnableScheduling
public class PulsarApplication {
    public static void main(String[] args) {
        SpringApplication.run(PulsarApplication.class, args);
    }
}
