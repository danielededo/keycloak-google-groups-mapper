package com.lunatech.keycloak.mappers.google;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.directory.Directory;
import com.google.api.services.directory.DirectoryScopes;
import com.google.api.services.directory.model.Group;
import com.google.api.services.directory.model.Groups;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

class GoogleClient {

    private static final Logger LOG = Logger.getLogger(GoogleClient.class);

    static final int MAX_RESULTS = 200;

    private final Directory directory;

    /**
     * @param delegateUser user to impersonate through domain-wide delegation, or {@code null} to use the
     *                     Application Default Credentials as they are (e.g. a service account that holds
     *                     a Workspace admin role itself)
     */
    public GoogleClient(String applicationName, String delegateUser) throws IOException {
        this(new Directory.Builder(new NetHttpTransport(), new JacksonFactory(), credential(delegateUser)).setApplicationName(applicationName).build());
    }

    GoogleClient(Directory directory) {
        this.directory = directory;
    }

    private static HttpRequestInitializer credential(String delegateUser) throws IOException {
        List<String> scopes = List.of(DirectoryScopes.ADMIN_DIRECTORY_GROUP_READONLY);

        GoogleCredentials credentials = GoogleCredentials.getApplicationDefault().createScoped(scopes);
        if(delegateUser != null) {
            credentials = credentials.createDelegated(delegateUser);
        }

        return new HttpCredentialsAdapter(credentials);
    }

    /**
     * Returns the names of the groups the user is a member of.
     *
     * @param domains domains in which to look for groups; when empty, Google picks the customer of the
     *                user's own domain (original behaviour)
     */
    public List<String> getUsergroupNames(String userKey, List<String> domains) {
        if(domains.isEmpty()) {
            return new ArrayList<>(listGroupNames(userKey, null));
        }

        Set<String> names = new LinkedHashSet<>();
        for(String domain : domains) {
            names.addAll(listGroupNames(userKey, domain));
        }
        return new ArrayList<>(names);
    }

    private Set<String> listGroupNames(String userKey, String domain) {
        Set<String> names = new LinkedHashSet<>();
        String pageToken = null;
        try {
            do {
                Directory.Groups.List request = directory.groups().list()
                        .setUserKey(userKey)
                        .setMaxResults(MAX_RESULTS);
                if(domain != null) {
                    request = request.setDomain(domain);
                }
                if(pageToken != null) {
                    request = request.setPageToken(pageToken);
                }

                Groups page = request.execute();
                if(page == null) {
                    break;
                }
                if(page.getGroups() != null) {
                    page.getGroups().stream()
                            .map(Group::getName)
                            .filter(Objects::nonNull)
                            .forEach(names::add);
                }
                pageToken = page.getNextPageToken();
            } while(pageToken != null && !pageToken.isEmpty());
        } catch(GoogleJsonResponseException e) {
            // The exception message contains the request URL, which includes the user's email: never log it.
            String reason = e.getDetails() != null ? e.getDetails().getMessage() : e.getStatusMessage();
            LOG.errorf("Google Directory API groups.list failed (domain=%s, HTTP %d): %s",
                    describe(domain), e.getStatusCode(), reason);
            throw new GoogleGroupsLookupException(String.format(
                    "Could not fetch the user's Google groups (domain=%s, HTTP %d: %s)",
                    describe(domain), e.getStatusCode(), reason));
        } catch(IOException e) {
            LOG.errorf("Google Directory API groups.list failed (domain=%s): %s",
                    describe(domain), e.getClass().getName());
            throw new GoogleGroupsLookupException(String.format(
                    "Could not fetch the user's Google groups (domain=%s, %s)",
                    describe(domain), e.getClass().getSimpleName()));
        }
        return names;
    }

    private static String describe(String domain) {
        return domain != null ? domain : "<user's own domain>";
    }

    /**
     * Thrown when the Directory API lookup fails. It deliberately carries no cause, because the
     * underlying HTTP exception message contains the user's email address.
     */
    static class GoogleGroupsLookupException extends RuntimeException {
        GoogleGroupsLookupException(String message) {
            super(message);
        }
    }

}
