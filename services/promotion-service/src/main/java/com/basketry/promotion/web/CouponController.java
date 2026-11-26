package com.basketry.promotion.web;

import com.basketry.promotion.coupon.Coupon;
import com.basketry.promotion.coupon.CouponManagement;
import com.basketry.promotion.coupon.CouponRepository;
import com.basketry.promotion.coupon.CouponScope;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The coupon endpoints for the Merchant portal and Admin (FR-M10, FR-A9;
 * DESIGN.md Section 3, promotion-service). Every one needs a bearer JWT (the
 * coupon chain). The order of the answers: 401 from the chain; 403 for a
 * token that is neither Admin's nor a merchant's, before any body is read;
 * 400 for the body or the path; 403 for a merchant's scope or merchant id;
 * then 404 (Admin only) and 409.
 *
 * <p>The body arrives as bytes and is read here, with the application's own
 * JsonMapper (every setting of application.yml and StrictJsonConfig), as the
 * pricing endpoint does: when Spring reads it, a Jackson error reaches
 * Spring's DEBUG log with Jackson's message, which quotes the input. The path
 * id is a string read here too: Spring's conversion error quotes the value.
 */
@RestController
public class CouponController {

    static final String COLLECTION = "/coupons";
    static final String ITEM = "/coupons/{id}";

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;
    /** Far past any real page; it keeps page times size inside a long. */
    static final int MAX_PAGE = 99_999_999;

    /** A whole number above 0 that fits a long: at most 18 digits. */
    private static final Pattern ID = Pattern.compile("[0-9]{1,18}");
    /** A paging parameter: digits only, at most 9 (it then fits an int). */
    private static final Pattern NUMBER = Pattern.compile("[0-9]{1,9}");

    private final CouponManagement management;
    private final CouponRepository coupons;
    private final JsonMapper json;
    private final Clock clock;

    public CouponController(CouponManagement management, CouponRepository coupons, JsonMapper json, Clock clock) {
        this.management = management;
        this.coupons = coupons;
        this.json = json;
        this.clock = clock;
    }

    /**
     * A merchant creates its own MERCHANT coupon (the merchant id is its
     * token's; the same id may be sent, another is a 403, and so is
     * PLATFORM). Admin creates either scope, and gives a merchant coupon's
     * merchant id. 201 with Location; 409 COUPON_CODE_TAKEN.
     */
    @PostMapping(path = COLLECTION, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CouponResponse> create(@RequestBody(required = false) byte[] body,
                                                 @AuthenticationPrincipal Jwt jwt) {
        Caller caller = Caller.of(jwt);
        CreateCouponRequest.Valid valid = read(body).validate(clock.instant(), caller.admin());

        Long merchantId = valid.merchantId();
        if (!caller.admin()) {
            if (valid.scope() != CouponScope.MERCHANT
                    || (merchantId != null && merchantId != caller.merchantId())) {
                throw CouponAccessDeniedException.notItsOwnToCreate();
            }
            merchantId = caller.merchantId();
        }
        Coupon created = management.create(new Coupon(valid.code(), valid.scope(), merchantId, valid.discount(),
                valid.expiresAt()));
        return ResponseEntity.created(URI.create(COLLECTION + "/" + created.getId()))
                .body(CouponResponse.from(created, clock.instant()));
    }

    /**
     * Admin: every coupon. A merchant: its own merchant coupons only. Paging as
     * identity-service's GET /accounts: {@code page} from 0 (default 0),
     * {@code size} 1 to 100 (default 20), anything else a 400, never clamped;
     * in id order; a page past the end is empty. Both are read here as text:
     * Spring's conversion error quotes the value.
     */
    @GetMapping(path = COLLECTION, produces = MediaType.APPLICATION_JSON_VALUE)
    public CouponPage list(@RequestParam(required = false) String page, @RequestParam(required = false) String size,
                           @AuthenticationPrincipal Jwt jwt) {
        Caller caller = Caller.of(jwt);
        List<String> violations = new ArrayList<>();
        int pageNumber = number(page, 0, 0, MAX_PAGE);
        if (pageNumber < 0) {
            violations.add("page must be a whole number, 0 or more.");
        }
        int pageSize = number(size, DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE);
        if (pageSize < 0) {
            violations.add("size must be a whole number from 1 to " + MAX_PAGE_SIZE + ".");
        }
        if (!violations.isEmpty()) {
            throw new InvalidCouponRequestException(violations);
        }
        PageRequest request = PageRequest.of(pageNumber, pageSize, Sort.by("id").ascending());
        return CouponPage.from(caller.admin() ? coupons.findAll(request)
                : coupons.findByMerchantId(caller.merchantId(), request), clock.instant());
    }

    /** Admin: any coupon, 404 for a missing one. A merchant: its own only, 403 for any other or a missing one. */
    @GetMapping(path = ITEM, produces = MediaType.APPLICATION_JSON_VALUE)
    public CouponResponse read(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        Caller caller = Caller.of(jwt);
        long couponId = id(id);
        Coupon coupon = coupons.findById(couponId).filter(caller::mayRead).orElseThrow(() -> caller.admin()
                ? new CouponNotFoundException() : CouponAccessDeniedException.notItsOwnToSee());
        return CouponResponse.from(coupon, clock.instant());
    }

    private CreateCouponRequest read(byte[] body) {
        if (body == null || body.length == 0) {
            throw new UnreadableBodyException(CreateCouponRequest.UNREADABLE);
        }
        CreateCouponRequest request;
        try {
            request = json.readValue(body, CreateCouponRequest.class);
        } catch (JacksonException unreadable) {
            throw new UnreadableBodyException(CreateCouponRequest.UNREADABLE, CreateCouponRequest.FIELD_NAMES,
                    unreadable);
        }
        if (request == null) {
            // The body was the JSON literal null.
            throw new InvalidCouponRequestException(List.of("The body must be a JSON object."));
        }
        return request;
    }

    /** The parameter as a whole number from min to max, the default when it is absent, or -1 when it is not. */
    private static int number(String text, int absent, int min, int max) {
        if (text == null) {
            return absent;
        }
        if (!NUMBER.matcher(text).matches()) {
            return -1;
        }
        int value = Integer.parseInt(text);
        return value < min || value > max ? -1 : value;
    }

    private static long id(String id) {
        if (!ID.matcher(id).matches() || Long.parseLong(id) <= 0) {
            throw new InvalidCouponRequestException(List.of("id must be a whole number above 0."));
        }
        return Long.parseLong(id);
    }
}
