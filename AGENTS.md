# Repository Guidelines

## Project Structure & Module Organization
- `src/main/java`: Spring Boot code organized by feature (`contest`, `problem`, `submission`, `user`, `user/rank` with `solvedBucket`).
- `src/main/resources/templates`: Thymeleaf pages; `static`: assets.
- `src/test/java`: JUnit tests (see `WebApplicationTests`).
- Build files: `build.gradle`, `settings.gradle`, Gradle wrapper `gradlew` / `gradlew.bat`.

## Build, Test, and Development Commands
- Prerequisite: MySQL on `localhost:3306` (see `src/main/resources/application.properties`). Default `ddl-auto=create` resets schema.
- Build: `./gradlew build` (Linux/macOS) or `gradlew.bat build` (Windows).
- Test: `./gradlew test`.
- Run locally: `./gradlew bootRun`.

## Coding Style & Naming Conventions
- Java 17, 4-space indentation, standard Spring naming.
- Suffixes: `Controller`, `Service`, `Repository`, `Dto`. Entities use singular nouns.
- Prefer DTOs and interface projections over raw `Object[]`; keep SQL aliases matching projection getters.
- Repositories: data access only. Services: business logic with `@Transactional` boundaries.

## Testing Guidelines
- Framework: JUnit 5 + Spring Boot Test.
- Naming: `*Tests.java` under the mirrored package path.
- Unit/slice tests: `@DataJpaTest` for repositories; integration: `@SpringBootTest`.
- Run all tests with `./gradlew test`.

## Commit & Pull Request Guidelines
- Use Conventional Commits (e.g., `feat:`, `fix:`, `refactor:`, `docs:`) with imperative, concise subjects.
- PRs include: purpose, linked issues, manual test steps, screenshots for UI changes, and any DB notes.
- Keep changes focused and small; update or add tests alongside code changes.

## Security & Configuration Tips
- Do not commit secrets. Prefer env vars or `application-local.properties`; activate with `--spring.profiles.active=local`.
- Production: set `spring.jpa.hibernate.ddl-auto=validate` and reduce Hibernate SQL logging.

## Architecture Notes
- Spring MVC + Thymeleaf; feature-based packages.
- Ranking uses bucket table `solved_count_bucket`; rebuild via `RankService.rebuildSolvedBuckets()` when distribution changes.

