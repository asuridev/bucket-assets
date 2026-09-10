# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**Branch `feature/only-cache`.** A cut-down application whose only purpose is to **validate the
Redis connection**: the service credential read from IBM Cloud Secrets Manager, the TLS handshake
against a self-signed CA, the ACL user, and the cache itself.

**There is no object storage here** — no COS, no MinIO, no uploads, no bucket policies. That is
`main`. One endpoint:

- `GET /v1/cache/{id}` — returns a random string for that `id`, cached in Redis. **Two calls with
  the same id must return the same string**; if they differ, the cache is not working. That
  equality is the whole test, and it is visible in the response — no need to inspect Redis.

Spring Boot 3.5.3, Java 21, Maven (wrapper only, no local Maven needed).

## Commands

```bash
./mvnw clean package                                          # build -> target/content-ms-1.0.0.jar
java -jar target/content-ms-1.0.0.jar --spring.profiles.active=local
```

PowerShell: same commands, but quote the `-D`/`--spring.profiles.active` arg.

No test suite exists yet (`src/test` does not exist).

Local infrastructure — **identical to `main`, deliberately**, so one stack serves both branches:

```bash
cd infra && ./up.sh redis ibm-secret-manager      # MinIO is not needed on this branch
```

It brings up **Redis on two ports** (6379 plaintext for Redis Commander and the healthcheck,
**6380 TLS** for the application, with ACL user `contentms`), a **WireMock stub of Secrets
Manager** on `:8090` serving the Redis service credential, and **Redis Commander** on `:8081`
(`admin`/`admin`). `up.sh` generates the self-signed CA and server certificate in
`infra/conf/redis-tls/` (needs `openssl`; not committed) and embeds that CA in the secret it
serves.

Service listens on `http://localhost:8080`; Swagger UI at `/swagger-ui.html`.

## Architecture

Hexagonal: `domain` → `application` → `infrastructure`, all under
`com.bnpparibas.cardif.cloud.contentms`. **`domain` and `application` never import Spring.**
They become beans via two custom annotations, `@DomainComponent` and `@ApplicationComponent`,
picked up by a filtered `@ComponentScan` in `UseCaseConfig`. When adding a domain or use-case
class, use these annotations, not `@Component`/`@Service`.

```
domain/
  values/ValueGenerator          The port: given an id, a value
  errors/                        Base hierarchy + InvalidUuidHeaderError
application/
  queries/GetCachedValueQuery
  usecases/GetCachedValueQueryHandler
  interfaces/                    Command/Query contracts the mediator dispatches against
infrastructure/
  values/RandomValueGenerator    Real adapter: a fresh UUID every call
  values/CachedValueGenerator    @Primary decorator with @Cacheable  <- what this branch validates
  rest/controllers/cache/v1/     The endpoint
  configurations/cache/          RedisCacheManager + TTL properties
  secrets/                       Secrets read at startup
  correlation/, web/             correlation_id into the MDC
```

Controllers depend only on `UseCaseMediator`, never on handlers directly.

### The cache

**The cache decorates the port, not the use case** — same decision as `main`. `CachedValueGenerator`
is `@Primary`, so `GetCachedValueQueryHandler` gets it without knowing it exists, and `domain` and
`application` contain nothing about Redis. `RandomValueGenerator` is named `randomValueGenerator`
(`BEAN_NAME`) and the decorator injects it by that qualifier.

- Cache name `contentms:cache` doubles as the Redis key prefix (`contentms:cache::42`), so
  `contentms:*` sweeps only this service.
- **A Redis failure degrades to a miss, never a 500** — `CacheConfig.errorHandler()` logs a WARN
  and the request falls through to the generator. On this branch that failure is *visible*: the
  endpoint still returns 200 but **with a different value each time**. On `main` the same failure
  is invisible from outside. `management.health.redis.enabled` is `false` in every profile, so the
  health check does not catch it either.
- `@Cacheable(sync = true)` collapses concurrent requests for the same id into one generation. It
  forbids `unless` and multiple cache names, and only fails at runtime with a 500.
- TTL is `cache.ttl-minutes` per profile (local 10, develop 60, production 1440).
- **`cache.enabled` is the only switch.** False removes the decorator *and* stops the Redis
  service credential from being read: with no cache there is no connection to configure.

### Secrets

Credentials come from **IBM Cloud Secrets Manager** at startup. Full write-up in
`secret-manager.md`; the fundamentals of the mechanisms in `credenciales-ibm-cloud.md`; the
step-by-step test and per-environment variables in `redis-instruction.md`.

- The seam is `SecretsEnvironmentPostProcessor`, an `EnvironmentPostProcessor` registered in
  `META-INF/spring.factories` (EPPs still go there in Boot 3 — only autoconfigurations moved to
  `AutoConfiguration.imports`).
- **This branch reads exactly one secret: the Redis `service_credentials`.** From it come host,
  port, ACL username, password, database and **the TLS CA**, mapped to `spring.data.redis.*` and
  `spring.ssl.bundle.pem.*`. It is the only place those properties are defined — the `cache.yaml`
  files do not declare them.
- **The `kv` path is intact but switched off**: `secrets.name` is commented out in every profile.
  Empty/absent name means "no kv secret" and none is read. Turning it on is uncommenting two lines
  and adding a `${VAR}` to a YAML — no Java.
- The certificate travels as `certificate_base64` and is registered as an **SSL bundle with the
  PEM inline**, so the TLS is resolved entirely through properties: no `RedisConnectionFactory`
  bean, no `LettuceClientConfigurationBuilderCustomizer`, no hand-built `KeyStore`. `CacheConfig`
  knows nothing about it.
- Registered with `addLast()`: **a real env var wins over the secret**.
- **A failure here refuses to start the app**, unlike the cache, which degrades to a miss.
- Read **once, at startup**. No refresh: rotating credentials means restarting the pod.
- **One auth mode only.** `IamAuthenticator` with `IBM_CLOUD_API_KEY`, the one credential that
  stays a plain env var — it is the key that opens the rest, so it cannot live inside what it
  opens.
- Local runs the **same adapter against a different URL** — the WireMock stub in `infra/`. Note
  WireMock rejects unknown top-level fields in a mapping file (`//` included): comments go in
  `metadata`.
- The SDK is `com.ibm.cloud:secrets-manager` + `sdk-core`, OpenAPI-generated.

### Config layering

Each profile's `application-<profile>.yaml` does nothing but `spring.config.import` fragments from
`src/main/resources/parameters/<profile>/{logging,management,cache,secrets}.yaml`, so environment
diffs are reviewable concept-by-concept rather than as one flat file. Profiles: `local` (against
the `infra/` stack), `develop`/`production` (real Secrets Manager — refuse to start without
`SECRETS_URL` and `IBM_CLOUD_API_KEY`).

### Not implemented here

Everything about object storage lives on `main`. This branch also has no auth/401 and no test
suite.
