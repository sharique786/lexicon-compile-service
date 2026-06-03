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

    private String   hyperscanVersion     = "5.4.0-2.0.0";
    private Compiler compiler             = new Compiler();
    private Upload   upload               = new Upload();

    /** Compiler-level settings. */
    public static class Compiler {

        @Min(1)
        private int maxTermsPerRequest = 1000;

        public int  getMaxTermsPerRequest()       { return maxTermsPerRequest; }
        public void setMaxTermsPerRequest(int v)  { this.maxTermsPerRequest = v; }
    }

    /** Upload-level settings. */
    public static class Upload {

        private String maxFileSize = "10MB";

        public String getMaxFileSize()          { return maxFileSize; }
        public void   setMaxFileSize(String v)  { this.maxFileSize = v; }
    }

    public String   getHyperscanVersion()                  { return hyperscanVersion; }
    public void     setHyperscanVersion(String v)          { this.hyperscanVersion = v; }
    public Compiler getCompiler()                          { return compiler; }
    public void     setCompiler(Compiler v)                { this.compiler = v; }
    public Upload   getUpload()                            { return upload; }
    public void     setUpload(Upload v)                    { this.upload = v; }
}
