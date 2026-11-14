## 1. Gateway route (owner: src worker)

- [x] 1.1 In `src/main/resources/application.yml`, route `notifications_api_route`: change `uri: lb://jbh-personal-finance` to `uri: lb://jbh-notification`. Path predicate and `RequestRateLimiter` filter unchanged. Depends on: none.

## 2. Docs (owner: leader, outside module `src`)

- [x] 2.1 `README.md` (line ~89), route table row for `/jbh-api/notifications/**`: target `lb://jbh-notification`. Depends on: none. Owner: leader.
- [x] 2.2 `CLAUDE.md` (line ~48), "Routing Strategy": notifications goes to `jbh-notification` (its own service, no longer a module inside finance). Depends on: none. Owner: leader.

## 3. Verification (manual, run by the user)

- [x] 3.1 Run the existing suite: `./gradlew test`. Depends on: 1.1.
- [ ] 3.2 Manual check from design.md "Test contract" (200 route reaches `jbh-notification`, 401 without token, 429 over limit). Depends on: 1.1 and `jbh-notification` running in Consul.

Tasks 1.1, 2.1 and 2.2 touch different files and can run in parallel. No unit tests, so no test blueprint group.

Size estimate: 3 files, ~6 changed lines (tests and docs included)
