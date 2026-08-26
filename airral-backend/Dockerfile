# Multi-stage build for production.
#
# The builder uses ./gradlew rather than a gradle base image so the Gradle
# version is pinned by the wrapper (8.14.2) exactly as it is locally and in CI.
# A gradle:X-jdkY image silently builds with a different Gradle than everyone
# else -- the same environment-drift that broke this build once already.
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /app

# Wrapper and build config first, so the dependency layer caches independently
# of source changes.
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle gradle.properties ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

COPY src ./src
RUN ./gradlew clean bootJar -x test --no-daemon

# ---------------------------------------------------------------------------
# Runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN groupadd -r airral && useradd -r -g airral airral

# bootJar leaves only the executable jar in build/libs (the -plain jar is not
# produced by that task), so this copies exactly one file.
COPY --from=builder /app/build/libs/*.jar app.jar
RUN chown -R airral:airral /app

USER airral

# Documentation only: Cloud Run injects PORT and the app binds ${PORT:8080}.
EXPOSE 8080

# No HEALTHCHECK: Cloud Run ignores it and runs its own startup probe, and the
# jre-jammy image has no curl, so the previous check could never have passed.

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", \
  "app.jar"]
