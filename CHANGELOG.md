# Changelog

All notable changes to this fork are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Version `0.8-fork.1`.

### Added

- Groups domain(s) setting on the mapper (`domain`) and `domain` SPI option: the user's groups are
  looked up in the given comma separated Google domains, which supports users whose accounts belong to
  another organization than the one owning the groups (external members). The mapper setting takes
  precedence over the SPI option; without either, the original lookup is used.
- Pagination of the Directory API results (200 per page, following `nextPageToken`).
- Unit tests (JUnit 5 + Mockito) for domain parsing and precedence, merging, pagination and empty results.

### Changed

- `service-account-user` is now optional: without it, the Application Default Credentials are used
  without domain-wide delegation (e.g. a service account holding a Workspace admin role, GKE Workload
  Identity).
- At startup the mapper logs (INFO) whether domain-wide delegation is used and the default groups domain(s).
- Directory API failures are logged with the queried domain and the HTTP status, without the user's
  email, and fail the login with a clear error message.
- Keycloak dependencies updated to 26.8.0.

### Fixed

- Users without any Google group no longer make the login fail with a `NullPointerException`; they are
  removed from the subgroups of the parent group.
