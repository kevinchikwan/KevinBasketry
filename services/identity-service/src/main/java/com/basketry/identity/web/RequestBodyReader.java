package com.basketry.identity.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validator;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a request body for a controller that takes it as bytes (ROADMAP F2),
 * with the application's own JsonMapper (every setting of application.yml) and
 * the application's own bean Validator (the request's constraints, as
 * {@code @Valid} checked them). When Spring read the body, a Jackson error
 * reached Spring's DEBUG log with Jackson's message, which quotes the input,
 * and a failed {@code @Valid} check was logged with its rejected value (an
 * email). The exceptions here carry neither.
 *
 * <p>Every answer is what Spring answered before (IdentityBadRequestBodiesTests),
 * by Spring's own rules, in its order:
 * <ol>
 *   <li>a Content-Type that cannot be parsed, or an unknown charset: 415
 *       "Could not parse Content-Type.", answered by Spring's own argument
 *       resolver before the controller is called, even for bytes;</li>
 *   <li>no Content-Type and no body: 400, the missing body;</li>
 *   <li>any other type that is not JSON, even with no body: 415 naming the type
 *       (application/octet-stream when there is none);</li>
 *   <li>no body: 400;</li>
 *   <li>the body, decoded as Spring's JSON converter did: a Unicode charset (or
 *       none) is left to Jackson's own detection, any other charset decodes it;
 *       unreadable or the JSON literal null: 400;</li>
 *   <li>a broken constraint: 400.</li>
 * </ol>
 */
@Component
public class RequestBodyReader {

    /** What Spring's JSON converter read. */
    private static final List<MediaType> JSON_TYPES =
            List.of(MediaType.APPLICATION_JSON, new MediaType("application", "*+json"));

    /** The charsets Spring's JSON converter left to Jackson's detection (Jackson's JsonEncoding, and UTF-16). */
    private static final Set<String> UNICODE = Set.of("UTF-8", "UTF-16", "UTF-16BE", "UTF-16LE", "UTF-32BE", "UTF-32LE");

    private final JsonMapper json;
    private final Validator validator;

    public RequestBodyReader(JsonMapper json, Validator validator) {
        this.json = json;
        this.validator = validator;
    }

    /**
     * @param bytes the body, or null when there is none ({@code @RequestBody(required = false)})
     * @throws HttpMediaTypeNotSupportedException a content type that is not JSON (415)
     * @throws UnreadableBodyException            no body, not JSON of the type's shape, or the JSON literal null
     * @throws InvalidBodyException               a constraint of the type is broken
     */
    public <T> T read(HttpServletRequest request, byte[] bytes, Class<T> type)
            throws HttpMediaTypeNotSupportedException {
        boolean empty = bytes == null || bytes.length == 0;
        // The content type as Spring's argument resolver saw it: the header plus the request's character
        // encoding. One that cannot be parsed never gets here: the resolver answers it before the controller.
        MediaType contentType = new ServletServerHttpRequest(request).getHeaders().getContentType();
        boolean noContentType = contentType == null;
        MediaType sent = noContentType ? MediaType.APPLICATION_OCTET_STREAM : contentType;
        if (JSON_TYPES.stream().noneMatch(json -> json.includes(sent))) {
            if (noContentType && empty) {
                // Spring answers a request with neither a type nor a body as a missing body.
                throw new UnreadableBodyException();
            }
            throw new HttpMediaTypeNotSupportedException(sent, JSON_TYPES, HttpMethod.valueOf(request.getMethod()));
        }
        if (empty) {
            throw new UnreadableBodyException();
        }
        T body;
        try {
            Charset charset = sent.getCharset() == null ? StandardCharsets.UTF_8 : sent.getCharset();
            body = UNICODE.contains(charset.name())
                    ? json.readValue(bytes, type)
                    : json.readValue(new String(bytes, charset), type);
        } catch (JacksonException unreadable) {
            // Neither the message nor the exception is kept: both can quote the input.
            throw new UnreadableBodyException();
        }
        if (body == null) {
            // The JSON literal null: Spring answered it as a missing body.
            throw new UnreadableBodyException();
        }
        if (!validator.validate(body).isEmpty()) {
            // Neither the violations nor their invalid values are kept.
            throw new InvalidBodyException();
        }
        return body;
    }
}
