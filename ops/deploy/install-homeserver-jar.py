#!/usr/bin/env python3
"""Install a verified API JAR on the home server with a readiness-gated rollback."""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import zipfile

BASE = Path('/home/asher/apps/kiikick-admin')
IMAGE_PREFIX = 'kiikick-api:'
CANDIDATE = 'kiikick-api-deploy-candidate'
ALIAS = 'kiikick-api-candidate'
NGINX = 'kiikick-admin-nginx-1'
NETWORK = 'kiikick-admin_default'


def run(*args, capture=False):
    return subprocess.run(args, cwd=BASE, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None).stdout


def ready(host, attempts=45):
    for _ in range(attempts):
        try:
            result = run('docker', 'exec', NGINX, 'wget', '-q', '-O', '-',
                         f'http://{host}:8080/actuator/health', capture=True)
            if json.loads(result)['status'] == 'UP':
                return
        except (subprocess.CalledProcessError, ValueError, KeyError):
            pass
        time.sleep(2)
    raise RuntimeError(f'Readiness check failed: {host}')


def reload_nginx():
    run('docker', 'exec', NGINX, 'nginx', '-t')
    run('docker', 'exec', NGINX, 'nginx', '-s', 'reload')
    # Let old workers finish before stopping their upstream.
    time.sleep(3)


def validate_jar(path):
    with zipfile.ZipFile(path) as jar:
        names = jar.namelist()
        required = ['BOOT-INF/classes/com/teamehera/kiikick/observability/ProductAnalytics.class',
                    'BOOT-INF/classes/com/teamehera/kiikick/observability/SentryPrivacyFilter.class']
        if not all(name in names for name in required):
            raise RuntimeError('Monitoring classes are required for home-server deployments')
        if not any(name.startswith('BOOT-INF/lib/sentry-spring-boot-4-starter-') for name in names):
            raise RuntimeError('Sentry starter missing')
        if not any(name.startswith('BOOT-INF/lib/posthog-server-') for name in names):
            raise RuntimeError('PostHog SDK missing')
        if any('ObservabilityVerification' in name for name in names):
            raise RuntimeError('Verification endpoints must not be deployed')


def deploy(commit, digest, stream):
    if not re.fullmatch(r'[a-f0-9]{40}', commit) or not re.fullmatch(r'[a-f0-9]{64}', digest):
        raise ValueError('Expected a full commit SHA and JAR SHA-256')
    os.umask(0o077)
    with (BASE / '.api-deploy.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        release = BASE / 'releases' / f'{commit}-{digest[:12]}-{time.time_ns()}'
        release.mkdir(mode=0o700)
        jar = release / 'app.jar'
        sha = hashlib.sha256()
        with jar.open('wb') as output:
            while chunk := stream.read(1024 * 1024):
                sha.update(chunk)
                output.write(chunk)
        if sha.hexdigest() != digest:
            raise ValueError('JAR SHA-256 mismatch')
        validate_jar(jar)
        jar.chmod(0o644)
        # The image needs a world-traversable build context only for Docker COPY.
        (release / 'Dockerfile').write_text(
            'FROM eclipse-temurin:21-jre\nWORKDIR /app\n'
            'COPY --chown=10001:10001 app.jar /app/app.jar\nUSER 10001:10001\n'
            'EXPOSE 8080\nENTRYPOINT ["java","-jar","/app/app.jar"]\n')
        (release / '.dockerignore').write_text('*\n!Dockerfile\n!app.jar\n')
        image = IMAGE_PREFIX + commit + '-' + digest[:12]
        run('docker', 'build', '--label', f'org.opencontainers.image.revision={commit}',
            '--label', f'dev.kiikick.jar-sha256={digest}', '-t', image, str(release))

        files = {name: (BASE / name).read_bytes() for name in
                 ('api-production.env', 'compose.homeserver.yaml', 'nginx/cloudflare-homeserver.conf')}
        for name, content in files.items():
            (release / (Path(name).name + '.before')).write_bytes(content)
        env = dict(line.split('=', 1) for line in files['api-production.env'].decode().splitlines()
                   if line and not line.startswith('#'))
        if env.get('OBSERVABILITY_ENVIRONMENT') != 'production' or env.get('POSTHOG_ENABLED') != 'true':
            raise RuntimeError('Production monitoring must remain enabled')
        if not all(env.get(name) for name in ('SENTRY_DSN', 'POSTHOG_API_KEY')):
            raise RuntimeError('Production monitoring credentials are missing')
        env['SENTRY_RELEASE'] = 'kiikick-backend@' + commit
        candidate_env = release / 'production.env'
        candidate_env.write_text(''.join(k + '=' + v + '\n' for k, v in env.items()))
        conf = BASE / 'nginx/cloudflare-homeserver.conf'
        original = files['nginx/cloudflare-homeserver.conf'].decode()
        if 'kiikick-api-runtime:8080' not in original:
            raise RuntimeError('Unexpected API upstream; check previous deployment')
        compose = files['compose.homeserver.yaml'].decode()
        updated, count = re.subn(r'(?m)^(    image: ).+$', lambda m: m[1] + image, compose)
        if count != 1:
            raise RuntimeError('Expected exactly one API image in home-server override')
        candidate_started = False
        primary_changed = False
        success = False
        rollback_ok = False
        try:
            run('docker', 'run', '-d', '--name', CANDIDATE, '--network', NETWORK,
                '--network-alias', ALIAS, '--env-file', str(candidate_env),
                '-e', 'KIIKICK_WORKOUT_SUMMARY_WORKER_ENABLED=false',
                '--memory', '768m', '--log-opt', 'max-size=10m', '--log-opt', 'max-file=3', image)
            candidate_started = True
            ready(ALIAS)
            conf.write_text(original.replace('kiikick-api-runtime:8080', ALIAS + ':8080'))
            reload_nginx()
            (BASE / 'api-production.env').write_bytes(candidate_env.read_bytes())
            (BASE / 'compose.homeserver.yaml').write_text(updated)
            primary_changed = True
            run('docker', 'compose', 'up', '-d', '--no-deps', 'api')
            ready('kiikick-api-runtime')
            conf.write_text(original)
            reload_nginx()
            success = True
            (BASE / 'current-api-release.json').write_text(json.dumps(
                {'commit': commit, 'jar_sha256': digest, 'image': image, 'release': str(release)}, indent=2))
            print(f'Deployed {image}; production monitoring retained', flush=True)
        except Exception:
            print('Deployment failed; restoring previous API image and environment', file=sys.stderr)
            for name in ('api-production.env', 'compose.homeserver.yaml'):
                (BASE / name).write_bytes(files[name])
            try:
                if primary_changed:
                    run('docker', 'compose', 'up', '-d', '--no-deps', 'api')
                    ready('kiikick-api-runtime')
                conf.write_bytes(files['nginx/cloudflare-homeserver.conf'])
                reload_nginx()
                rollback_ok = True
            except Exception:
                print('Rollback requires operator attention; candidate retained if running', file=sys.stderr)
            raise
        finally:
            if candidate_started and (success or rollback_ok):
                run('docker', 'stop', '--time', '30', CANDIDATE)
                run('docker', 'rm', CANDIDATE)


if __name__ == '__main__':
    try:
        if len(sys.argv) != 3:
            raise ValueError('Expected commit SHA and JAR SHA-256')
        deploy(sys.argv[1], sys.argv[2], sys.stdin.buffer)
    except Exception as error:
        # Avoid printing subprocess environments or request contents.
        sys.exit(f'Home-server deployment failed ({type(error).__name__})')
