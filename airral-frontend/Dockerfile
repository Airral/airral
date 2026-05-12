# Multi-stage build for frontend apps
FROM node:22.12.0 AS builder

WORKDIR /app

# Copy package files
COPY package*.json ./
COPY tsconfig.base.json nx.json ./

# Copy applications and libraries
COPY apps ./apps
COPY libs ./libs

# Install dependencies
RUN npm ci

# Build the specified app (passed as build arg)
ARG APP=website
RUN npx nx build $APP --configuration=production

# Nginx stage
FROM nginx:alpine

ARG APP=website

# Copy nginx config
COPY airral-frontend/nginx.conf /etc/nginx/nginx.conf

# Copy built app to nginx
COPY --from=builder /app/dist/apps/$APP /usr/share/nginx/html

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
