package com.basketry.order.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A checkout body over {@value #LIMIT} bytes is a 413 (DESIGN.md Section 3,
 * order-service), decided by the bytes actually read, not by Content-Length: a
 * chunked body has none, and a declared length can be wrong. The filter reads
 * at most LIMIT + 1 bytes; above the limit it answers 413 at once, otherwise
 * it hands the bytes on. A Content-Length above the limit is refused before
 * reading. The body is held only for this request and is never logged.
 * Registered for POST /orders/checkout, before Spring Security, so a huge body
 * costs no token check (FilterConfig).
 */
class CheckoutBodyLimitFilter extends OncePerRequestFilter {

    static final int LIMIT = 64 * 1024;
    static final String CODE = "BODY_TOO_LARGE";

    private static final String TOO_LARGE_BODY = "{\"type\":\"about:blank\",\"title\":\"Content Too Large\","
            + "\"status\":413,\"detail\":\"The checkout body is larger than 64 KB.\",\"code\":\"" + CODE + "\"}";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!"POST".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > LIMIT) {
            tooLarge(response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(LIMIT + 1);
        if (body.length > LIMIT) {
            tooLarge(response);
            return;
        }
        chain.doFilter(new ReadBody(request, body), response);
    }

    private static void tooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        // The rest of the body is not read; the client may not send it.
        response.setHeader("Connection", "close");
        response.getOutputStream().write(TOO_LARGE_BODY.getBytes(StandardCharsets.UTF_8));
    }

    /** The request with the body already read, from memory. */
    private static final class ReadBody extends HttpServletRequestWrapper {

        private final byte[] body;

        ReadBody(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream bytes = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return bytes.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    return bytes.read(buffer, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return bytes.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Not used: the body is already read");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
