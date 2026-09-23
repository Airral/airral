# cictus.com: plain static HTML behind nginx on Cloud Run. No build step.
FROM nginx:1.27-alpine
ENV PORT=8080
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY security-headers.conf /etc/nginx/security-headers.conf
COPY site/ /usr/share/nginx/html/
EXPOSE 8080
