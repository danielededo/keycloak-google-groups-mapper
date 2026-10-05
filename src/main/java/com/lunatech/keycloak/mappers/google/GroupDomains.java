package com.lunatech.keycloak.mappers.google;

import java.util.Arrays;
import java.util.List;

/**
 * Parses and resolves the Google domain(s) in which the groups of a user are looked up.
 */
final class GroupDomains {

    private GroupDomains() {}

    /**
     * Parses a comma separated list of domains, trimming whitespace and dropping empty elements.
     * Returns an empty list for {@code null} or blank input.
     */
    static List<String> parse(String value) {
        if(value == null) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(domain -> !domain.isEmpty())
                .distinct()
                .toList();
    }

    /**
     * Resolves the domains to query: the mapper config wins over the SPI option; if neither
     * yields a domain, the result is empty and the original lookup (by user key only) is used.
     */
    static List<String> resolve(String mapperValue, String spiValue) {
        List<String> mapperDomains = parse(mapperValue);
        if(!mapperDomains.isEmpty()) {
            return mapperDomains;
        }
        return parse(spiValue);
    }

}
