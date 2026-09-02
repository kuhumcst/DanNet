#!/bin/bash

tree /etc/dannet/bootstrap

# Exit on OOM so Docker restarts the container; the heap dump lands in the
# mounted export volume. The Jena LP tabled-goal cache (default 524288) would
# otherwise eat a large share of the heap.
java -Xmx1500m \
     -XX:+ExitOnOutOfMemoryError \
     -XX:+HeapDumpOnOutOfMemoryError \
     -XX:HeapDumpPath=/etc/dannet/export \
     -Djena.rulesys.lp.max_cached_tabled_goals=32768 \
     -jar dannet.jar --no-bootstrap

# Print error in case of failure
cat /tmp/clojure-*.edn
