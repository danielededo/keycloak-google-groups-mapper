package com.lunatech.keycloak.mappers.google;

import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import com.google.api.services.directory.Directory;
import com.google.api.services.directory.model.Group;
import com.google.api.services.directory.model.Groups;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoogleClientTest {

    private static final String USER = "user@other.example";

    private Directory directory;
    private Directory.Groups groupsResource;
    private GoogleClient client;

    /** jboss-logging is routed to java.util.logging in tests (see the surefire configuration). */
    private final Logger julLogger = Logger.getLogger(GoogleClient.class.getName());
    private final List<LogRecord> logRecords = new ArrayList<>();
    private final Handler logHandler = new Handler() {
        @Override public void publish(LogRecord record) { logRecords.add(record); }
        @Override public void flush() {}
        @Override public void close() {}
    };

    @AfterEach
    void removeLogHandler() {
        julLogger.removeHandler(logHandler);
    }

    @BeforeEach
    void setUp() {
        julLogger.addHandler(logHandler);
        directory = mock(Directory.class);
        groupsResource = mock(Directory.Groups.class);
        when(directory.groups()).thenReturn(groupsResource);
        client = new GoogleClient(directory);
    }

    /** A groups.list request whose setters return itself, like the real builder-style request. */
    private Directory.Groups.List request(Groups... pages) throws IOException {
        Directory.Groups.List request = mock(Directory.Groups.List.class, RETURNS_SELF);
        if(pages.length > 0) {
            when(request.execute()).thenReturn(pages[0], Arrays.copyOfRange(pages, 1, pages.length));
        }
        return request;
    }

    private static Groups page(String nextPageToken, String... names) {
        Groups groups = new Groups().setNextPageToken(nextPageToken);
        if(names != null) {
            groups.setGroups(Arrays.stream(names).map(name -> new Group().setName(name)).toList());
        }
        return groups;
    }

    @Test
    void withoutDomainUsesOriginalLookup() throws IOException {
        Directory.Groups.List request = request(page(null, "Admins", "Developers"));
        when(groupsResource.list()).thenReturn(request);

        assertEquals(List.of("Admins", "Developers"), client.getUsergroupNames(USER, List.of()));

        verify(request).setUserKey(USER);
        verify(request).setMaxResults(GoogleClient.MAX_RESULTS);
        verify(request, never()).setDomain(anyString());
    }

    @Test
    void nullGroupsMeansNoGroups() throws IOException {
        Directory.Groups.List request = request(new Groups());
        when(groupsResource.list()).thenReturn(request);

        assertTrue(client.getUsergroupNames(USER, List.of()).isEmpty());
        assertTrue(client.getUsergroupNames(USER, List.of("groups.example.com")).isEmpty());
    }

    @Test
    void mergesAndDeduplicatesAcrossDomains() throws IOException {
        Directory.Groups.List first = request(page(null, "Admins", "Developers"));
        Directory.Groups.List second = request(page(null, "Developers", "Support"));
        when(groupsResource.list()).thenReturn(first, second);

        List<String> names = client.getUsergroupNames(USER, List.of("a.example.com", "b.example.com"));

        assertEquals(List.of("Admins", "Developers", "Support"), names);
        verify(first).setUserKey(USER);
        verify(first).setDomain("a.example.com");
        verify(second).setUserKey(USER);
        verify(second).setDomain("b.example.com");
    }

    @Test
    void followsNextPageTokenUntilTheLastPage() throws IOException {
        Directory.Groups.List request = request(
                page("token-1", "Admins"),
                page("token-2", "Developers"),
                page(null, "Support"));
        when(groupsResource.list()).thenReturn(request);

        List<String> names = client.getUsergroupNames(USER, List.of("groups.example.com"));

        assertEquals(List.of("Admins", "Developers", "Support"), names);
        verify(request, times(3)).execute();
        verify(request).setPageToken("token-1");
        verify(request).setPageToken("token-2");
        verify(request, times(3)).setDomain("groups.example.com");
        verify(request, times(3)).setMaxResults(GoogleClient.MAX_RESULTS);
    }

    @Test
    void failsWithoutLeakingTheEmailWhenTheApiFails() throws IOException {
        Directory.Groups.List request = request();
        GoogleJsonError details = new GoogleJsonError();
        details.setCode(404);
        details.setMessage("Domain not found.");
        HttpResponseException.Builder builder = new HttpResponseException.Builder(404, "Not Found", new HttpHeaders())
                .setMessage("404 Not Found\nGET https://admin.googleapis.com/admin/directory/v1/groups?userKey=" + USER);
        when(request.execute()).thenThrow(new GoogleJsonResponseException(builder, details));
        when(groupsResource.list()).thenReturn(request);

        GoogleClient.GoogleGroupsLookupException e = assertThrows(GoogleClient.GoogleGroupsLookupException.class,
                () -> client.getUsergroupNames(USER, List.of("groups.example.com")));

        assertTrue(e.getMessage().contains("groups.example.com"));
        assertTrue(e.getMessage().contains("404"));
        assertFalse(e.getMessage().contains(USER));
        assertEquals(null, e.getCause());

        LogRecord record = singleErrorRecord();
        assertTrue(record.getMessage().contains("domain=groups.example.com"), record.getMessage());
        assertTrue(record.getMessage().contains("HTTP 404"), record.getMessage());
        assertFalse(record.getMessage().contains(USER), record.getMessage());
        assertNull(record.getThrown());
    }

    @Test
    void failsOnNetworkErrorsWithoutLeakingTheEmail() throws IOException {
        Directory.Groups.List request = request();
        when(request.execute()).thenThrow(new SocketTimeoutException("Read timed out for userKey=" + USER));
        when(groupsResource.list()).thenReturn(request);

        GoogleClient.GoogleGroupsLookupException e = assertThrows(GoogleClient.GoogleGroupsLookupException.class,
                () -> client.getUsergroupNames(USER, List.of()));

        assertTrue(e.getMessage().contains("SocketTimeoutException"), e.getMessage());
        assertFalse(e.getMessage().contains(USER));
        assertNull(e.getCause());

        LogRecord record = singleErrorRecord();
        assertTrue(record.getMessage().contains("<user's own domain>"), record.getMessage());
        assertFalse(record.getMessage().contains(USER), record.getMessage());
        assertNull(record.getThrown());
    }

    @Test
    void doesNotFetchFurtherDomainsAfterAFailure() throws IOException {
        Directory.Groups.List failing = request();
        when(failing.execute()).thenThrow(new SocketTimeoutException());
        Directory.Groups.List second = request(page(null, "Admins"));
        when(groupsResource.list()).thenReturn(failing, second);

        assertThrows(GoogleClient.GoogleGroupsLookupException.class,
                () -> client.getUsergroupNames(USER, List.of("a.example.com", "b.example.com")));

        verify(second, never()).execute();
    }

    private LogRecord singleErrorRecord() {
        List<LogRecord> errors = logRecords.stream()
                .filter(r -> r.getLevel().intValue() >= Level.SEVERE.intValue())
                .toList();
        assertEquals(1, errors.size(), "expected exactly one ERROR log record");
        return errors.get(0);
    }

}
