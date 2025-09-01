FROM golang:1.24-bookworm AS build

WORKDIR /etc/caddy

# Install xcaddy and compile a custom build of Caddy w/ rate limiting
RUN go install github.com/caddyserver/xcaddy/cmd/xcaddy@v0.4.4
RUN xcaddy build v2.9.0 --with github.com/RussellLuo/caddy-ext/ratelimit



FROM ubuntu:noble

RUN apt-get clean && \
    apt-get update && \
    DEBIAN_FRONTEND=noninteractive \
    apt-get install --no-install-recommends -y \
    ca-certificates

WORKDIR /etc/caddy

# Move over the binary from the builder image
COPY --from=build /etc/caddy/caddy /usr/bin/caddy

# Add the server configuration containing
COPY ./docker/caddy/Caddyfile /etc/caddy/Caddyfile

# Prepare the start script
COPY ./docker/caddy/start.sh /etc/caddy/start.sh
RUN chmod +x /etc/caddy/start.sh

ENTRYPOINT ["./start.sh"]
