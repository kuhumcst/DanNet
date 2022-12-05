#!/bin/bash

tree /etc/dannet/bootstrap
java -jar -Xmx3g dannet.jar

# Print error in case of failure
cat /tmp/clojure-*.edn
