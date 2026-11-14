# gateway-routing Specification

## Purpose
TBD - created by archiving change src-route-notifications-to-jbh-notification. Update Purpose after archive.
## Requirements
### Requirement: Notifications route targets jbh-notification
The gateway SHALL route requests matching `/jbh-api/notifications/**` to the Consul service `jbh-notification` through the `lb://jbh-notification` URI. The route SHALL keep the existing path predicate and the `RequestRateLimiter` filter. The route SHALL stay protected by the authorization filter.

#### Scenario: Authenticated request reaches jbh-notification
- **WHEN** a client sends a request with a valid access token to `/jbh-api/notifications/v1` and `jbh-notification` is registered in Consul
- **THEN** the gateway forwards the request to an instance of `jbh-notification`
- **AND** the request does not reach `jbh-personal-finance`

#### Scenario: Request without a token is rejected
- **WHEN** a client sends a request without a token to `/jbh-api/notifications/v1`
- **THEN** the gateway returns `401` and does not forward the request

#### Scenario: Rate limiter still applies
- **WHEN** one IP sends more requests per second to `/jbh-api/notifications/**` than the default limit of 10
- **THEN** the gateway rejects the extra requests with `429`

#### Scenario: No registered instance
- **WHEN** no instance of `jbh-notification` is registered in Consul
- **THEN** the gateway returns `503` for `/jbh-api/notifications/**`

