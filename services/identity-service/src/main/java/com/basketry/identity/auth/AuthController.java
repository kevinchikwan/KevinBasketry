package com.basketry.identity.auth;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final LoginService loginService;

    public AuthController(LoginService loginService) {
        this.loginService = loginService;
    }

    /**
     * 200 with a signed JWT; 401 for an unknown email or wrong password (same
     * response for both); 403 for a deactivated buyer.
     */
    @PostMapping("/auth/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return loginService.login(request.email(), request.password());
    }
}
