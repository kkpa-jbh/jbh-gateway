## Context

Notifications moves from a module inside `jbh-personal-finance` to its own service `jbh-notification` (Consul name `jbh-notification`, port 7778, path `/jbh-api/notifications/v1`). The gateway holds one route for it, `notifications_api_route`, which points to `lb://jbh-personal-finance`. Only the target changes.

No interview ran. There is no interview agenda, no database change, and only one possible design.

## Decisions

- **D1: Change only `uri` of `notifications_api_route` to `lb://jbh-notification`.** Path predicate `Path=/jbh-api/notifications/**` and filter `RequestRateLimiter` (`#{@inMemoryRateLimiter}`, `#{@ipKeyResolver}`) stay. Rejected: a new route id or a second route; it adds config with no benefit.
- **D2: No code and no new `publicPaths` entry.** The path stays protected by default, so the JWT check still applies.
- **D3: Docs are updated by the leader in the same change.** `README.md` (route table row) and `CLAUDE.md` ("Routing Strategy") name `jbh-notification` as its own service.
- **D4: Out of scope.** `../jbh-deploy/README.md` (JBH-74 section 7), compose and Caddy changes, and the gap "notifications blocked only at Caddy".

## Responsibilities

none. No new class and no class gets a new job. The change is YAML config only.

## Database

No database change.

## Concurrency

none. No shared data, no async code of our own, no check-then-act. The route table is read at startup.

## Test contract

- **Test blueprint: does not apply.** The JBH-74 plan excludes unit tests. The change is one YAML value with no unit-testable logic.
- **Unit tests:** none planned.
- **Integration tests:** none planned. The existing suite (`src/test`) has no route test. It must still pass unchanged.
- **Manual check (expected values):** with `jbh-notification` registered in Consul:
  1. Authenticated `GET /jbh-api/notifications/v1` through the gateway: reaches `jbh-notification`, not `jbh-personal-finance`.
  2. Same request without a token: `401` with JSON body (`error_code`, `message`).
  3. More than 10 requests per second from one IP: extra requests get `429` (the rate limiter still applies).
  4. With `jbh-notification` not registered: `503`.

## Risks / Trade-offs

- **Deploy order:** after deploy, notification calls fail with `503` (no instance for `lb://jbh-notification`) until `jbh-notification` runs and is registered. Deploy after the section 2 service is up.
- **Rollback:** revert the one line.
