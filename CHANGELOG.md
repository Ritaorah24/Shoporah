# Changelog

## Template refactor: 2026-11-05

Branch: `refactor/template`

Rita, this reshapes the project to match `qucoon-backend-template`. It adds no features: the business rules you built are the same, including checkout, payments, refunds, coupons, stock and notifications. What changes is the structure, the URLs, the response format and how the app is configured.

### What you need to do

1. **Pull the branch:** `git fetch` then `git checkout refactor/template`.
2. **Create your local config:** copy `src/main/resources/application-example.properties` to `application-dev.properties` in the same folder, then fill in your values. It is gitignored. The app no longer reads `secrets.local.properties`.
3. **Keep `secrets.local.properties` for tests only:** it now needs only the keys in `secrets.local.properties.example`. `DB_URL` and the optional mail and Cloudinary values are no longer used there.
4. **Run the app:** `./gradlew bootRun`, on JDK 21.
5. **Update the Paystack dashboard:** the webhook URL is now `/webhook/paystack`.
6. **Update anything that calls the API,** such as Postman collections or a frontend. See [URLs](#urls) and [Responses](#responses).

Your database and admins stay as they are. No migration changed, so Flyway will accept your existing database. The V9 seed admin also stays.

### Config keys

| Before | Now, in `application-dev.properties` |
|---|---|
| `DB_URL` | `datasource.dev.url` |
| `DB_USERNAME` | `datasource.dev.username` |
| `DB_PASSWORD` | `datasource.dev.password` |
| `SEED_ADMIN_EMAIL` | `spring.flyway.dev.placeholders.seed_admin_email` |
| `SEED_ADMIN_PASSWORD` | `spring.flyway.dev.placeholders.seed_admin_password` |
| `JWT_SECRET` | `jwt.dev.secret` |
| `PAYSTACK_SECRET_KEY` | `paystack.dev.secret-key` |
| `PAYSTACK_CALLBACK_URL` | `paystack.dev.callback-url` |
| `MAIL_HOST` | `spring.mail.dev.host` |
| `MAIL_USERNAME` | `spring.mail.dev.username` |
| `MAIL_PASSWORD` | `spring.mail.dev.password` |
| `MAIL_FROM` | `notifications.dev.from` |
| `PASSWORD_RESET_URL` | `password-reset.dev.url` |
| `CLOUDINARY_CLOUD_NAME` | `cloudinary.dev.cloud-name` |
| `CLOUDINARY_API_KEY` | `cloudinary.dev.api-key` |
| `CLOUDINARY_API_SECRET` | `cloudinary.dev.api-secret` |

Each key is written once per stage. `StageAwarePropertyResolver` picks the set that matches `app.stage`, so `datasource.dev.url` becomes `datasource.url` when the stage is `dev`.

`MAIL_PORT` has no dev key. The port stays 2525, set in `application.properties`.

### URLs

| Before | Now |
|---|---|
| `/api/products` | `/dev/products` |
| `/api/customer/cart` | `/dev/customer/cart` |
| `/api/admin/orders` | `/dev/admin/orders` |
| `/api/webhooks/paystack` | `/webhook/paystack` |

Every route except the webhook starts with the stage. A request for any other stage, such as `/prod/products` on a dev server, gets HTTP 404 with `{"responseCode":"106","responseMessage":"Invalid stage."}`.

### Responses

Every route now answers HTTP 200, and the result is in `responseCode`:

```json
{ "responseCode": "00", "responseMessage": "Successful", "data": { } }
```

Check `responseCode`, not the HTTP status. The object your endpoint used to return is now inside `data`. Routes with nothing to return, which used to answer 204, leave `data` out.

| Before | Code now | Exception now |
|---|---|---|
| 200, 201, 202, 204 | `00` | |
| 400, 413, most 409s | `106` | `BadRequestException` |
| 409, duplicate email, SKU, name or code | `117` | `RecordAlreadyExistException` |
| 409, Idempotency-Key used for another order | `26` | `DuplicateTransactionException` |
| 404 | `25` | `UnableToLocateRecordException` |
| 401, wrong email or password | `86` | `IncorrectPasswordException` |
| 401, missing, invalid or expired token | `115` | Written by `JsonAuthenticationEntryPoint` |
| 403 | `107` | `AccessNotAllowedException` |
| 500, 502, 503 | `22` | `ApiErrorException`, or any unexpected error |

Your error messages are unchanged.

- **Validation errors:** these use code `106`, and the fields are now in `data.fieldErrors`.
- **Checkout and payment start:** these used 201 for new and 200 for a repeat. Now `responseMessage` tells you: `Order created` or `Payment started` when new, `Successful` when repeated.
- **The Paystack webhook is the exception:** it keeps real HTTP statuses (200, 400, 401, 502), because Paystack only retries when it gets a failure status.

### Project structure

| Before | Now |
|---|---|
| `dto/request` | `model/request` |
| `dto/response` | `model/response` |
| `model` | `model/entity` |
| `repository` | `repository/database`, with SQL in `repository/database/query` |
| `repository/api/paystack`, `cloudinary`, `email` | `repository/api` |
| 12 files in `exception` | `exception/CustomException.kt` and `GlobalExceptionHandler.kt` |

New folders are `filter` (stage check and request logging) and `utility` (`ApiUtil`, `LogRedactor`, SQL helpers).

### Code changes

- **Entities:** fields are nullable `var`s named after their columns, so `category.name` is now `category.categoryName`. JDBI maps rows with `mapTo<Category>()`. Columns that are required in the database use `!!` where they are read.
- **Repositories:** these now use JDBI. `DatabaseConfig` keeps JDBI inside the surrounding `@Transactional`, so a failed checkout still rolls back everything. `CheckoutRollbackTest` fails without this, so please keep it.
- **Settings:** `@Value` is replaced by typed classes in `config/AppProperties.kt`, such as `JwtProps` and `PaystackProps`. Add new settings there and to `application-example.properties`.
- **Logging:** `KotlinLogging` replaces SLF4J's `LoggerFactory`.
- **Request logging:** `RequestResponseLoggingFilter` logs each request and response with passwords and tokens masked. Customer emails and phone numbers do appear in these logs. It skips `/webhook`.
- **Renames:**
  - `PaystackGateway` is now `PaystackApi`
  - `CloudinaryImageStorage` is now `CloudinaryApi`
  - `OpenApiConfig` is now `SwaggerConfig`
  - `EmailExecutorConfig` is now `AsyncConfig`
- **Tests:** they now check response codes instead of HTTP statuses. Many test names still mention old statuses, such as "returns 409".
- **New tests:** `RouteProtectionTest` checks that every non-public route refuses a request with no token. `PaystackApiTest` also checks that a customer's reference stays safely encoded in the URL.

### Deliberately different from the template

If you compare with the template, these differences are on purpose. Please don't "fix" them.

| Stays this way | Why |
|---|---|
| Paystack calls are limited to 15 seconds | `STALE_START_SECONDS` and `UNCONFIRMED_GRACE_SECONDS` depend on it. A longer call could create a second payment page or send a refund twice. |
| Refunds use `OkHttpClient` directly, with no automatic retry | A refund needs the HTTP status to tell a clear refusal from an unknown outcome. |
| Image uploads use `OkHttpClient` directly | `ApiUtil` takes a `String` body, which would corrupt image bytes. |
| Flyway runs at startup | Your app relies on this for migrations. |
| V9 seeds the first admin, so there is no `AdminBootstrapService` | Your admins already exist, and editing V9 would stop Flyway starting on your database. |
| SMTP email stays, so there is no `MailUtil` or SendGrid | This is what you built. |
| Your notification scheduler stays, so there is no `ScheduledJobController` | Nothing would call that route locally, and emails would stop. |
| The Swagger UI stays as it was | The template's own pages exist only for Lambda. |
| No Lambda or deployment files | The project runs locally only. |
| No audit log | There is no audit table, and adding one would be a new feature. |

### Dependencies added

- `io.github.oshai:kotlin-logging-jvm`
- `org.jdbi:jdbi3-core` and `org.jdbi:jdbi3-kotlin`
- `com.squareup.okhttp3:okhttp`
- `org.json:json`

### Testing

- The last full test run passed 342 of 342. After it, only comments, the README and two email-not-configured messages changed, and the 14 tests re-run after that passed.
- The real app was run through checkout, payment, cancel and refund against a local stand-in for Paystack.
- It has not been run against the real Paystack, Cloudinary or a mail server, so please try a test-mode payment, an image upload and an email once your config is in place.
