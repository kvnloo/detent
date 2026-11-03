import copy
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
import provenance as p


class ProvenanceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.expected = {'source_sha': 'a' * 40, 'channel': 'preview', 'run_id': '123'}
        apks = {}
        # Deliberately synthetic ZIP fixtures; these tests validate guards, not Android rendering.
        for label, package in p.PACKAGES.items():
            name = f'detent-{label}.apk'
            with zipfile.ZipFile(self.root / name, 'w') as fixture:
                fixture.writestr('assets/detent-build.json', json.dumps(self.expected))
            apks[label] = {'file': name, 'package': package, 'sha256': p.sha256(self.root / name)}
        self.manifest = {**self.expected, 'schema': 1, 'version_code': 10001, 'apks': apks}
        (self.root / 'provenance.json').write_text(json.dumps(self.manifest))

    def verify(self):
        return p.verify(self.root, 'a' * 40, 'preview', '123')

    def receipt(self):
        return {**self.expected, 'schema': 1, 'device_serial': 'RFCTA1RZAQY',
                'artifact_hashes': {k: v['sha256'] for k, v in self.manifest['apks'].items()},
                'journeys': {j: 'passed' for j in p.JOURNEYS}, 'evidence_dir': 'test-fixture-only'}

    def test_matching_artifacts_verify(self):
        self.assertEqual(self.manifest, self.verify())

    def test_changed_apk_is_rejected(self):
        (self.root / 'detent-lab.apk').write_bytes(b'not the built bytes')
        with self.assertRaises(ValueError): self.verify()

    def test_moving_channel_or_run_cannot_reuse_receipt(self):
        for key, value in [('source_sha', 'b' * 40), ('run_id', '456'), ('channel', 'nightly')]:
            receipt = self.receipt(); receipt[key] = value
            with self.assertRaises(ValueError): p.qualification(receipt, self.manifest)

    def test_failed_missing_and_skipped_journeys_cannot_deploy(self):
        for result in ['failed', 'skipped', True, None]:
            receipt = self.receipt(); receipt['journeys']['image_import'] = result
            with self.assertRaises(ValueError): p.qualification(receipt, self.manifest)
        receipt = self.receipt(); del receipt['journeys']['distant_tap']
        with self.assertRaises(ValueError): p.qualification(receipt, self.manifest)

    def test_another_phone_is_not_s22_qualification(self):
        receipt = self.receipt(); receipt['device_serial'] = 'R5CY93G2WNH'
        with self.assertRaises(ValueError): p.qualification(receipt, self.manifest)

    def test_wrong_artifact_receipt_is_rejected(self):
        receipt = self.receipt(); receipt['artifact_hashes']['kvn'] = 'b' * 64
        with self.assertRaises(ValueError): p.qualification(receipt, self.manifest)

    def test_missing_evidence_is_rejected(self):
        receipt = self.receipt(); receipt.pop('evidence_dir')
        with self.assertRaises(ValueError): p.qualification(receipt, self.manifest)

    def test_complete_s22_receipt_is_accepted(self):
        p.qualification(self.receipt(), self.manifest)

    def test_policy_keeps_feature_preview_and_nightly_integration_distinct(self):
        policy = json.loads((ROOT / '.github/channels.json').read_text())
        self.assertEqual('preview', policy['feature_target'])
        self.assertEqual('nightly', policy['integration_target'])
        self.assertTrue(policy['s25_requires_s22_receipt'])
        self.assertFalse(policy['automatic_phone_install'])


if __name__ == '__main__':
    unittest.main()
