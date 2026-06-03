package com.db.macs3.ecomms.spectre;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Lexicon Compile Service — Spring Boot 4.0.6 / JDK 21 / GCP Cloud Run.
 *
 * <p>Translates lexicon term descriptions into Hyperscan PCRE patterns
 * and validates them via {@code com.gliwka.hyperscan 5.4.0-2.0.0}.
 *
 * <p>Base package: {@code com.db.macs3.ecomms.spectre}
 */
@SpringBootApplication
public class LexiconCompileServiceApp {

    /**
     * Application entry point.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(LexiconCompileServiceApp.class, args);
    }
}
