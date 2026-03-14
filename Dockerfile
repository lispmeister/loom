FROM node:22-slim

RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Analysis cache for self-hosted ClojureScript eval
COPY .shadow-cljs/builds/lab/release/ana/cljs/core.cljs.cache.transit.json \
     /app/cljs-core-cache.transit.json

# Pre-compiled, self-contained lab script
COPY out/lab.js /app/lab.js

ENV CACHE_PATH=/app/cljs-core-cache.transit.json
EXPOSE 8402

CMD ["node", "/app/lab.js"]
