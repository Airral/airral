# Multi-stage build for frontend apps
FROM node:22.12.0 AS builder

WORKDIR /app

# Copy package files
COPY package.json yarn.lock ./
COPY tsconfig.base.json nx.json ./

# Copy applications and libraries
COPY apps ./apps
COPY libs ./libs

# Install dependencies
RUN corepack enable && yarn install --frozen-lockfile

# Build the specified app (passed as build arg)
ARG APP=website
RUN npx nx build $APP --configuration=production

# Nginx stage
FROM nginx:alpine

ARG APP=website

# Copy nginx config
COPY nginx.conf /etc/nginx/nginx.conf

# Copy built app to nginx
COPY --from=builder /app/dist/apps/$APP /usr/share/nginx/html

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
