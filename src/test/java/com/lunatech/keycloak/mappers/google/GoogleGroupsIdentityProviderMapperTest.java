package com.lunatech.keycloak.mappers.google;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.Config;
import org.keycloak.models.GroupModel;
import org.keycloak.models.IdentityProviderMapperModel;
import org.keycloak.models.IdentityProviderSyncMode;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.provider.ProviderConfigProperty;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoogleGroupsIdentityProviderMapperTest {

    private static final String EMAIL = "user@other.example";
    private static final String PARENT_PATH = "/google";

    private GoogleClient googleClient;
    private String[] factoryArgs;
    private GoogleGroupsIdentityProviderMapper mapper;

    private KeycloakSession session;
    private RealmModel realm;
    private UserModel user;
    private GroupModel parent;
    private MockedStatic<KeycloakModelUtils> modelUtils;

    @BeforeEach
    void setUp() {
        googleClient = mock(GoogleClient.class);
        mapper = new GoogleGroupsIdentityProviderMapper((applicationName, delegateUser) -> {
            factoryArgs = new String[]{applicationName, delegateUser};
            return googleClient;
        });

        session = mock(KeycloakSession.class);
        realm = mock(RealmModel.class);
        user = mock(UserModel.class);
        when(user.getEmail()).thenReturn(EMAIL);
        parent = mock(GroupModel.class);
        modelUtils = mockStatic(KeycloakModelUtils.class);
        modelUtils.when(() -> KeycloakModelUtils.findGroupByPath(session, realm, PARENT_PATH)).thenReturn(parent);
    }

    @AfterEach
    void tearDown() {
        modelUtils.close();
    }

    private static Config.Scope spiConfig(Map<String, String> values) {
        Config.Scope config = mock(Config.Scope.class);
        when(config.get(anyString())).thenAnswer(inv -> values.get(inv.<String>getArgument(0)));
        when(config.get(anyString(), anyString()))
                .thenAnswer(inv -> values.getOrDefault(inv.<String>getArgument(0), inv.getArgument(1)));
        return config;
    }

    private static IdentityProviderMapperModel mapperModel(String domain) {
        IdentityProviderMapperModel model = new IdentityProviderMapperModel();
        Map<String, String> config = new HashMap<>();
        config.put("parentGroup", PARENT_PATH);
        if(domain != null) {
            config.put("domain", domain);
        }
        model.setConfig(config);
        return model;
    }

    private static GroupModel group(String name, GroupModel parentGroup) {
        GroupModel group = mock(GroupModel.class);
        when(group.getName()).thenReturn(name);
        when(group.getParent()).thenReturn(parentGroup);
        return group;
    }

    private void login(IdentityProviderMapperModel model) {
        mapper.updateBrokeredUser(session, realm, user, model, null);
    }

    // --- configuration ---

    @Test
    void exposesParentGroupAndDomainConfigProperties() {
        List<ProviderConfigProperty> properties = mapper.getConfigProperties();
        assertEquals(List.of("parentGroup", "domain"), properties.stream().map(ProviderConfigProperty::getName).toList());

        ProviderConfigProperty domain = properties.get(1);
        assertEquals("Groups domain(s)", domain.getLabel());
        assertEquals(ProviderConfigProperty.STRING_TYPE, domain.getType());
        assertTrue(domain.getHelpText().contains("Comma separated"));
    }

    @Test
    void keepsProviderIdAndSyncModes() {
        assertEquals("google-groups-idp-mapper", mapper.getId());
        assertTrue(mapper.supportsSyncMode(IdentityProviderSyncMode.IMPORT));
        assertTrue(mapper.supportsSyncMode(IdentityProviderSyncMode.FORCE));
        assertFalse(mapper.supportsSyncMode(IdentityProviderSyncMode.LEGACY));
    }

    @Test
    void initWithoutServiceAccountUserDisablesDelegation() {
        mapper.init(spiConfig(Map.of()));
        assertEquals("keycloak", factoryArgs[0]);
        assertNull(factoryArgs[1]);
    }

    @Test
    void initTreatsBlankServiceAccountUserAsMissing() {
        mapper.init(spiConfig(Map.of("service-account-user", "  ")));
        assertNull(factoryArgs[1]);
    }

    @Test
    void initWithServiceAccountUserKeepsDelegation() {
        mapper.init(spiConfig(Map.of("service-account-user", "admin@example.com", "application-name", "my-app")));
        assertEquals("my-app", factoryArgs[0]);
        assertEquals("admin@example.com", factoryArgs[1]);
    }

    @Test
    void initFailsWhenTheClientCannotBeCreated() {
        GoogleGroupsIdentityProviderMapper failing = new GoogleGroupsIdentityProviderMapper((applicationName, delegateUser) -> {
            throw new IOException("no credentials");
        });
        assertThrows(RuntimeException.class, () -> failing.init(spiConfig(Map.of())));
    }

    // --- domain precedence, end to end through the mapper ---

    @Test
    void mapperDomainWinsOverSpiDomain() {
        mapper.init(spiConfig(Map.of("domain", "spi.example.com")));
        when(user.getGroupsStream()).thenReturn(Stream.empty());

        login(mapperModel("a.example.com, b.example.com"));

        verify(googleClient).getUsergroupNames(EMAIL, List.of("a.example.com", "b.example.com"));
    }

    @Test
    void spiDomainIsUsedWhenMapperDomainIsEmpty() {
        mapper.init(spiConfig(Map.of("domain", "spi.example.com")));
        when(user.getGroupsStream()).thenReturn(Stream.empty());

        login(mapperModel(""));

        verify(googleClient).getUsergroupNames(EMAIL, List.of("spi.example.com"));
    }

    @Test
    void noDomainWhenNothingIsConfigured() {
        mapper.init(spiConfig(Map.of()));
        when(user.getGroupsStream()).thenReturn(Stream.empty());

        login(mapperModel(null));

        verify(googleClient).getUsergroupNames(EMAIL, List.of());
    }

    // --- group sync (unchanged behaviour) ---

    @Test
    void joinsExistingAndCreatesMissingSubgroupsWithSlugifiedNames() {
        mapper.init(spiConfig(Map.of()));
        when(googleClient.getUsergroupNames(EMAIL, List.of())).thenReturn(List.of("Dev Team", "Admins"));
        when(user.getGroupsStream()).thenReturn(Stream.empty());
        GroupModel devTeam = group("dev-team", parent);
        when(parent.getSubGroupsStream()).thenReturn(Stream.of(devTeam));
        GroupModel admins = group("admins", parent);
        when(realm.createGroup("admins", parent)).thenReturn(admins);

        login(mapperModel(null));

        verify(user).joinGroup(devTeam);
        verify(user).joinGroup(admins);
        verify(realm, never()).createGroup("dev-team", parent);
    }

    @Test
    void userWithoutGroupsLeavesOnlyTheSubgroupsOfTheParent() {
        mapper.init(spiConfig(Map.of()));
        when(googleClient.getUsergroupNames(EMAIL, List.of())).thenReturn(List.of());
        GroupModel imported = group("admins", parent);
        GroupModel unrelated = group("local", mock(GroupModel.class));
        when(user.getGroupsStream()).thenReturn(Stream.of(imported, unrelated));

        login(mapperModel(null));

        verify(user).leaveGroup(imported);
        verify(user, never()).leaveGroup(unrelated);
        verify(user, never()).joinGroup(any());
    }

    @Test
    void lookupFailureFailsTheLoginWithoutTouchingGroups() {
        mapper.init(spiConfig(Map.of()));
        GoogleClient.GoogleGroupsLookupException failure = new GoogleClient.GoogleGroupsLookupException("boom");
        when(googleClient.getUsergroupNames(anyString(), any())).thenThrow(failure);

        RuntimeException e = assertThrows(RuntimeException.class, () -> login(mapperModel("groups.example.com")));

        assertSame(failure, e);
        verify(user, never()).leaveGroup(any());
        verify(user, never()).joinGroup(any());
    }

}
