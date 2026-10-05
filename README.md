# Keycloak Google Groups Identity Provider Mapper

This is a Keycloak extension that fetches Google Groups membership information from a user that authenticates through Google. 

## Usage

Add the mapper to a Google (or OIDC) identity provider and pick a **Parent Group**: on every login the
user's Google groups are read from the Directory API (Admin SDK) and the user is put in the matching
subgroups of the parent group (created when missing), and removed from the subgroups they no longer belong to.

The mapper uses the _default_ authentication mechanism of the Google client libraries (Application
Default Credentials): set `GOOGLE_APPLICATION_CREDENTIALS` to the JSON key of a service account, or run
on a platform that provides credentials (e.g. GKE with Workload Identity). The credentials need the
`https://www.googleapis.com/auth/admin.directory.group.readonly` scope.

### Options

SPI options go in `keycloak.conf` (or the matching `KC_SPI_...` environment variables / `--spi-...` flags):

| SPI option | Required | Description |
| --- | --- | --- |
| `spi-identity-provider-mapper-google-groups-idp-mapper-service-account-user` | no | User to impersonate through domain-wide delegation, e.g. `admin@example.com`. Leave it unset when the service account itself holds a Workspace admin role that can read groups. |
| `spi-identity-provider-mapper-google-groups-idp-mapper-domain` | no | Default for the mapper's *Groups domain(s)* setting (see below). |
| `spi-identity-provider-mapper-google-groups-idp-mapper-application-name` | no | Application name sent to Google, defaults to `keycloak`. |

Mapper settings (in the admin console, on the identity provider's mapper):

| Setting | Description |
| --- | --- |
| Parent Group | All imported groups are created under this group. |
| Groups domain(s) | Comma separated list of Google domains in which to look for the user's groups, e.g. `groups.example.com` or `a.example.com, b.example.com`. |

The groups domain is resolved as follows: the mapper setting wins; if it is empty, the `domain` SPI
option is used; if that is not set either, the groups are looked up without a domain (the original
behaviour: Google uses the organization that owns the user's own email domain). With several domains,
the groups of each one are fetched and merged, without duplicates.

### Authentication modes

**Domain-wide delegation** (original mode): grant the service account domain-wide delegation for the
scope above and set `service-account-user` to a Workspace admin of your organization:

    spi-identity-provider-mapper-google-groups-idp-mapper-service-account-user=admin@example.com

**Service account with an admin role**: assign a Workspace admin role that can read groups (e.g. a
custom read-only Groups role) directly to the service account, and leave `service-account-user` unset.
The mapper then calls the Directory API as the service account itself. This works well with keyless
setups such as GKE Workload Identity, where no JSON key is needed.

### Users from another organization (external members)

When users sign in with Google accounts of several organizations, while the groups used for
permissions live in a single organization and contain external members, the default lookup fails: for
`user@other.example`, Google searches the organization of `other.example` and answers
`404 Domain not found`. Set the domain that holds the groups, and the lookup is done there instead:

    spi-identity-provider-mapper-google-groups-idp-mapper-domain=groups.example.com

(or fill *Groups domain(s)* in the mapper). A `customer` ID cannot be used for this: the Directory API
does not accept it together with `userKey`.

### Behaviour

- A user without groups is removed from all the subgroups of the parent group; the login goes on.
- Results are paginated (200 per page) and every page is read.
- If the Directory API fails, the error is logged (queried domain and HTTP status, without the user's
  email) and the login fails: the user is never synced with a partial list of groups.
- At startup the mapper logs whether domain-wide delegation is used and the default groups domain(s).

## Building

    mvn clean package

## Running locally

* Get a keycloak zip release, unpack it.
* Configure `keycloak.conf`, see *Usage*
* Run with `GOOGLE_APPLICATION_CREDENTIALS=<<path-to-json-credentials-file>> DEBUG=true DEBUG_SUSPEND=n ./kc.sh start-dev`

And then check out http://localhost:8080/. You can login with admin/admin.

## Releasing

We use the `maven-release-plugin` to publish to Github Packages, and then JReleaser to create a 'release' on GitHub.

Typically, you would run the following:

    mvn release:prepare
    mvn release:perform
    git checkout <<the release version>>
    JRELEASER_GITHUB_TOKEN=<<A GH Token>> mvn jreleaser:release

This is not really a great flow; properly combining these into one jrelease config would probably be better.