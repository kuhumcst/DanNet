FROM ubuntu:jammy-20221101

RUN apt-get clean && \
    apt-get update && \
    DEBIAN_FRONTEND=noninteractive \
    apt-get install --no-install-recommends -y \
    git \
    ca-certificates \
    golang-go

WORKDIR /etc/caddy

# Add Go to path
ENV GOROOT="/usr/lib/go"
ENV GOPATH="$HOME/go"
ENV PATH="$PATH:$GOROOT/bin:$GOPATH/bin"

# Install xcaddy and compile a custom build of Caddy w/ rate limiting
RUN go install github.com/caddyserver/xcaddy/cmd/xcaddy@latest
RUN xcaddy build --with github.com/RussellLuo/caddy-ext/ratelimit
RUN cp ./caddy /usr/bin/caddy

# Add the server configuration containing
COPY ./docker/caddy/Caddyfile /etc/caddy/Caddyfile

# Prepare the start script
COPY ./docker/caddy/start.sh /etc/caddy/start.sh
RUN chmod +x /etc/caddy/start.sh

ENTRYPOINT ["./start.sh"]
