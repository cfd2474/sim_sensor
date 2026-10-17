# Sim Sensor updater: the only container with the Docker socket (chunk 4.6). It has no network
# service; it acts only on update/request.json, written by the server when an admin clicks
# "Update", and runs deploy/updater/update.sh from the *current* release.
FROM docker:27.3.1-cli
RUN apk add --no-cache curl jq tar
