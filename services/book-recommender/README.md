# Book recommender service

This is the server for Wave's **Book Recs** app. It is a standalone Go service,
not part of the APK or Wave's static GitHub Pages site.

## HTTP contract

`POST /v1/recommendations`, `Content-Type: application/json`, with an optional
`Authorization: Bearer <app-access-token>` header (required if a token is set on
the service). The only request field is `prompt`, a nonblank string of up to
1,200 Unicode code points; the HTTP body may be at most 8 KiB.

```json
{"prompt":"Give me eight books like Dune, with strange ecologies and political intrigue"}
```

`200 OK` returns `{"books":[{"isbn":"9780441007462","title":"Dune","author":"Frank Herbert"}]}`
with actual selections rather than this example. ISBNs are canonical ISBN-13
values. A returned title and author identify the Open Library work; the ISBN
is of an edition the catalog links to that work (not necessarily the first
edition or in the same language). The service asks for five by default or up
to 20 on request. It uses additional searches and retries an underfilled list,
but returns fewer rather than deliberately pad with unrelated works. The
bounded search and model judgment can miss relevant works.

Rejected prompts and valid requests without verified matches both return
`200 {"books":[]}`. Otherwise the service returns a fixed JSON code:

| Status | `error` |
| --- | --- |
| 400 | `invalid_request` |
| 401 | `unauthorized` |
| 404 | `not_found` |
| 405 | `method_not_allowed` |
| 415 | `unsupported_media_type` |
| 429 | `busy` |
| 503 | `service_unavailable` |

The service gives its upstream work a 45-second deadline. Clients and proxy
read timeouts should leave extra time (e.g. 60–90 seconds).

## Trust boundary

- A local filter catches common injection and encoded-reply requests. A
  separate OpenRouter call without tools either rejects the request or
  generates a bounded bibliographic search plan. Only this gate receives the
  raw user prompt.
- The selector sees the sanitized plan, a server-defined search tool taking an
  existing query index, and bounded Open Library title/author records. The
  service rejects unauthorized tool calls or malformed output; neither model
  can cause an arbitrary network call. Catalog results are treated as data,
  not instructions.
- The selector returns only per-request candidate IDs. The server resolves
  them to catalog records with valid ISBN checksums and printable title/author
  metadata, deduplicates works and editions, and sorts by title and ISBN to
  remove model control of order. Neither model text nor upstream errors are
  echoed. No requests, headers, prompts, or model replies are logged by the
  application.

These checks do **not** prove that every recommendation is relevant, that the
catalog's data is accurate, or that every attempt at covert communication is
rejected. Which real books appear and how many appear can still carry
information if the semantic gate is fooled. The offline tests exercise the
server's enforceable boundaries and use scripted model replies for semantic
gate cases. They are not evaluations of a live model's classification accuracy.

## Build and deploy

```sh
cd services/book-recommender
go vet ./...
go test -race ./...
CGO_ENABLED=0 go build -o /tmp/wave-book-recommender .
```

Run the binary on an **authorized server-side HTTPS host** behind TLS
termination with access to `openrouter.ai` and `openlibrary.org`. Inject
configuration using the host's own secret manager:

| Setting | Purpose |
| --- | --- |
| `OPENROUTER_API_KEY` | Required. The authorized OpenRouter credential, provisioned directly into the service environment, not CI, source, a setup file, or the APK. |
| `OPEN_LIBRARY_CONTACT` | Required. Operator email or URL, sent publicly in the catalog User-Agent. |
| `WAVE_BOOK_RECS_TOKEN` | A distinct random access token (32–256 non-whitespace characters) for the client and service. Distribute it via an authorized app setup flow, never in the APK. |
| `WAVE_BOOK_RECS_PRIVATE_INGRESS` | Set `true` to allow omitting the service access token only if ingress is private or already authenticated. |
| `WAVE_BOOK_RECS_LISTEN` | Defaults to `127.0.0.1:8080`; change only as needed for the trusted proxy/host. |
| `WAVE_BOOK_RECS_REQUESTS_PER_MINUTE` | Defaults to 12 per instance, with a short burst and at most four concurrent requests. |
| `OPENROUTER_MODEL` | Defaults to `openai/gpt-4.1-mini`. An override must support tools and strict JSON-schema output together and be available through a provider permitting `data_collection: deny`. |

Never use the Oak login or the OpenRouter key as the phone's access token.
This repo does not extract an Oak credential, integrate with any private Oak
storage, or deploy the command center. To use the owner's Oak OpenRouter
credential, the owner or authorized operator needs a supported secret-manager
integration that provisions it directly into this server process as
`OPENROUTER_API_KEY`. If there is no authorized mechanism for that credential,
provision a separately authorized Wave-only OpenRouter key with a spending cap.
The request goes to OpenRouter and its routed provider, and generated search
terms go to Open Library; configure ingress not to log prompts or authorization
headers. Add edge rate limiting and a provider budget cap. The in-process
catalog pacing targets one search per second; apply an aggregate provider limit
before scaling replicas.

The Actions workflow tests/builds without credentials; it does not deploy. To
verify a live launch after provisioning, configure Book Recs with the HTTPS
base URL and app-access token, then try a genre request (five results if enough
verified matches), an explicit eight-book request, an acrostic request and a
conditional-count reply request (both should return an empty list). Check
OpenRouter and catalog availability separately if the service returns 503.
