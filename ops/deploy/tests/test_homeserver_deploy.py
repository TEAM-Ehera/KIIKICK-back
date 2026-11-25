import hashlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('deploy', Path(__file__).parents[1] / 'install-homeserver-jar.py')
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)


def artifact(verification=False):
    data = io.BytesIO()
    with zipfile.ZipFile(data, 'w') as jar:
        for name in ['BOOT-INF/classes/com/teamehera/kiikick/observability/ProductAnalytics.class',
                     'BOOT-INF/classes/com/teamehera/kiikick/observability/SentryPrivacyFilter.class',
                     'BOOT-INF/lib/sentry-spring-boot-4-starter-8.jar',
                     'BOOT-INF/lib/posthog-server-2.jar']:
            jar.writestr(name, b'test')
        if verification:
            jar.writestr('BOOT-INF/classes/ObservabilityVerificationController.class', b'test')
    return data.getvalue()


class HomeServerDeploymentTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        (self.base / 'releases').mkdir()
        (self.base / 'nginx').mkdir()
        self.before = {
            'api-production.env': b'DB_NAME=kiikick_production\nAUTH_SECRET=unchanged\nOBSERVABILITY_ENVIRONMENT=production\nPOSTHOG_ENABLED=true\nSENTRY_DSN=test\nPOSTHOG_API_KEY=test\nSENTRY_RELEASE=old\n',
            'compose.homeserver.yaml': b'services:\n  api:\n    image: old-image\n',
            'nginx/cloudflare-homeserver.conf': b'proxy_pass http://kiikick-api-runtime:8080;\n',
        }
        for name, content in self.before.items():
            (self.base / name).write_bytes(content)
        patcher = patch.object(deploy, 'BASE', self.base)
        patcher.start()
        self.addCleanup(patcher.stop)
        patcher = patch.object(deploy.time, 'sleep')
        patcher.start()
        self.addCleanup(patcher.stop)

    def install(self, data=None, digest=None):
        data = artifact() if data is None else data
        deploy.deploy('a' * 40, digest or hashlib.sha256(data).hexdigest(), io.BytesIO(data))

    def assert_restored(self):
        for name, content in self.before.items():
            self.assertEqual(content, (self.base / name).read_bytes())

    def test_wrong_digest_never_builds_or_changes_production(self):
        with patch.object(deploy, 'run') as run, self.assertRaises(ValueError):
            self.install(digest='0' * 64)
        run.assert_not_called()
        self.assert_restored()

    def test_verification_endpoint_rejected_before_docker(self):
        with patch.object(deploy, 'run') as run, self.assertRaises(RuntimeError):
            self.install(artifact(verification=True))
        run.assert_not_called()
        self.assert_restored()

    def test_failed_candidate_restores_without_recreating_primary(self):
        with patch.object(deploy, 'run') as run, patch.object(deploy, 'ready', side_effect=RuntimeError), self.assertRaises(RuntimeError):
            self.install()
        self.assert_restored()
        self.assertFalse(any(call.args[:2] == ('docker', 'compose') for call in run.call_args_list))
        self.assertTrue(any(call.args[:2] == ('docker', 'rm') for call in run.call_args_list))

    def test_failed_primary_restores_previous_image_then_removes_candidate(self):
        with patch.object(deploy, 'run') as run, patch.object(deploy, 'ready', side_effect=[None, RuntimeError(), None]), self.assertRaises(RuntimeError):
            self.install()
        self.assert_restored()
        self.assertEqual(2, sum(call.args[:2] == ('docker', 'compose') for call in run.call_args_list))
        self.assertEqual(('docker', 'rm', deploy.CANDIDATE), run.call_args_list[-1].args)

    def test_success_changes_release_but_preserves_credentials_and_routes(self):
        with patch.object(deploy, 'run'), patch.object(deploy, 'ready'):
            self.install()
        env = (self.base / 'api-production.env').read_text()
        self.assertIn('AUTH_SECRET=unchanged\n', env)
        self.assertIn('POSTHOG_ENABLED=true\n', env)
        self.assertIn('SENTRY_RELEASE=kiikick-backend@' + 'a' * 40, env)
        self.assertEqual(self.before['nginx/cloudflare-homeserver.conf'], (self.base / 'nginx/cloudflare-homeserver.conf').read_bytes())
        self.assertTrue((self.base / 'current-api-release.json').exists())


if __name__ == '__main__':
    unittest.main()
