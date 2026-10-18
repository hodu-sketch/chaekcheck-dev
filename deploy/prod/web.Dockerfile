# Web image for the EC2 deployment (cycle 17): nginx with the web build (web/dist, `npm run build` first) and
# deploy/nginx/ec2-tunnel.conf (port 80 behind the Cloudflare Tunnel). Build context: the repository root.
# No RUN step, so building for arm64 needs no emulation.
FROM nginx:stable-alpine

COPY deploy/nginx/rate-limits.conf deploy/nginx/security-headers.conf deploy/nginx/chaekcheck-locations.conf /etc/nginx/chaekcheck/
COPY deploy/nginx/ec2-tunnel.conf /etc/nginx/conf.d/default.conf
COPY web/dist /usr/share/nginx/html

EXPOSE 80
