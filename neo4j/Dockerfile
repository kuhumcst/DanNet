# Using the official image as base
# https://github.com/neo4j/docker-neo4j-publish/blob/d42c3ac9cde66e2a1dcb3f667fe73878dbf2218c/4.1.1/community/Dockerfile
FROM neo4j:4.1

# Adding the neosemantics plugin for RDF
# https://github.com/neo4j-labs/neosemantics#installation
COPY neosemantics-4.1.0.0.jar ${NEO4J_HOME}/plugins/neosemantics-4.1.0.0.jar
RUN echo 'dbms.unmanaged_extension_classes=n10s.endpoint=/rdf' >> ${NEO4J_HOME}/conf/neo4j.conf

# Fixing a JVM-related bug that came up while importing RDF
# https://stackoverflow.com/questions/48836016/neo4j-jaxp00010001-the-parser-has-encountered-more-than-64000-entity-expansio
RUN echo 'dbms.jvm.additional=-Djdk.xml.entityExpansionLimit=0' >> ${NEO4J_HOME}/conf/neo4j.conf

# If queries run out of memory, default to simply exiting the JVM.
# When the Docker container is run with the --restart=always flag, this can be a
# useful failsafe. The browser interface stays functional after the restart.
# https://neo4j.com/developer/kb/recommendations-for-recovery-upon-out-of-memory-error/
RUN echo 'dbms.jvm.additional=-XX:+ExitOnOutOfMemoryError' >> ${NEO4J_HOME}/conf/neo4j.conf

# Increase the default memory limit from 512MB to 2GB.
# Some fairly harmless queries will crash the database using the default 512MB.
# https://neo4j.com/docs/operations-manual/current/docker/configuration/
# https://neo4j.com/docs/operations-manual/current/reference/configuration-settings/#config_dbms.memory.heap.max_size
RUN echo 'dbms.memory.heap.max_size=2g' >> ${NEO4J_HOME}/conf/neo4j.conf

# First build the container from this Dockerfile:
# $ docker build --tag neo4j-local .

# Run the locally built image using my local RDF resources. [sg]
# https://github.com/gorillalabs/neo4j-clj/blob/master/docs/neo4j.md
# $ docker run \
#     --publish=7474:7474 \
#     --publish=7687:7687 \
#     --volume=$HOME/neo4j/data:/data \
#     --volume=/Users/rqf595/Code/DanNet/resources:/resources \
#     --restart=always \
#     neo4j-local
