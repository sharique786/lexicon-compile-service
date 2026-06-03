package com.db.macs3.ecomms.spectre.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * Servlet filter that decompresses GZIP-encoded request bodies.
 *
 * <p>Uses {@code jakarta.servlet.*} (Jakarta EE 10 / Spring Boot 4).
 * @{@code Order(1)} — runs before Spring's DispatcherServlet.
 *
 * <h2>Compression flow</h2>
 * <dl>
 *   <dt>REQUEST (this filter)</dt>
 *   <dd>Client: {@code Content-Encoding: gzip} → filter wraps InputStream in
 *       {@link GZIPInputStream} → Jackson reads plain JSON transparently</dd>
 *   <dt>RESPONSE (Tomcat)</dt>
 *   <dd>Configured via {@code server.compression.*} in {@code application.yml}.
 *       Tomcat adds {@code Content-Encoding: gzip} when client sends
 *       {@code Accept-Encoding: gzip} and body exceeds threshold.</dd>
 * </dl>
 */
@Component
@Order(1)
public class GzipRequestFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(GzipRequestFilter.class);
    private static final String GZIP = "gzip";

    @Override
    public void doFilter(ServletRequest request,
                          ServletResponse response,
                          FilterChain chain) throws IOException, ServletException {

        HttpServletRequest httpReq = (HttpServletRequest) request;
        String encoding = httpReq.getHeader("Content-Encoding");

        if (GZIP.equalsIgnoreCase(encoding)) {
            log.debug("Decompressing GZIP request: {} {}",
                    httpReq.getMethod(), httpReq.getRequestURI());
            chain.doFilter(new GzipRequestWrapper(httpReq), response);
        } else {
            chain.doFilter(request, response);
        }
    }

    @Override
    public void init(FilterConfig config) { }

    @Override
    public void destroy() { }

    // ── GzipRequestWrapper ────────────────────────────────────────────────────

    /**
     * Wraps an {@link HttpServletRequest}, replacing its InputStream with a
     * GZIPInputStream. The body is eagerly decompressed into a byte array
     * so it can be re-read (required by Spring content-caching interceptors).
     */
    private static class GzipRequestWrapper extends HttpServletRequestWrapper {

        private final byte[] decompressedBody;

        GzipRequestWrapper(HttpServletRequest request) throws IOException {
            super(request);
            try (GZIPInputStream gzis = new GZIPInputStream(request.getInputStream());
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                gzis.transferTo(baos);
                decompressedBody = baos.toByteArray();
            }
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream bais = new ByteArrayInputStream(decompressedBody);
            return new ServletInputStream() {
                @Override public int     read()                               { return bais.read(); }
                @Override public int     read(byte[] b, int off, int len)     { return bais.read(b, off, len); }
                @Override public boolean isFinished()                         { return bais.available() == 0; }
                @Override public boolean isReady()                            { return true; }
                @Override public void    setReadListener(ReadListener rl) {
                    throw new UnsupportedOperationException("ReadListener not supported");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(
                    new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        /** Strip Content-Encoding so downstream code does not double-decompress. */
        @Override
        public String getHeader(String name) {
            return "Content-Encoding".equalsIgnoreCase(name) ? null : super.getHeader(name);
        }
    }
}
