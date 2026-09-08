# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

ContentMS: a Spring Boot microservice that stores/serves static content files in IBM Cloud
Object Storage, deployed on IBM Code Engine. Implements HU-211.md (spec transcribed from
screenshots — read it for the full request/response contract). Two endpoints:

- `POST /v1/save-content` — saves a file to COS under `/partnerId/Object`
- `GET /v1/content-loaded?context_url=<partnerId>/<file>` — returns the file as binary.
  `context_url` is its only required input; `correlation_id` and `request_id` are accepted
  as optional headers (validated as UUIDs, echoed back when sent). No `_p`.

Spring Boot 3.5.3, Java 21, Maven (wrapper only, no local Maven needed).

## Commands

```bash
./mvnw clean package                                          # build -> target/content-ms-1.0.0.jar
./mvnw spring-boot:run -Dspring-boot.run.profiles=local        # run against local MinIO (needs the compose up)
java -jar target/content-ms-1.0.0.jar --spring.profiles.active=local
```

PowerShell: same commands, but quote the `-D`/`--spring.profiles.active` arg, e.g.
`.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"`.

No test suite exists yet (`src/test` is empty).

Local infrastructure: `podman-compose -f deploy/docker-compose.yaml up -d` (prefer
`podman-compose`, the Python one, over `podman compose` — see README §4 for why on Windows).
It brings up MinIO (S3, auto-creating the bucket via a one-shot `minio-init` container),
Redis (the GET cache; no volume, so every start is cold), a WireMock stub of IBM Cloud
Secrets Manager on `:8090` (see "Secrets" below) and Redis Commander on `:8081`
(`admin`/`admin`), a web UI over that cache — the only way to inspect it in DevX, where
there is no `exec` and no TCP 6379, only HTTP exposed under a subpath. The public URLs of
that environment are parameterised (`MINIO_PUBLIC_URL`, `REDIS_UI_BASE_PATH`,
`REDIS_UI_USER`/`_PASSWORD`); template in `deploy/.env.devx.example`, README §4.

Service listens on `http://localhost:8080`; Swagger UI at `/swagger-ui.html`.

## Architecture

Hexagonal: `domain` → `application` → `infrastructure`, all under
`com.bnpparibas.cardif.cloud.contentms`. **`domain` and `application` never import Spring.**
They become beans via two custom annotations, `@DomainComponent` and `@ApplicationComponent`,
picked up by a filtered `@ComponentScan` in `UseCaseConfig`. When adding a domain or use-case
class, use these annotations, not `@Component`/`@Service`.

```
domain/            Java puro
  errors/           DomainException + typed subclasses (each maps to an HTTP status)
  storage/          FileStorage port, ObjectKey, ContentTypes, StoredFile/StoredObject, BucketPolicy, StoragePolicies
application/        CQRS use cases, still zero-Spring
  commands/         SaveContentCommand
  queries/          GetContentLoadedQuery
  usecases/         The two handlers (SaveContentCommandHandler, GetContentLoadedQueryHandler)
  interfaces/       Command/Query/Handler contracts the mediator dispatches against
infrastructure/    All Spring lives here
  rest/             ContentV1Controller, ApiExceptionHandler, ErrorResponse
  storage/          CosFileStorage (the only adapter that talks to the provider) +
                    CachedFileStorage (@Primary Redis-caching decorator around it)
  configurations/   usecase/ (mediator wiring), storage/ (COS + policy config),
                    cache/ (Redis cache manager + TTL properties)
  secrets/          SecretsEnvironmentPostProcessor + IbmSecretsManagerSource
  correlation/      CorrelationContext + CorrelationFilter
```

Controllers depend only on `UseCaseMediator` (`infrastructure/configurations/usecase/`), never
on handlers directly. The mediator resolves the right handler from `UseCaseContainer` by the
runtime type of the Command/Query. Unlike a typical mediator, it does **not** open a
transaction — there is no database, so no transactional boundary to define. If persistence is
ever added, that boundary belongs in `UseCaseMediator`, not in the handlers.

Two IBM COS SDK detail worth knowing: `com.ibm.cos:ibm-cos-java-sdk` is a fork of the **AWS SDK
v1** (namespace `com.ibm.cloud.objectstorage.*`), not v2 — it's the only one that speaks IAM via
`BasicIBMOAuthCredentials`.

### The GET cache

`GET /v1/content-loaded` is cached in Redis, which is step 1 of HU-211. The seam is
`CachedFileStorage`, a decorator implementing the same `FileStorage` port and marked
`@Primary`, so the handlers get it without knowing it exists — `domain` and `application`
contain nothing about Redis. `CosFileStorage` is named `cosFileStorage` (`BEAN_NAME`) and
the decorator injects it by that qualifier.

Why decorate the port instead of caching the use case (which is what the reference project
`catalog-spring` does): **both** endpoints go through this port, so `upload`/`delete` evict
the key for free. Re-uploading the same `fileName` can never leave the GET serving the old
bytes. A cache hung off the GET handler would not see that write.

- Cache name `contentms:content` doubles as the Redis key prefix, so a key looks like
  `contentms:content::cmsContent/12345/image1.png` and `contentms:*` sweeps only this service.
- The cached value is `StoredFile` as-is (`byte[]` as base64 in JSON, ~33% overhead; the
  10 MB bucket cap keeps entries around 13 MB). The extra fields of the HU's `Components`
  model are derivable from the object key and nobody reads them.
- **A Redis failure degrades to a miss, never a 500** — `CacheConfig.errorHandler()` only
  logs a WARN and the request falls through to COS. Because that also swallows
  (de)serialization bugs, a cache that caches *nothing* looks healthy: verify with
  `redis-cli KEYS`, not with a 200. For the same reason `management.health.redis.enabled` is
  `false` in every profile — an unreachable cache must not make Code Engine restart the pod.
- `@Cacheable(sync = true)` collapses concurrent requests for the same file into one COS
  download. It forbids `unless` and multiple cache names, and only fails at runtime with a 500.
- 404s are never cached: `FileNotFoundError` is an exception, so nothing is stored.
- TTL is `cache.ttl-minutes` per profile (local 10, develop 60, production 1440) and
  `cache.enabled: false` removes the decorator entirely, restoring the pre-cache behaviour.

### Secrets

Credentials come from **IBM Cloud Secrets Manager** at startup, not from plain env vars.
Full write-up in `secret-manager.md`; the parts that matter when changing code:

- The seam is `SecretsEnvironmentPostProcessor`, an `EnvironmentPostProcessor` registered in
  `META-INF/spring.factories` (EPPs still go there in Boot 3 — only autoconfigurations moved
  to `AutoConfiguration.imports`). It fetches one `kv` secret and registers its keys as a
  `MapPropertySource` named `ibm-secrets-manager`.
- **The secret's keys are named exactly like the env vars the YAML already used**
  (`COS_API_KEY`, `COS_SERVICE_INSTANCE_ID`, `REDIS_PASSWORD`), so `${COS_API_KEY}` in
  `parameters/develop/storage.yaml` resolves on its own. `StorageProperties`, `CosConfig` and
  every YAML are untouched and know nothing about Secrets Manager. **Adding a new secret is a
  key in the secret plus a `${VAR}` in a YAML — no Java.** The reference project does the
  opposite (a typed `GetCredentialsCommand` bean each consumer injects), which is more
  explicit in a stack trace but needs code per secret.
- Registered with `addLast()`: **a real env var wins over the secret**. Deliberate escape
  hatch. It also means a leftover `COS_API_KEY` in a Code Engine deployment silently shadows
  the secret.
- **A failure here refuses to start the app**, unlike the cache, which degrades to a miss.
  There is a durable store behind the cache; there is nothing behind missing credentials but
  a 500 on the first request. Same stance as `CosConfig.requireConfigured`.
- Read **once, at startup**. No refresh: rotating credentials means restarting the pod.
- `secrets.enabled: false` skips the whole thing, restoring the pre-Secrets-Manager
  behaviour — same pattern as `cache.enabled`.
- An EPP runs **before the logging system exists**, so it takes a `DeferredLogFactory` in its
  constructor; an SLF4J logger there would swallow its lines. It also binds `secrets.enabled`
  separately and *first*, because binding the whole record would blow up on an unresolved
  `${SECRETS_URL}` even with the feature off — which would make the escape hatch useless.
- **Two auth modes**, switched by `secrets.auth-mode` (`apikey` | `container`) — same pattern
  as `storage.auth-mode`, one level up. `apikey` (the default) uses `IamAuthenticator` with
  `IBM_CLOUD_API_KEY`, the one credential that stays a plain env var. `container` uses
  `ContainerAuthenticator`: the SDK reads the compute-resource token Code Engine mounts in the
  pod and exchanges it against a trusted profile, so **no credential exists in the
  deployment**. Switching is one env var and a restart — no image, no code. The default is
  `apikey` deliberately: it is what already works, so a deployment that does nothing is
  unaffected.
- **No automatic fallback between modes**, on purpose. A `container`→`apikey` fallback would
  hide a misconfigured trusted profile (the migration never completes) and make the Secrets
  Manager audit log unattributable. A misconfigured mode refuses to start instead, naming the
  missing property — `IbmSecretsManagerSource.requireCredentials()`.
- `api-key` is `${IBM_CLOUD_API_KEY:}` (empty default) in develop/production: without it the
  `Binder` would blow up on an unresolved placeholder in `container` mode, where the variable
  must not exist. The fail-fast moved to `requireCredentials()`, which gives a better message.
- `container` mode **is rehearsable locally**: the SDK's cr-token exchange hits the same
  `POST /identity/token` the WireMock stub already serves, so pointing `SECRETS_CR_TOKEN_FILE`
  at `deploy/secrets-manager-stub/cr-token` exercises the whole path. WireMock's request
  journal (`/__admin/requests`) shows which `grant_type` was actually sent — that is the proof
  the mode is live, not the fact that it booted.
- Local runs the **same adapter against a different URL** — a WireMock stub in the compose
  (`deploy/secrets-manager-stub/`) serving both `POST /identity/token` and the v2 secret path.
  Same reasoning as MinIO vs COS. Note WireMock rejects unknown top-level fields in a mapping
  file (`//` included): comments go in `metadata`.
- **In local the secret is not decorative**: it carries `MINIO_ACCESS_KEY`/`MINIO_SECRET_KEY`,
  which is what `parameters/local/storage.yaml` actually signs with, so a wrong value in
  `secret-kv.json` fails the upload with a 503 — the cheapest proof the mechanism works. The
  `minioadmin` defaults in that YAML exist only for `SECRETS_ENABLED=false`. The `COS_*` keys
  are in the local secret purely so its shape matches the other environments.
- The SDK is `com.ibm.cloud:secrets-manager` + `sdk-core`, which shares **nothing** with the
  COS SDK above: that one is an AWS-SDK-v1 fork, this one is OpenAPI-generated.

### Config layering

Each profile's `application-<profile>.yaml` does nothing but `spring.config.import` fragments
from `src/main/resources/parameters/<profile>/{logging,management,storage,cache,secrets}.yaml`, so environment
diffs are reviewable concept-by-concept rather than as one flat file. Profiles: `local`
(real SDK against local MinIO, HMAC signing, `MINIO_*` vars all defaulted), `develop`/`production`
(IBM COS via IAM — refuse to start without `COS_ENDPOINT`, `COS_API_KEY`,
`COS_SERVICE_INSTANCE_ID`, `COS_BUCKET_CMS_CONTENT`; see README §6 for the full variable table).
There is no in-memory store and no `storage.provider` switch: every profile talks to a real
object storage, and only `storage.auth-mode` (`hmac` vs `iam`) differs.

### Deliberate decisions where HU-211 was ambiguous (see README §9)

- The GET response can't carry two bodies (HU tabulates both a `responseHeader` and the binary).
  Binary goes in the body; `returnCode`/`message` go out as HTTP headers instead.
- `partnerId` is optional in the JSON body but required by the storage key layout
  (`/partnerId/Object`). **On the POST**, resolution order is JSON `partnerId` → header `_p` →
  400 `PartnerIdRequiredError`. On the GET it comes from the `context_url` prefix instead
  (`ObjectKey.ofContextUrl`), so `PartnerIdRequiredError` can no longer be raised there.
- Path traversal guard on `context_url` was added despite not being in the HU: the value comes
  from the client, so an unvalidated `../otherPartner/x.pdf` would escape the partner space.
- The stored `Content-Type` comes from the `fileName` extension (`ContentTypes.resolve`),
  not from what the client declares — a `.svg` announced as `image/png` is still stored as
  `image/svg+xml`. The same `fileName` builds the object key, so metadata and key agree by
  construction and the GET can't serve an SVG labelled as PNG. The declared value is only a
  fallback for extensions the map doesn't know. No magic-byte sniffing: a PDF renamed to
  `.png` is stored as `image/png`.
- The GET does not require the three headers the HU declares mandatory. `correlation_id` and
  `request_id` are **optional** there: absent means the download still succeeds and the
  response carries neither (the filter does not generate one, unlike on the POST); present
  means the value must be a canonical UUID or the request fails 400 `INVALID_UUID_HEADER`, and
  a valid value is echoed back and traces that request's log lines. `_p` still does not exist
  on the GET, so the GET does not isolate one partner from another (anyone who knows another
  partner id can read its files — the 401 that would restore this is still unwired). The POST
  is unchanged: three mandatory headers, values taken as-is with no format check.
- `ApiExceptionHandler` is the **only** place that logs exceptions, and the level follows
  the HTTP status it is about to return: 4xx goes out at `INFO` **without a stack** (a 404
  for a file that was never uploaded is a contract response, not an incident — a sixty-line
  stack per miss buries the real failures and trips WARN-count alerts), while 5xx and the
  catch-all keep `ERROR` with the full stack. The request-shape handlers (missing header,
  malformed body, 413) log too, at `INFO`: they used to log nothing at all.
  A `@LogExceptions` aspect on the use-case handlers used to log the same exception a second
  time, with a stack, at `WARN`. It is gone, along with `spring-boot-starter-aop`, which
  nothing else needed — `@EnableCaching` proxies through `spring-context`, not aspectjweaver.
- UUID validation lives in `infrastructure/correlation/CorrelationIds`, deliberately split in
  two: `CorrelationFilter` is best-effort tracing and silently drops a malformed id (a filter
  runs outside the `DispatcherServlet`, so anything it throws never reaches
  `ApiExceptionHandler`), while `ContentV1Controller` enforces the contract and throws the
  typed `InvalidUuidHeaderError`. It matches a regex, not `UUID.fromString`, which silently
  accepts short forms like `1-1-1-1-1`.

### Explicitly out of scope (not implemented)

MongoDB `OutPutsUrls` (migration-temporary per the HU itself) and auth/401 (error shape exists
in the contract, but no issuer is wired — TBD whether that's an OAuth2 resource server, the Code
Engine gateway, or something else). Redis caching used to be listed here; it is implemented now —
see "The GET cache" above.
