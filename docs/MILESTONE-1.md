# Milestone 1 — Repository Explorer

Initial JavaFX scaffold for opening a Git working tree, showing porcelain-v2 status and recent commits.

## Build
Requires JDK 27, Maven, and Git on PATH.

```bash
mvn clean install
mvn -pl git-app javafx:run
```

## Scope
- Maven reactor with five JPMS modules
- Git CLI via ProcessBuilder with virtual-thread I/O and a timeout
- Status parser, repository service and basic JavaFX explorer
- Background loading and manual refresh

## Limitations
This is an unverified scaffold; compilation and JavaFX launch have not yet been tested in CI. Bare repositories, unborn HEAD, large output streaming, cancellation, repository tabs, and advanced workflows remain future work.
