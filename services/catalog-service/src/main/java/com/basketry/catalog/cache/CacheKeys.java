package com.basketry.catalog.cache;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The listing cache's keys (DESIGN.md Section 2, catalog-service). A listing
 * belongs to one scope: {@code all}, or {@code m:<merchantId>} when it is
 * filtered by merchant. Every page key carries its scope's version, so bumping
 * the version makes every page of the scope unreachable at once; the old pages
 * expire by their TTL. The {@code v2} prefix is the format of the stored page
 * ({@code v1} until the conflict flag became {@code stockRowFailed}):
 * a change of {@link CachedPage} gets a new prefix, so a new release never
 * reads an old release's pages.
 */
public final class CacheKeys {

    static final String PREFIX = "catalog:v2:";
    public static final String ALL = "all";

    private CacheKeys() {
    }

    public static String merchantScope(long merchantId) {
        return "m:" + merchantId;
    }

    /** The scope a listing reads: its merchant's, or {@link #ALL}. */
    public static String scopeOf(OptionalLong merchantId) {
        return merchantId.isPresent() ? merchantScope(merchantId.getAsLong()) : ALL;
    }

    public static String versionKey(String scope) {
        return PREFIX + "ver:" + scope;
    }

    /**
     * The key of one page. The category is free text, so it is URL-encoded:
     * a ':' in it can never make two different listings share a key.
     */
    public static String pageKey(String scope, long version, Optional<String> category, int page, int size) {
        String categoryPart = category.map(value -> "c=" + URLEncoder.encode(value, StandardCharsets.UTF_8))
                .orElse("c*");
        return PREFIX + "list:" + scope + ":" + version + ":" + categoryPart + ":p=" + page + ":s=" + size;
    }
}
