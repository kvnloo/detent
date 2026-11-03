import runpy
from pathlib import Path
import unittest

policy = runpy.run_path(str(Path(__file__).resolve().parents[1] / 'check-policy.py'))
allowed = policy['allowed']


class BranchPolicyTest(unittest.TestCase):
    def test_features_only_enter_preview(self):
        for branch in ['feat/themes', 'fix/transition', 'main']:
            self.assertTrue(allowed('preview', branch))
            self.assertFalse(allowed('main', branch))
            self.assertFalse(allowed('nightly', branch))

    def test_nightly_is_full_integration_before_stable(self):
        self.assertTrue(allowed('nightly', 'preview'))
        self.assertTrue(allowed('nightly', 'integration/wave-1'))
        self.assertTrue(allowed('main', 'nightly'))
        self.assertFalse(allowed('main', 'preview'))

    def test_bootstrap_rejects_product_source(self):
        self.assertTrue(policy['bootstrap_path']('.github/workflows/checks.yml'))
        self.assertTrue(policy['bootstrap_path']('app/build.gradle.kts'))
        self.assertFalse(policy['bootstrap_path']('app/src/main/java/app/detent/launcher/LauncherState.kt'))

    def test_ci_bootstrap_does_not_authorize_features_on_main(self):
        self.assertTrue(allowed('main', 'ci/preview-nightly'))
        self.assertFalse(allowed('main', 'feature/ci-excuse'))


if __name__ == '__main__':
    unittest.main()
