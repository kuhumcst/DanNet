FROM ubuntu:jammy-20221101

RUN apt-get clean && \
    apt-get update && \
    DEBIAN_FRONTEND=noninteractive \
    apt-get install --no-install-recommends -y \
    git \
    npm \
    tree \
    openjdk-17-jdk \
    curl \
    rlwrap \
    locales

# Fix emojis not showing up on HTML pages
# https://medium.com/quiq-blog/handling-emoticons-or-other-unicode-characters-beware-of-docker-images-f0f11673dac4
RUN locale-gen en_US.UTF-8
ENV LANG en_US.UTF-8
ENV LANGUAGE en_US:en
ENV LC_ALL en_US.UTF-8

# The recommended way of installing Clojure CLI tools
# https://clojure.org/guides/getting_started#_installation_on_linux
RUN curl -O https://download.clojure.org/install/linux-install-1.11.1.1139.sh
RUN chmod +x linux-install-1.11.1.1139.sh
RUN ./linux-install-1.11.1.1139.sh

WORKDIR /etc/dannet

# Install JS dependencies
COPY ./package.json /etc/dannet/package.json
COPY ./package-lock.json /etc/dannet/package-lock.json
RUN npm init -y
RUN npm install -g shadow-cljs
RUN npm install

# Fetch backend dependencies
COPY ./deps.edn /etc/dannet/deps.edn
RUN clojure -A:backend:build -Spath

# Fetch frontend dependencies
COPY ./shadow-cljs.edn /etc/dannet/shadow-cljs.edn
RUN shadow-cljs --aliases :frontend classpath

# Copy needed source files
COPY ./src /etc/dannet/src
COPY ./resources /etc/dannet/resources
COPY ./pages /etc/dannet/pages

# Create a release build of the frontend app
RUN shadow-cljs --aliases :frontend release app

# Build an uberjar for the backend
RUN clojure -T:build org.corfield.build/uber :lib dk.cst/dannet :main dk.cst.dannet.web.service :uber-file "\"dannet.jar\""

# Prepare the start script
COPY ./docker/dannet/start.sh /etc/dannet/start.sh
RUN chmod +x /etc/dannet/start.sh

# Switch to a non-root user
# NOTE: the ownership of the volumes must be set to group id 1024 on the host!
# https://medium.com/@nielssj/docker-volumes-and-file-system-permissions-772c1aee23ca
#RUN addgroup --gid 1024 dannet
#RUN adduser --disabled-password --gecos "" --force-badname --ingroup dannet dannet-user
#USER dannet-user

# Run the backend web service
ENTRYPOINT ["./start.sh"]
