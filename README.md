# Airral - Complete ATS Platform

**Version:** 1.0  
**Status:** ✅ Production-Ready  
**Tech Stack:** Spring Boot (WebFlux) + Angular 19 + PostgreSQL

> A full-featured, multi-tenant Applicant Tracking System with enterprise capabilities for companies of all sizes.

---

## 🚀 Quick Start

### For New Users

```bash
# 1. Setup database
createdb airral_db

# 2. Start backend
cd airral-backend
./gradlew bootRun

# 3. Start frontend
cd airral-frontend
npm install
npx nx serve hr-portal

# 4. Login at http://localhost:4202
Email: sarah.hr@democompany.com
Password: password123
```

**👉 Read:** [SETUP_GUIDE.md](SETUP_GUIDE.md) for detailed setup instructions

---

## 📚 Documentation Index

### Getting Started

| Document | Purpose | When to Use |
|----------|---------|-------------|
| **[SETUP_GUIDE.md](SETUP_GUIDE.md)** | Installation & configuration | First time setup |
| **[COMPLETE_USER_GUIDE.md](COMPLETE_USER_GUIDE.md)** | Complete feature documentation | Learning the system |
| **[COMMON_WORKFLOWS.md](COMMON_WORKFLOWS.md)** | Step-by-step procedures | Day-to-day operations |
| **[API_REFERENCE_CARD.md](API_REFERENCE_CARD.md)** | API endpoints & examples | API integration |

### Architecture & Development

| Document | Purpose | When to Use |
|----------|---------|-------------|
| **[SYSTEM_ARCHITECTURE.md](SYSTEM_ARCHITECTURE.md)** | System design & diagrams | Understanding architecture |
| **[BACKEND_ARCHITECTURE.md](BACKEND_ARCHITECTURE.md)** | Backend technical details | Backend development |
| **[BACKEND_TESTING_GUIDE.md](BACKEND_TESTING_GUIDE.md)** | Testing procedures | Testing & QA |
| **[MULTI_TENANT_ORGANIZATION_DESIGN.md](MULTI_TENANT_ORGANIZATION_DESIGN.md)** | Multi-tenancy details | Multi-tenant features |

### Feature Documentation

| Document | Purpose | When to Use |
|----------|---------|-------------|
| **[BACKEND_PHASE1_COMPLETE.md](BACKEND_PHASE1_COMPLETE.md)** | Core features (Auth, Jobs, Apps) | Phase 1 features |
| **[BACKEND_PHASE2_COMPLETE.md](BACKEND_PHASE2_COMPLETE.md)** | Team & org features | Phase 2 features |
| **[BACKEND_PHASE3_COMPLETE.md](BACKEND_PHASE3_COMPLETE.md)** | Analytics & tracking | Phase 3 features |
| **[HR_PORTAL_SIMPLIFICATION_STRATEGY.md](HR_PORTAL_SIMPLIFICATION_STRATEGY.md)** | Tier system design | Understanding tiers |

### Quick Reference

| Document | Purpose | When to Use |
|----------|---------|-------------|
| **[QUICK_REFERENCE.md](QUICK_REFERENCE.md)** | Command cheat sheet | Quick lookups |
| **[BACKEND_STATUS.md](BACKEND_STATUS.md)** | Implementation summary | Feature overview |

---

## ✨ What's Included

### ✅ Full Backend API (11 Controllers, 50+ Endpoints)

```
✓ Authentication (Login, Register, JWT)
✓ Jobs Management (CRUD, Search, Filter)
✓ Applications (Submit, Track, Score)
✓ Interviews (Schedule, Calendar Export, Feedback)
✓ Offers (Create, Send, Accept/Decline)
✓ Team Management (Invite, Roles, Departments)
✓ Referrals (Submit, Approve, Convert)
✓ Analytics (Dashboard, Pipeline, KPIs)
✓ HR Encounters (Audit Trail)
✓ Activity Feed (Timeline)
```

### ✅ HR Portal (Complete Frontend)

```
✓ Login & Authentication
✓ Jobs Posting & Management
✓ Candidate Review & Tracking
✓ Interview Scheduling with Calendar Export
✓ Offer Management
✓ Dashboard with Metrics
✓ Pipeline Board (Kanban)
✓ Analytics & Reporting
✓ Team & Department Management
✓ Settings & Configuration
```

### ✅ Enterprise Features

```
✓ Multi-tenant Architecture
✓ Role-Based Access Control (5 roles)
✓ Tier-Based Feature Gating (3 tiers)
✓ JWT Authentication with Auto-Expiration
✓ ATS Scoring (0-100)
✓ Audit Trail (HR Encounters)
✓ Activity Timeline
✓ Manager Hierarchy
✓ Data Isolation by Organization
```

---

## 🎯 Use Cases

### I Want to...

| Goal | Start Here |
|------|-----------|
| **Set up the application** | [SETUP_GUIDE.md](SETUP_GUIDE.md) |
| **Learn how to use features** | [COMPLETE_USER_GUIDE.md](COMPLETE_USER_GUIDE.md) |
| **Post a job and hire someone** | [COMMON_WORKFLOWS.md](COMMON_WORKFLOWS.md#workflow-1-standard-hiring-process) |
| **Integrate with the API** | [API_REFERENCE_CARD.md](API_REFERENCE_CARD.md) |
| **Understand the architecture** | [SYSTEM_ARCHITECTURE.md](SYSTEM_ARCHITECTURE.md) |
| **Add a new feature** | [COMPLETE_USER_GUIDE.md](COMPLETE_USER_GUIDE.md#adding-a-new-feature) |
| **Troubleshoot an issue** | [COMPLETE_USER_GUIDE.md](COMPLETE_USER_GUIDE.md#troubleshooting) |
| **Review test data** | [BACKEND_TESTING_GUIDE.md](BACKEND_TESTING_GUIDE.md) |

---

## 🏗️ Architecture Overview

```
Frontend (Angular 19)          Backend (Spring Boot)       Database
─────────────────────         ────────────────────        ───────────
HR Portal (4202)              REST API (8080)             PostgreSQL
Applicant Portal (4201)   ←→  WebFlux + R2DBC        ←→  12 Tables
Admin Portal (4200)           JWT Auth                    Multi-tenant
Website (4203)                Multi-tenancy
```

**Detail:** See [SYSTEM_ARCHITECTURE.md](SYSTEM_ARCHITECTURE.md)

---

## 📊 System Capabilities

### Complete Hiring Workflow

```
Post Job → Receive Applications → Review Candidates → 
Schedule Interviews → Submit Feedback → Create Offer → 
Send to Candidate → Candidate Accepts → Mark as Hired
```

### Multi-Tenancy

- Every organization has isolated data
- JWT token carries organization context
- Automatic filtering on all queries
- Platform admins can access all orgs

### Three Tiers

| Tier | Price | Features |
|------|-------|----------|
| **Quick Hire** | Free | Basic features, 5 jobs, 3 users |
| **Professional** | $199/mo | Full features, unlimited jobs & users |
| **Enterprise** | $499/mo | White label, API access, custom workflows |

---

## 🔐 Default Test Accounts

```
HR Manager:
  Email: sarah.hr@democompany.com
  Password: password123
  
Manager:
  Email: mike.manager@democompany.com
  Password: password123
  
Employee:
  Email: emma.employee@democompany.com
  Password: password123
```

---

## 🛠️ Technology Stack

### Backend
- **Framework:** Spring Boot 3.2+ (WebFlux - Reactive)
- **Database:** PostgreSQL 14+ with R2DBC (non-blocking)
- **Security:** JWT (HS512) + BCrypt password hashing
- **Migrations:** Flyway
- **Build:** Gradle

### Frontend
- **Framework:** Angular 19 (Standalone Components)
- **Monorepo:** Nx
- **Reactive:** RxJS
- **Styling:** Tailwind CSS + Custom CSS
- **Type Safety:** Full TypeScript

### Features
- ✅ Reactive programming (WebFlux + RxJS)
- ✅ Non-blocking I/O (R2DBC)
- ✅ Multi-tenant architecture
- ✅ Role-based access control
- ✅ JWT with automatic expiration handling
- ✅ Type-safe APIs (TypeScript interfaces)

---

## 📈 Project Status

### ✅ Completed (Production-Ready)

- [x] Full backend API with 11 controllers
- [x] Complete database schema with migrations
- [x] HR Portal with all core features
- [x] Multi-tenant architecture
- [x] JWT authentication + token expiration
- [x] Type-safe TypeScript throughout
- [x] Comprehensive documentation

### 🚧 In Progress

- [ ] Email notifications
- [ ] Applicant Portal (basic structure exists)
- [ ] Admin Portal (basic structure exists)
- [ ] Resume parsing (AI)

### 📋 Planned

- [ ] LinkedIn OAuth integration
- [ ] Slack notifications
- [ ] Video interview integration
- [ ] Mobile app
- [ ] Advanced reporting
- [ ] Custom workflows

---

## 📞 Getting Help

### Common Questions

**Q: How do I start the app?**  
A: See [SETUP_GUIDE.md](SETUP_GUIDE.md)

**Q: How do I use feature X?**  
A: See [COMPLETE_USER_GUIDE.md](COMPLETE_USER_GUIDE.md)

**Q: How do I call the API?**  
A: See [API_REFERENCE_CARD.md](API_REFERENCE_CARD.md)

**Q: I'm getting 401 errors?**  
A: Token expired - login again. See [Troubleshooting](COMPLETE_USER_GUIDE.md#troubleshooting)

**Q: How does multi-tenancy work?**  
A: See [MULTI_TENANT_ORGANIZATION_DESIGN.md](MULTI_TENANT_ORGANIZATION_DESIGN.md)

### Documentation Path

```
New User Flow:
1. SETUP_GUIDE.md          (Get it running)
2. COMPLETE_USER_GUIDE.md  (Learn features)
3. COMMON_WORKFLOWS.md     (Daily operations)

Developer Flow:
1. SYSTEM_ARCHITECTURE.md  (Understand design)
2. BACKEND_ARCHITECTURE.md (Backend details)
3. API_REFERENCE_CARD.md   (API integration)
```

---

## 🎓 Learning Path

### Day 1: Setup & Basics
```
□ Read SETUP_GUIDE.md
□ Install dependencies
□ Start backend & frontend
□ Login and explore UI
□ Post a test job
```

### Day 2: Core Features
```
□ Read COMPLETE_USER_GUIDE sections 1-5
□ Review applications
□ Schedule an interview
□ Create an offer
□ Understand workflow
```

### Day 3: Advanced Features
```
□ Read COMMON_WORKFLOWS.md
□ Invite team member
□ Create department
□ Review analytics
□ Submit referral
```

### Week 2: Development
```
□ Read SYSTEM_ARCHITECTURE.md
□ Review API_REFERENCE_CARD.md
□ Test API endpoints
□ Understand multi-tenancy
□ Build integration
```

---

## 🚀 Next Steps

### For Users
1. ✅ Read [SETUP_GUIDE.md](SETUP_GUIDE.md)
2. ✅ Start the application
3. ✅ Read [COMPLETE_USER_GUIDE.md](COMPLETE_USER_GUIDE.md)
4. ✅ Try [COMMON_WORKFLOWS.md](COMMON_WORKFLOWS.md)

### For Developers
1. ✅ Review [SYSTEM_ARCHITECTURE.md](SYSTEM_ARCHITECTURE.md)
2. ✅ Understand [BACKEND_ARCHITECTURE.md](BACKEND_ARCHITECTURE.md)
3. ✅ Test with [API_REFERENCE_CARD.md](API_REFERENCE_CARD.md)
4. ✅ Follow [BACKEND_TESTING_GUIDE.md](BACKEND_TESTING_GUIDE.md)

### For Integrators
1. ✅ Study [API_REFERENCE_CARD.md](API_REFERENCE_CARD.md)
2. ✅ Review authentication flow
3. ✅ Test endpoints
4. ✅ Build integration

---

## 📄 License

MIT License - See LICENSE file for details

---

## 🙏 Acknowledgments

Built with:
- Spring Boot (VMware)
- Angular (Google)
- PostgreSQL
- Nx (Nrwl)

---

**🎉 You have a complete, production-ready ATS platform!**

Start with [SETUP_GUIDE.md](SETUP_GUIDE.md) to get running, then explore [COMPLETE_USER_GUIDE.md](COMPLETE_USER_GUIDE.md) to learn all the features.

**Questions?** Check the documentation index above to find the right guide.
