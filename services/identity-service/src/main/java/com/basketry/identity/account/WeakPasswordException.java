package com.basketry.identity.account;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * 400: the password fails the password policy. {@code detail} names every
 * failed rule; {@code violations} lists them one per entry for clients that
 * want to show them separately.
 */
public class WeakPasswordException extends ErrorResponseException {

    public WeakPasswordException(List<String> violations) {
        super(HttpStatus.BAD_REQUEST, problem(violations), null);
    }

    private static ProblemDetail problem(List<String> violations) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, String.join(" ", violations));
        problem.setTitle("Password does not meet the policy");
        problem.setProperty("violations", violations);
        return problem;
    }
}
