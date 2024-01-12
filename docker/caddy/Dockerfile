FROM golang:1.21.6-bookworm as build

WORKDIR /etc/caddy

# TODO: use fixed versions of these packages
# Install xcaddy and compile a custom build of Caddy w/ rate limiting
RUN go install github.com/caddyserver/xcaddy/cmd/xcaddy@latest
RUN xcaddy build --with github.com/RussellLuo/caddy-ext/ratelimit



FROM ubuntu:focal-20231211

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
