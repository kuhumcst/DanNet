#!/bin/bash

tree /etc/dannet/bootstrap
java -jar -Xmx3g dannet.jar --no-bootstrap

# Print error in case of failure
cat /tmp/clojure-*.edn
