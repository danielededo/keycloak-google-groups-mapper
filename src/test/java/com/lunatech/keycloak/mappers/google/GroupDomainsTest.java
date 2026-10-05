package com.lunatech.keycloak.mappers.google;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GroupDomainsTest {

    @Test
    void parsesCommaSeparatedListTrimmingWhitespace() {
        assertEquals(List.of("a.example.com", "b.example.com"), GroupDomains.parse("a.example.com, b.example.com"));
    }

    @Test
    void ignoresEmptyElementsAndDuplicates() {
        assertEquals(List.of("a.example.com", "b.example.com"),
                GroupDomains.parse(" ,a.example.com,, b.example.com ,a.example.com, "));
    }

    @Test
    void parsesEmptyAndNullAsNoDomain() {
        assertEquals(List.of(), GroupDomains.parse(""));
        assertEquals(List.of(), GroupDomains.parse("   "));
        assertEquals(List.of(), GroupDomains.parse(null));
    }

    @Test
    void mapperConfigWinsOverSpiOption() {
        assertEquals(List.of("groups.example.com"), GroupDomains.resolve("groups.example.com", "spi.example.com"));
    }

    @Test
    void fallsBackToSpiOptionWhenMapperConfigIsEmpty() {
        assertEquals(List.of("spi.example.com"), GroupDomains.resolve(null, "spi.example.com"));
        assertEquals(List.of("spi.example.com"), GroupDomains.resolve("", "spi.example.com"));
        assertEquals(List.of("spi.example.com"), GroupDomains.resolve(" , ", "spi.example.com"));
    }

    @Test
    void resolvesToNoDomainWhenNothingIsConfigured() {
        assertEquals(List.of(), GroupDomains.resolve(null, null));
        assertEquals(List.of(), GroupDomains.resolve("", ""));
    }

}
