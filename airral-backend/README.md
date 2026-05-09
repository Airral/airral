# AIRRAL Backend

Java/Gradle Spring Boot backend for the AIRRAL HR Portal system.

## Prerequisites

- Java 17+
- Gradle 8.x
- PostgreSQL 14+

## Getting Started

### 1. Database Setup

Create PostgreSQL database:
```bash
createdb airral_db
```

### 2. Configuration

Update `src/main/resources/application.yml` with your database credentials:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/airral_db
    username: your_username
    password: your_password
```

### 3. Build & Run

```bash
# Build
./gradlew build

# Run
./gradlew bootRun

# Run tests
./gradlew test
```

## API Documentation

Once running, access Swagger UI at: `http://localhost:8080/swagger-ui.html`

## Project Structure

```
src/main/java/com/airral/hrportal/
├── config/           # Spring configuration
├── security/         # JWT & security components
├── controller/       # REST endpoints
├── service/          # Business logic
├── repository/       # Database access
├── entity/           # JPA entities
├── dto/              # Request/Response DTOs
├── exception/        # Exception handling
├── util/             # Utility classes
└── enumeration/      # Enums
```

## Main Endpoints

- **Auth**: `POST /api/auth/login`
- **Jobs**: `GET /api/jobs`
- **Applications**: `POST /api/applications`
- **Users**: `GET /api/users` (Admin only)

For full API documentation, see AIRRAL_HR_PORTAL_ARCHITECTURE.md
