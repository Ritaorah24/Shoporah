# Shoporah

The e-commerce platform backend: Kotlin, Spring Boot, JDBI and Postgres, in the shape of qucoon-backend-template. Needs JDK 21.

## Run locally

1. Copy `src/main/resources/application-example.properties` to `application-dev.properties` in the same folder and fill it in. It is gitignored.
2. Run `./gradlew bootRun`. Flyway applies the migrations at startup, and the API docs are at http://localhost:8080/swagger-ui.html.

Routes are `/{stage}/...`, for example `/dev/products`. The Paystack webhook has no stage: `/webhook/paystack`.

## Test

1. Create a database whose name ends in `_test`. It is wiped and rebuilt on every run.
2. Copy `secrets.local.properties.example` to `secrets.local.properties` and fill it in. It is gitignored.
3. Run `./gradlew test`. No test calls Paystack, Cloudinary or a mail server.

## Responses

Every route answers HTTP 200 with `responseCode` and `responseMessage`, plus `data` when there is a result. `00` is success. The Paystack webhook is the exception: it keeps real HTTP statuses, because Paystack retries only on a failure status.

| Code | Meaning |
|---|---|
| `106` | Bad request, a failed validation or a broken business rule |
| `117` | The record already exists |
| `26` | Idempotency-Key already used for another order |
| `25` | Not found |
| `86` | Wrong email or password |
| `107` | Not allowed |
| `115` | Missing, invalid or expired access token |
| `22` | Something went wrong, including a provider that could not be reached |
