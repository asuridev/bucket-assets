# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

ContentMS: a Spring Boot microservice that stores/serves static content files in IBM Cloud
Object Storage, deployed on IBM Code Engine. Implements HU-211.md (spec transcribed from
screenshots — read it for the full request/response contract). Two endpoints:

- `POST /v1/save-content` — saves a file to COS under `/partnerId/Object`
- `GET /v1/content-loaded?context_url=<partnerId>/<file>` — returns the file as binary. No
  headers at all, in or out: `context_url` is its only input.

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

MinIO for local S3 testing: `podman-compose -f deploy/docker-compose.yaml up -d` (prefer
`podman-compose`, the Python one, over `podman compose` — see README §4 for why on Windows).
It auto-creates the bucket via a one-shot `minio-init` container.

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
  storage/          CosFileStorage — the only FileStorage adapter, always active
  configurations/   usecase/ (mediator wiring), storage/ (COS + policy config), logging/
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

### Config layering

Each profile's `application-<profile>.yaml` does nothing but `spring.config.import` fragments
from `src/main/resources/parameters/<profile>/{logging,management,storage}.yaml`, so environment
diffs are reviewable concept-by-concept rather than as one flat file. Profiles: `local`
(real SDK against local MinIO, HMAC signing, `MINIO_*` vars all defaulted), `develop`/`production`
(IBM COS via IAM — refuse to start without `COS_ENDPOINT`, `COS_API_KEY`,
`COS_SERVICE_INSTANCE_ID`, `COS_BUCKET_CMS_CONTENT`; see README §6 for the full variable table).
There is no in-memory store and no `storage.provider` switch: every profile talks to a real
object storage, and only `storage.auth-mode` (`hmac` vs `iam`) differs.

### Deliberate decisions where HU-211 was ambiguous (see README §8)

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
- The GET drops the three headers the HU declares mandatory (`correlation_id`, `request_id`,
  `_p`) and no longer echoes them. Two consequences worth knowing: the GET no longer isolates
  one partner from another (anyone who knows another partner id can read its files — the 401
  that would restore this is still unwired), and `CorrelationFilter.shouldNotFilter` skips that
  endpoint, so its log lines carry no correlation id. The POST is unchanged.

### Explicitly out of scope (not implemented)

Redis caching (`Components` collection), MongoDB `OutPutsUrls` (migration-temporary per the HU
itself), and auth/401 (error shape exists in the contract, but no issuer is wired — TBD whether
that's an OAuth2 resource server, the Code Engine gateway, or something else). The `FileStorage`
port is where a caching decorator would go if Redis caching is implemented later.
