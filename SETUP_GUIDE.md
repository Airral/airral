# AIRRAL HR Portal - Complete Setup Guide

## Project Overview
AIRRAL is a comprehensive HR/Recruitment Portal system with:
- **Backend**: Java/Gradle Spring Boot (port 8080)
- **Frontend**: Nx monorepo with 4 Angular portals
- **Database**: PostgreSQL
- **Authentication**: JWT-based with RBAC

---

## ✅ What's Been Created

### Backend Structure (airral-backend/)
✅ **Core Configuration**
- `build.gradle` - Gradle dependencies and Spring Boot setup
- `settings.gradle` - Gradle settings
- `gradle.properties` - Java and Gradle configuration
- `application.yml` - Spring application configuration
- `src/main/resources/db/migration/` - Flyway database migrations

✅ **Security & Auth**
- `JwtTokenProvider.java` - JWT token generation and validation
- `JwtAuthenticationFilter.java` - JWT filter middleware
- `SecurityConfig.java` - Spring Security configuration
- `CorsConfig.java` - CORS configuration for frontends

✅ **Core Entities** (JPA)
- `User.java` - User entity with roles
- `Role.java` - Role entity
- `Job.java` - Job listing entity
- `Application.java` - Job application entity
- `Interview.java` - Interview entity

✅ **DTOs** (Request/Response)
- `LoginRequest.java`, `RegisterRequest.java`
- `CreateJobRequest.java`, `SubmitApplicationRequest.java`
- `AuthResponse.java`, `JobResponse.java`, `ApplicationResponse.java`, `UserResponse.java`

✅ **Services**
- `AuthService.java` - Authentication logic
- `JobService.java` - Job management
- `ApplicationService.java` - Application processing

✅ **Controllers** (REST API)
- `AuthController.java` - Login/Register endpoints
- `JobController.java` - Job CRUD endpoints
- `ApplicationController.java` - Application endpoints

✅ **Exception Handling**
- `GlobalExceptionHandler.java` - Centralized error handling
- Custom exceptions: `ResourceNotFoundException`, `UnauthorizedException`, `ValidationException`

✅ **Database**
- `V1__Initial_Schema.sql` - Database schema
- `V2__Seed_Data.sql` - Sample data (test users, jobs)

### Frontend Structure (airral-frontend/)
✅ **Configuration**
- `package.json` - NPM dependencies (Angular, Nx, RxJS)
- `nx.json` - Nx workspace configuration
- `tsconfig.json` - TypeScript configuration

✅ **Shared Libraries**
1. **@airral/shared-types** - TypeScript interfaces
   - `auth.types.ts` - User, AuthResponse, LoginRequest
   - `job.types.ts` - Job, JobStatus
   - `application.types.ts` - Application, ApplicationStatus
   - `user.types.ts` - UserProfile
   - `common.types.ts` - Common types and enums

2. **@airral/shared-auth** - Authentication
   - `auth.service.ts` - Authentication service
   - `token.service.ts` - Token management
   - `auth.guard.ts` - Authentication guard
   - `role.guard.ts` - Role-based guard

3. **@airral/shared-api** - API Client
   - `api-client.service.ts` - HTTP client with error handling
   - `auth-api.service.ts` - Auth API endpoints
   - `job-api.service.ts` - Job API endpoints
   - `application-api.service.ts` - Application API endpoints

4. **@airral/shared-utils** - Utilities
   - `constants.ts` - API URL, roles, statuses
   - `formatters.ts` - Date/text formatting functions
   - `validators.ts` - Email, password, phone validation
   - `date-helpers.ts` - Date utility functions

5. **@airral/shared-ui** - UI Components
   - `navbar.component` - Navigation bar
   - `footer.component` - Footer
   - `header.component` - Header

✅ **4 Portal Applications**
1. **Website Portal** (port 4200)
   - Public job listings
   - Company information

2. **Applicant Portal** (port 4201)
   - User profile management
   - Job search and application
   - Application tracking

3. **HR Portal** (port 4202)
   - Job management
   - Application review
   - Interview scheduling
   - Candidate tracking

4. **Admin Portal** (port 4203)
   - User management
   - System configuration
   - Roles and permissions
   - Audit logs

---

## 🚀 Next Steps to Get Running

### 1. Backend Setup

#### Prerequisites
- Java 17+ installed
- PostgreSQL 14+ running
- Gradle (included via gradlew)

#### Create Database
```bash
createdb airral_db
```

#### Update Database Credentials
Edit `airral-backend/src/main/resources/application.yml`:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/airral_db
    username: postgres
    password: your_password
```

#### Run Backend
```bash
cd airral-backend
./gradlew bootRun
```

Backend will start at: `http://localhost:8080`
Swagger UI: `http://localhost:8080/swagger-ui.html`

### 2. Frontend Setup

#### Prerequisites
- Node.js 18+ and npm 9+

#### Install Dependencies
```bash
cd airral-frontend
npm install
```

#### Run Frontends
```bash
# Option 1: Run all portals
npm start

# Option 2: Run specific portal
npm run website
npm run applicant-portal
npm run hr-portal
npm run admin-portal
```

Portals will be available at:
- Website: `http://localhost:4200`
- Applicant Portal: `http://localhost:4201`
- HR Portal: `http://localhost:4202`
- Admin Portal: `http://localhost:4203`

### 3. Test Users
Login with these test credentials (from seed data):

| Email | Password | Role |
|-------|----------|------|
| admin@airral.com | admin123 | ADMIN |
| hr@airral.com | hr123 | HR |
| applicant@airral.com | applicant123 | APPLICANT |

---

## 📊 Project Structure Summary

```
/Users/HXS0302/IdeaProjects/airral/
├── AIRRAL_HR_PORTAL_ARCHITECTURE.md      # Architecture documentation
├── airral-backend/                       # Java/Gradle backend
│   ├── build.gradle
│   ├── src/main/java/com/airral/hrportal/
│   │   ├── config/                       # Spring configurations
│   │   ├── controller/                   # REST controllers
│   │   ├── service/                      # Business logic
│   │   ├── repository/                   # Database access
│   │   ├── entity/                       # JPA entities
│   │   ├── dto/                          # Request/Response DTOs
│   │   ├── security/                     # JWT & Security
│   │   └── exception/                    # Error handling
│   └── src/main/resources/
│       ├── application.yml               # Configuration
│       └── db/migration/                 # Database migrations
│
└── airral-frontend/                      # Nx Angular monorepo
    ├── package.json
    ├── nx.json
    ├── apps/
    │   ├── website/                      # Website portal
    │   ├── applicant-portal/             # Applicant portal
    │   ├── hr-portal/                    # HR portal
    │   └── admin-portal/                 # Admin portal
    └── libs/
        ├── shared-types/                 # TypeScript types
        ├── shared-auth/                  # Auth services
        ├── shared-api/                   # API client
        ├── shared-utils/                 # Utilities
        └── shared-ui/                    # UI components
```

---

## 📝 Key API Endpoints

### Authentication
```
POST   /api/auth/login      - Login with email/password
POST   /api/auth/register   - Register new applicant
```

### Jobs
```
GET    /api/jobs            - Get all jobs
GET    /api/jobs/open       - Get open jobs only
GET    /api/jobs/{id}       - Get job by ID
POST   /api/jobs            - Create job (HR/Admin only)
PUT    /api/jobs/{id}       - Update job (HR/Admin only)
DELETE /api/jobs/{id}       - Delete job (Admin only)
```

### Applications
```
POST   /api/applications                  - Submit application
GET    /api/applications/{id}             - Get application
GET    /api/applications/applicant/{id}   - My applications
GET    /api/applications/job/{id}         - Job applications
PUT    /api/applications/{id}/status      - Update status
```

---

## 🔐 Authentication Flow

1. User enters credentials in login form
2. Frontend calls `POST /api/auth/login` with email/password
3. Backend validates credentials and generates JWT token
4. Frontend stores token in sessionStorage
5. Frontend includes token in all subsequent requests: `Authorization: Bearer {token}`
6. Backend validates token in JwtAuthenticationFilter
7. Token expires after 24 hours (configurable)

---

## 📋 Test Data
- Admin user created for testing
- HR user created for testing  
- Applicant user created for testing
- 4 sample jobs created (3 OPEN, 1 DRAFT)

---

## 🛠️ Technology Stack

### Backend
- Java 17+
- Spring Boot 3.2.0
- Spring Security + JWT
- Spring Data JPA / Hibernate
- PostgreSQL 14+
- Flyway (database migrations)
- Gradle 8.x

### Frontend
- Angular 17+
- TypeScript 5+
- Nx 17+
- RxJS
- Angular Material
- Tailwind CSS

---

## ✨ Features Implemented

✅ **Backend**
- JWT authentication with token validation
- Role-Based Access Control (RBAC)
- Exception handling with custom exceptions
- Database schema with migrations
- API endpoint security
- CORS configuration

✅ **Frontend**
- Shared library architecture (Nx)
- Authentication service
- API client with error handling
- Type-safe DTOs
- Utility functions
- UI components (navbar, footer, header)
- 4 separate portal applications

---

## 📖 What to Work On Next

### Immediate Priorities:
1. **Complete Frontend Pages** - Add login, job listing, application forms for each portal
2. **API Integration** - Connect frontends to backend API
3. **User Management Service** - Implement admin user management
4. **Interview Service** - Add interview scheduling endpoints
5. **Email Notifications** - Implement email service for notifications

### Phase 2:
- Resume storage/parsing
- Advanced search and filtering
- Analytics and reporting
- Interview feedback forms
- Email notifications

---

Generated: April 22, 2026
Architecture Version: 1.0.0
