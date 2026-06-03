package com.db.macs3.ecomms.spectre.config;

import com.db.macs3.ecomms.spectre.hyperscan.HyperscanCompiler;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring {@code @Configuration} for the Lexicon Compile Service.
 *
 * <p>Registers:
 * <ul>
 *   <li>CORS mapping for Cloud Run inter-service calls</li>
 *   <li>Hyperscan health indicator ({@code /actuator/health})</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(LexiconProperties.class)
public class LexiconCompileConfig {

    /**
     * CORS — allows any origin for API paths.
     * Exposes {@code Content-Encoding} so clients can detect GZIP responses.
     *
     * @return CORS configurer
     */
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins("*")
                        .allowedMethods("GET", "POST", "OPTIONS")
                        .allowedHeaders("*")
                        .exposedHeaders("Content-Encoding", "Content-Length")
                        .maxAge(3600);
            }
        };
    }

    /**
     * Hyperscan health indicator.
     *
     * <p>Compiles a probe pattern on every health call to confirm the native
     * library is operational. Used by Cloud Run liveness and readiness probes.
     *
     * @param compiler the Hyperscan compiler bean
     * @return health indicator
     */
    @Bean
    public HealthIndicator hyperscanHealthIndicator(HyperscanCompiler compiler) {
        return () -> {
            try {
                HyperscanCompiler.ValidationResult probe =
                        compiler.validate("health_probe", HyperscanCompiler.HS_FLAG_CASELESS);
                if (probe.isPass()) {
                    return Health.up()
                            .withDetail("engine",      "HYPERSCAN_NATIVE")
                            .withDetail("library",     "com.gliwka.hyperscan")
                            .withDetail("version",     compiler.getHyperscanVersion())
                            .withDetail("compression", "GZIP request + response")
                            .withDetail("jdk",         Runtime.version().toString())
                            .build();
                }
                return Health.down()
                        .withDetail("engine", "HYPERSCAN_NATIVE")
                        .withDetail("error",  probe.errorMessage())
                        .build();
            } catch (Exception e) {
                return Health.down()
                        .withDetail("engine", "HYPERSCAN_NATIVE")
                        .withDetail("error",  e.getMessage())
                        .build();
            }
        };
    }
}
