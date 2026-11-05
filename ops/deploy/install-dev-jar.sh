#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

jar=/home/ubuntu/KIIKICK-back/build/libs/KIIKICK-back-0.0.1-SNAPSHOT.jar
health=http://127.0.0.1:8080/actuator/health
commit=${1:-}
digest=${2:-}

if [[ ! $commit =~ ^[a-f0-9]{40}$ || ! $digest =~ ^[a-f0-9]{64}$ ]]; then
    echo 'Expected a commit SHA and a JAR SHA-256' >&2
    exit 2
fi

exec 9>/run/lock/kiikick-dev-deploy.lock
flock -w 60 9
staged=$(mktemp "${jar}.staged.XXXXXX")
trap 'unlink "$staged" 2>/dev/null || true' EXIT

# Validate the complete upload before replacing the running service's artifact.
cat > "$staged"
printf '%s  %s\n' "$digest" "$staged" | sha256sum --check --status
chown ubuntu:ubuntu "$staged"
chmod 0644 "$staged"

wait_for_health() {
    for ((attempt=0; attempt<30; attempt++)); do
        if curl --silent --fail --max-time 2 "$health" |
            python3 -c 'import json, sys; sys.exit(json.load(sys.stdin)["status"] != "UP")' 2>/dev/null; then
            return 0
        fi
        sleep 1
    done
    return 1
}

cp -p "$jar" "$jar.previous"
mv -f "$staged" "$jar"
if systemctl restart kiikick.service && wait_for_health; then
    printf 'Deployed %s (sha256 %s)\n' "$commit" "$digest"
    exit 0
fi

echo 'Deployment health check failed. Restoring previous JAR.' >&2
mv -f "$jar.previous" "$jar"
systemctl restart kiikick.service
wait_for_health
exit 1
