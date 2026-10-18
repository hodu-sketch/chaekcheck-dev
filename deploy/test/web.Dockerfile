# Web image for the cycle 17 test deployment: nginx with the web build (web/dist, `npm run build` first) and the
# example config from deploy/nginx (local-test.conf: plain HTTP 8088, backend at backend-host:8080).
# Build context: the repository root.
FROM nginx:stable-alpine

COPY deploy/nginx/rate-limits.conf deploy/nginx/security-headers.conf deploy/nginx/chaekcheck-locations.conf /etc/nginx/chaekcheck/
COPY deploy/nginx/local-test.conf /etc/nginx/conf.d/default.conf
COPY web/dist /usr/share/nginx/html

EXPOSE 8088
