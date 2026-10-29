# Web file carrier only (cycle 27, ADR-0168). nginx config lives on the server (/srv/chaekcheck/nginx/),
# not in this image. Build context: the repository root.
FROM nginx:1.30.5-alpine

COPY web/dist /usr/share/nginx/html

EXPOSE 80
