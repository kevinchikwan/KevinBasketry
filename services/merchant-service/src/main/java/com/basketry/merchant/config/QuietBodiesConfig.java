package com.basketry.merchant.config;

import jakarta.validation.Valid;
import jakarta.validation.Validator;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps the values of a request body out of Spring's log (ROADMAP F3) while
 * Spring still reads the body, so every content-type, charset and 400/415
 * rule stays Spring's own (MerchantBadRequestBodiesTests). Spring logs a
 * failed read's message at DEBUG and TRACE, and Jackson's message quotes the
 * input ("Unrecognized token '...'"); it logs a failed {@code @Valid} check
 * with every rejected value at DEBUG (a login email, a name). Here neither
 * message ever holds a value.
 *
 * <p>This rests on two Spring Boot 4.1 behaviours: Boot's JSON converter
 * backs off when the application defines one, and a RequestBodyAdvice's
 * afterBodyRead runs before Spring validates a {@code @Valid} body. An
 * upgrade that changed either would bring the values back to the log
 * silently; HttpJacksonMessageLeakTests is the alarm.
 */
@Configuration(proxyBeanMethods = false)
class QuietBodiesConfig {

    /**
     * Boot's JSON converter, with the application's own JsonMapper (every
     * spring.jackson setting), but a failed read keeps neither Jackson's
     * message nor the exception: the answer is Spring Boot's own 400 ("Failed
     * to read request"), as before.
     */
    @Bean
    JacksonJsonHttpMessageConverter jacksonJsonHttpMessageConverter(JsonMapper json) {
        return new JacksonJsonHttpMessageConverter(json) {
            @Override
            public Object read(ResolvableType type, HttpInputMessage input, Map<String, Object> hints)
                    throws IOException {
                try {
                    return super.read(type, input, hints);
                } catch (HttpMessageNotReadableException unreadable) {
                    throw new HttpMessageNotReadableException("JSON parse error", input);
                }
            }
        };
    }

    /**
     * Validates a {@code @Valid} body as soon as it is read, before Spring does
     * and builds a MethodArgumentNotValidException with the rejected values.
     * A broken constraint is Spring Boot's own 400 ("Invalid request
     * content."), as before, without the field errors.
     */
    @ControllerAdvice
    static class ValidateWithoutValues extends RequestBodyAdviceAdapter {

        private final Validator validator;

        ValidateWithoutValues(Validator validator) {
            this.validator = validator;
        }

        @Override
        public boolean supports(MethodParameter parameter, Type targetType,
                                Class<? extends HttpMessageConverter<?>> converterType) {
            return parameter.hasParameterAnnotation(Valid.class);
        }

        @Override
        public Object afterBodyRead(Object body, HttpInputMessage input, MethodParameter parameter, Type targetType,
                                    Class<? extends HttpMessageConverter<?>> converterType) {
            // The JSON literal null: Spring answers it, as before (a missing body, or no retry body).
            if (body != null && !validator.validate(body).isEmpty()) {
                throw new InvalidContentException();
            }
            return body;
        }
    }

    /** Spring Boot's answer for a failed @Valid check, without the field errors or their values. */
    static class InvalidContentException extends ErrorResponseException {

        InvalidContentException() {
            super(HttpStatus.BAD_REQUEST,
                    ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request content."), null);
        }
    }
}
