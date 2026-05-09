#!/bin/bash

# Airral Backend Quick Start Script

echo "🚀 Starting Airral Backend..."
echo ""

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Check if PostgreSQL is running
echo -e "${YELLOW}📊 Checking PostgreSQL...${NC}"
if pg_isready -q; then
    echo -e "${GREEN}✅ PostgreSQL is running${NC}"
else
    echo -e "${YELLOW}🔄 Starting PostgreSQL...${NC}"
    brew services start postgresql@14
    sleep 3
    
    if pg_isready -q; then
        echo -e "${GREEN}✅ PostgreSQL started successfully${NC}"
    else
        echo -e "${RED}❌ Failed to start PostgreSQL${NC}"
        exit 1
    fi
fi

# Create database if it doesn't exist
echo -e "${YELLOW}📊 Setting up database...${NC}"
if psql -lqt | cut -d \| -f 1 | grep -qw airral_db; then
    echo -e "${GREEN}✅ Database 'airral_db' already exists${NC}"
else
    echo -e "${YELLOW}🔄 Creating database 'airral_db'...${NC}"
    createdb airral_db
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✅ Database created successfully${NC}"
    else
        echo -e "${RED}❌ Failed to create database${NC}"
        exit 1
    fi
fi

echo ""
echo -e "${YELLOW}🔨 Building backend...${NC}"
cd airral-backend
./gradlew clean build

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✅ Build successful${NC}"
else
    echo -e "${RED}❌ Build failed${NC}"
    exit 1
fi

echo ""
echo -e "${YELLOW}🚀 Starting backend server...${NC}"
echo -e "${YELLOW}Backend will be available at: http://localhost:8080${NC}"
echo ""
echo -e "${GREEN}No default users are seeded.${NC}"
echo "Create your first organization + HR admin via:"
echo "  POST http://localhost:8080/api/auth/register"
echo "  Body fields: email, password, firstName, lastName, companyName, companyDomain"
echo ""
echo -e "${YELLOW}Press Ctrl+C to stop the server${NC}"
echo ""

export JWT_SECRET="mySuperLongDefaultJwtSecretKeyForDevelopmentOnly_ChangeMe_0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
./gradlew bootRun
