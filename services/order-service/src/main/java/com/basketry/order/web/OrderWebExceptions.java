package com.basketry.order.web;

/** The web layer's own refusals, each one answer (OrderExceptionHandler). */
final class OrderWebExceptions {

    private OrderWebExceptions() {
    }

    /** 400: a parameter or path value with the wrong form. Never quotes the value. */
    static class BadRequest extends RuntimeException {

        BadRequest(String detail) {
            super(detail);
        }
    }

    /** 403, no code: the token lacks what this needs (BUYER to check out, MERCHANT for view=merchant, Admin). */
    static class Forbidden extends RuntimeException {

        Forbidden(String detail) {
            super(detail);
        }
    }

    /** 404: no such order, or, for a non-admin, not one of theirs. */
    static class NotFound extends RuntimeException {

        NotFound() {
            super("No order has this reference.");
        }
    }
}
