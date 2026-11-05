#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

container=kiikick-dev
previous=kiikick-dev-previous
env_file=/etc/kiikick/kiikick.env
health=http://127.0.0.1:8080/actuator/health
commit=${1:-}
digest=${2:-}

if [[ ! $commit =~ ^[a-f0-9]{40}$ || ! $digest =~ ^[a-f0-9]{64}$ ]]; then
    echo 'Expected a commit SHA and a JAR SHA-256' >&2
    exit 2
fi

exec 9>/run/lock/kiikick-dev-deploy.lock
flock -w 60 9
staging=$(mktemp -d)
trap 'unlink "$staging/app.jar" 2>/dev/null || true; rmdir "$staging"' EXIT

cat > "$staging/app.jar"
printf '%s  %s\n' "$digest" "$staging/app.jar" | sha256sum --check --status
chmod 0644 "$staging/app.jar"
image="kiikick-dev:$commit"
docker build --pull --label "org.opencontainers.image.revision=$commit" \
    --file /usr/local/share/kiikick/dev.Dockerfile --tag "$image" "$staging"

wait_for_health() {
    for ((attempt=0; attempt<40; attempt++)); do
        if curl --silent --fail --max-time 2 "$health" |
            python3 -c 'import json, sys; sys.exit(json.load(sys.stdin)["status"] != "UP")' 2>/dev/null; then
            return 0
        fi
        sleep 1
    done
    return 1
}

# Read the root-managed systemd environment file, including quoted values.
set -a
source "$env_file"
set +a

if docker container inspect "$previous" >/dev/null 2>&1; then
    docker container rm "$previous"
fi

if docker container inspect "$container" >/dev/null 2>&1; then
    docker stop --time 20 "$container"
    docker rename "$container" "$previous"
    had_container=true
else
    systemctl stop kiikick.service
    had_container=false
fi

if docker run --detach --name "$container" --restart unless-stopped \
        --publish 127.0.0.1:8080:8080 \
        --env DB_HOST --env DB_PORT --env DB_NAME --env DB_USERNAME --env DB_PASSWORD "$image" && wait_for_health; then
    systemctl disable kiikick.service
    printf 'Deployed Docker image %s (jar sha256 %s)\n' "$image" "$digest"
    exit 0
fi

echo 'Docker deployment failed. Restoring previous service.' >&2
docker logs --tail 30 "$container" >&2 || true
docker container rm --force "$container" || true
if "$had_container"; then
    docker rename "$previous" "$container"
    docker start "$container"
else
    systemctl start kiikick.service
fi
wait_for_health
exit 1
