FROM eclipse-temurin:21-jre

WORKDIR /app
COPY apps/pubsub-node/build/install/pubsub-node/ /app/

ENTRYPOINT ["/app/bin/pubsub-node"]
