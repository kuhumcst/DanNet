#!/bin/bash

tree /etc/dannet/bootstrap
java -Xmx1500m -jar dannet.jar --no-bootstrap

# Print error in case of failure
cat /tmp/clojure-*.edn
