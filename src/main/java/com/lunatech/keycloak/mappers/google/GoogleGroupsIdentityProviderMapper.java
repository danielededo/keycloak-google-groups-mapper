package com.lunatech.keycloak.mappers.google;

import com.github.slugify.Slugify;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.broker.oidc.OIDCIdentityProviderFactory;
import org.keycloak.social.google.GoogleIdentityProviderFactory;
import org.keycloak.broker.provider.AbstractIdentityProviderMapper;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.models.*;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.provider.ProviderConfigProperty;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.function.Function.identity;

public class GoogleGroupsIdentityProviderMapper extends AbstractIdentityProviderMapper {
    private static final Logger LOG = Logger.getLogger(GoogleGroupsIdentityProviderMapper.class);

    protected static final List<ProviderConfigProperty> configProperties = new ArrayList<>();
    private static final String CONFIG_KEY_SERVICE_ACCOUNT_USER = "service-account-user";
    private static final String CONFIG_KEY_APPLICATION_NAME = "application-name";
    private static final String CONFIG_KEY_DOMAIN = "domain";

    private static final String MAPPER_MODEL_KEY_PARENT_GROUP = "parentGroup";
    static final String MAPPER_MODEL_KEY_DOMAIN = "domain";
    public static final String PROVIDER_ID = "google-groups-idp-mapper";

    private static final Set<IdentityProviderSyncMode> IDENTITY_PROVIDER_SYNC_MODES = new HashSet<>(Arrays.asList(IdentityProviderSyncMode.IMPORT, IdentityProviderSyncMode.FORCE));

    private final GoogleClientFactory googleClientFactory;
    private GoogleClient googleClient;
    private String spiDomain;
    private Slugify slugify;

    static {
        ProviderConfigProperty property;
        property = new ProviderConfigProperty();
        property.setName(MAPPER_MODEL_KEY_PARENT_GROUP);
        property.setLabel("Parent Group");
        property.setHelpText("All imported groups will be created under this parent group.");
        property.setType(ProviderConfigProperty.GROUP_TYPE);
        configProperties.add(property);

        property = new ProviderConfigProperty();
        property.setName(MAPPER_MODEL_KEY_DOMAIN);
        property.setLabel("Groups domain(s)");
        property.setHelpText("Comma separated list of Google domains in which to look for the user's groups, "
                + "e.g. groups.example.com. Needed when users sign in with accounts of another organization "
                + "than the one owning the groups. Leave empty to use the SPI option '" + CONFIG_KEY_DOMAIN
                + "' or, if that is not set either, the original behaviour (the user's own domain).");
        property.setType(ProviderConfigProperty.STRING_TYPE);
        configProperties.add(property);
    }

    /** Creates the {@link GoogleClient}; replaced in tests to avoid loading real Google credentials. */
    @FunctionalInterface
    interface GoogleClientFactory {
        GoogleClient create(String applicationName, String delegateUser) throws IOException;
    }

    public GoogleGroupsIdentityProviderMapper() {
        this(GoogleClient::new);
    }

    GoogleGroupsIdentityProviderMapper(GoogleClientFactory googleClientFactory) {
        this.googleClientFactory = googleClientFactory;
    }

    public void init(Config.Scope config) {
        // Optional: without it, the Application Default Credentials are used without domain-wide delegation.
        String serviceAccountUser = config.get(CONFIG_KEY_SERVICE_ACCOUNT_USER);
        if(serviceAccountUser != null && serviceAccountUser.isBlank()) {
            serviceAccountUser = null;
        }

        this.spiDomain = config.get(CONFIG_KEY_DOMAIN);

        String applicationName = config.get(CONFIG_KEY_APPLICATION_NAME, "keycloak");
        try {
            this.googleClient = googleClientFactory.create(applicationName, serviceAccountUser);
        } catch(IOException e) {
            throw new RuntimeException(e);
        }

        this.slugify = Slugify.builder().build();

        LOG.infof("Google groups mapper initialized (domain-wide delegation: %s, default groups domain(s): %s)",
                serviceAccountUser != null ? "enabled" : "disabled",
                GroupDomains.parse(spiDomain).isEmpty() ? "<none>" : String.join(", ", GroupDomains.parse(spiDomain)));
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String[] getCompatibleProviders() {
        return new String[]{
            OIDCIdentityProviderFactory.PROVIDER_ID,
            GoogleIdentityProviderFactory.PROVIDER_ID
        };
    }

    @Override
    public boolean supportsSyncMode(IdentityProviderSyncMode syncMode) {
        return IDENTITY_PROVIDER_SYNC_MODES.contains(syncMode);
    }

    @Override
    public String getDisplayCategory() {
        return "Google Workspace";
    }

    @Override
    public String getDisplayType() {
        return "Google Groups Importer";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return configProperties;
    }

    @Override
    public void importNewUser(KeycloakSession keycloakSession, RealmModel realmModel, UserModel userModel, IdentityProviderMapperModel identityProviderMapperModel, BrokeredIdentityContext brokeredIdentityContext) {
        updateUserGroups(keycloakSession, realmModel, userModel, identityProviderMapperModel);
    }

    @Override
    public void updateBrokeredUser(KeycloakSession keycloakSession, RealmModel realmModel, UserModel userModel, IdentityProviderMapperModel identityProviderMapperModel, BrokeredIdentityContext brokeredIdentityContext) {
        updateUserGroups(keycloakSession, realmModel, userModel, identityProviderMapperModel);
    }

    private void updateUserGroups(KeycloakSession keycloakSession, RealmModel realm, UserModel user, IdentityProviderMapperModel mapperModel) {
        GroupModel parentGroup = getParentGroup(keycloakSession, realm, mapperModel);

        List<String> domains = GroupDomains.resolve(mapperModel.getConfig().get(MAPPER_MODEL_KEY_DOMAIN), spiDomain);
        List<String> userGroupNames = googleClient.getUsergroupNames(user.getEmail(), domains);

        Set<String> targetGroups = userGroupNames.stream()
                .map(slugify::slugify)
                .collect(Collectors.toSet());

        HashSet<String> groupsToJoin = new HashSet<>(targetGroups);
        user.getGroupsStream().forEach(currentGroup -> {
            if(parentGroup.equals(currentGroup.getParent())) {
                if(targetGroups.contains(currentGroup.getName())) {
                    groupsToJoin.remove(currentGroup.getName());
                } else {
                    user.leaveGroup(currentGroup);
                }
            }
        });

        if(!groupsToJoin.isEmpty()) {
            Map<String, GroupModel> existingGroups = parentGroup.getSubGroupsStream()
                    .collect(Collectors.toMap(GroupModel::getName, identity()));

            groupsToJoin.forEach(groupName -> {
                GroupModel group = existingGroups.get(groupName);
                if(group == null) {
                    group = realm.createGroup(groupName, parentGroup);
                }
                user.joinGroup(group);
            });
        }
    }

    @Override
    public void updateBrokeredUserLegacy(KeycloakSession keycloakSession, RealmModel realmModel, UserModel userModel, IdentityProviderMapperModel identityProviderMapperModel, BrokeredIdentityContext brokeredIdentityContext) {}

    @Override
    public String getHelpText() {
        return "Adds the user to all groups that the user is a member of in Google";
    }

    public GroupModel getParentGroup(KeycloakSession keycloakSession, RealmModel realm, IdentityProviderMapperModel mapperModel) {
        String groupPath = mapperModel.getConfig().get(MAPPER_MODEL_KEY_PARENT_GROUP);
        if(groupPath == null) {
            throw new RuntimeException("No parent group configured.");
        }
        return KeycloakModelUtils.findGroupByPath(keycloakSession, realm, groupPath);
    }

}
