# Git Client

An open-source, cross-platform JavaFX Git GUI using the native Git command-line executable.

## Current state

Milestone 1 is an **unverified implementation scaffold**. It opens an existing Git working-tree repository and displays the current branch, changed files, and up to 100 recent commits. Git calls execute off the JavaFX application thread.

## Requirements

- JDK 27
- Maven
- Git on `PATH`

## Build and run

```bash
mvn clean install
mvn -pl git-app javafx:run
```

## Project modules

- `git-model`: immutable repository, file and commit records
- `git-cli`: native Git execution and porcelain status parsing
- `git-services`: repository operations
- `git-ui`: JavaFX repository explorer
- `git-app`: desktop application launcher

See [architecture](docs/ARCHITECTURE.md) and [Milestone 1 notes](docs/MILESTONE-1.md).

## Development status

The scaffold has not yet been compiled or run in a JavaFX environment. Tests, CI, robust cancellation, empty-repository handling, staging, and advanced workflows are next steps.
