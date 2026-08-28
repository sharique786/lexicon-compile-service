package com.db.macs3.ecomms.spectre.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe configuration bound from {@code application.yml} under prefix {@code lexicon}.
 */
@Validated
@ConfigurationProperties(prefix = "lexicon")
public class LexiconProperties {

    private String hyperscanVersion = "5.4.0-2.0.0";
    private Compiler compiler = new Compiler();
    private Upload upload = new Upload();

    /**
     * Compiler-level settings.
     */
    public static class Compiler {

        @Min(1)
        private int maxTermsPerRequest = 1000;

        public int getMaxTermsPerRequest() {
            return maxTermsPerRequest;
        }

        public void setMaxTermsPerRequest(int maxTermsPerRequest) {
            this.maxTermsPerRequest = maxTermsPerRequest;
        }
    }

    /**
     * Upload-level settings.
     */
    public static class Upload {

        private String maxFileSize = "10MB";

        public String getMaxFileSize() {
            return maxFileSize;
        }

        public void setMaxFileSize(String maxFileSize) {
            this.maxFileSize = maxFileSize;
        }
    }

    public String getHyperscanVersion() {
        return hyperscanVersion;
    }

    public void setHyperscanVersion(String hyperscanVersion) {
        this.hyperscanVersion = hyperscanVersion;
    }

    public Compiler getCompiler() {
        return compiler;
    }

    public void setCompiler(Compiler compiler) {
        this.compiler = compiler;
    }

    public Upload getUpload() {
        return upload;
    }

    public void setUpload(Upload upload) {
        this.upload = upload;
    }
}
