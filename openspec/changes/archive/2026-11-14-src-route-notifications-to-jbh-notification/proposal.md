## Why

The notification module moves out of `jbh-personal-finance` into a new Quarkus service, `jbh-notification` (Consul name `jbh-notification`, port 7778, same path `/jbh-api/notifications/v1`). The gateway must send `/jbh-api/notifications/**` to the new service. This is section 6 of the JBH-74 plan.

## What Changes

- In `src/main/resources/application.yml`, route `notifications_api_route`: change `uri` from `lb://jbh-personal-finance` to `lb://jbh-notification`.
- The path predicate and the `RequestRateLimiter` filter stay the same.
- Docs (owner: leader): `README.md` route table and `CLAUDE.md` "Routing Strategy" name the new target.

## Non-goals

- No change to `../jbh-deploy/README.md` (the system map). Section 7 of JBH-74 updates it.
- No compose or Caddy change (section 7). The known gap "notifications blocked only at Caddy" stays as is.
- No change to `publicPaths`, JWT checks, or rate-limit beans.

## Capabilities

### New Capabilities
- `gateway-routing`: how the gateway maps public paths to backend services. This change adds the notifications route requirement.

### Modified Capabilities
None.

## Impact

- One line in `application.yml`. No code, no database, no new dependency.
- Deploy-order risk: after deploy, notification calls return 503 until `jbh-notification` runs and is registered in Consul. Deploy after the section 2 service is up. Rollback: revert the one line.
