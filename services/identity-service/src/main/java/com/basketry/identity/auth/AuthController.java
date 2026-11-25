package com.basketry.identity.auth;

import com.basketry.identity.web.RequestBodyReader;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.HttpMediaTypeNotSupportedException;

@RestController
public class AuthController {

    private final LoginService loginService;
    private final RequestBodyReader bodies;

    public AuthController(LoginService loginService, RequestBodyReader bodies) {
        this.loginService = loginService;
        this.bodies = bodies;
    }

    /**
     * 200 with a signed JWT; 401 for an unknown email or wrong password (same
     * response for both); 403 for a deactivated buyer. The body is read and
     * validated by RequestBodyReader, so neither the email nor the password
     * reaches Spring's log (ROADMAP F2).
     */
    @PostMapping("/auth/login")
    public LoginResponse login(@RequestBody(required = false) byte[] body, HttpServletRequest http)
            throws HttpMediaTypeNotSupportedException {
        LoginRequest request = bodies.read(http, body, LoginRequest.class);
        return loginService.login(request.email(), request.password());
    }
}
