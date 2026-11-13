"""Dependency-free tests: synthetic DEX fixtures, never proprietary runtime blobs."""
import importlib.util
import json
import pathlib
import struct
import tempfile
import unittest
import zipfile
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('carlink_sdk', pathlib.Path(__file__).parents[1] / 'scripts/carlink_sdk.py')
sdk = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sdk)


def dex(definitions, references=()):
    strings = sorted(set(definitions) | set(references))
    result = bytearray(112 + 8 * len(strings) + 32 * len(definitions))
    result[:8] = b'dex\n035\0'
    def u32(offset, value):
        struct.pack_into('<I', result, offset, value)
    u32(56, len(strings)); u32(60, 112)
    u32(64, len(strings)); u32(68, 112 + 4 * len(strings))
    u32(96, len(definitions)); u32(100, 112 + 8 * len(strings))
    for i, name in enumerate(strings):
        u32(112 + 4 * i, len(result))
        result.extend(bytes([len(name)]) + name.encode() + b'\0')
        u32(112 + 4 * len(strings) + 4 * i, i)
    for i, name in enumerate(definitions):
        u32(112 + 8 * len(strings) + 32 * i, strings.index(name))
    return bytes(result)


class DependencyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = pathlib.Path(self.temp.name)
        native = bytearray(64)
        native[:6] = b'\x7fELF\x02\x01'
        struct.pack_into('<H', native, 18, 183)
        self.files = {'icar-sdk.dex': dex(sdk.REQUIRED_CLASSES), 'ccd.data': b'test fixture',
                      'libusbio.so': bytes(native), 'libovmsink.so': bytes(native)}
        self.lock = {'files': {n: {'size': len(d), 'sha256': sdk.digest(d)} for n, d in self.files.items()}}
        for name, data in self.files.items():
            (self.directory / name).write_bytes(data)

    def test_valid_bundle(self):
        sdk.verify(self.directory, self.lock)

    def test_missing_blob_fails(self):
        (self.directory / 'icar-sdk.dex').unlink()
        with self.assertRaisesRegex(ValueError, 'Missing CarLink dependency'):
            sdk.verify(self.directory, self.lock)

    def test_same_size_corruption_fails(self):
        (self.directory / 'ccd.data').write_bytes(b'fake fixture')
        with self.assertRaisesRegex(ValueError, 'does not match'):
            sdk.verify(self.directory, self.lock)

    def test_references_do_not_count_as_definitions(self):
        blob = dex([], sdk.REQUIRED_CLASSES)
        (self.directory / 'icar-sdk.dex').write_bytes(blob)
        self.lock['files']['icar-sdk.dex'] = {'size': len(blob), 'sha256': sdk.digest(blob)}
        with self.assertRaisesRegex(ValueError, 'class definitions'):
            sdk.verify(self.directory, self.lock)

    def test_wrong_abi_even_with_matching_hash_fails(self):
        blob = bytearray(self.files['libusbio.so'])
        struct.pack_into('<H', blob, 18, 62)
        (self.directory / 'libusbio.so').write_bytes(blob)
        self.lock['files']['libusbio.so']['sha256'] = sdk.digest(blob)
        with self.assertRaisesRegex(ValueError, 'AArch64'):
            sdk.verify(self.directory, self.lock)

    def test_prepare_rejects_wrong_apk_without_changing_existing_bundle(self):
        apk = self.directory / 'wrong.apk'; apk.write_bytes(b'wrong')
        self.lock['apkSha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'pinned'):
            sdk.prepare(apk, self.directory, self.lock)
        self.assertEqual(self.files['ccd.data'], (self.directory / 'ccd.data').read_bytes())

    def test_prepare_is_repeatable(self):
        apk = self.directory / 'source.apk'
        with zipfile.ZipFile(apk, 'w') as archive:
            for name, entry in sdk.SDK_ENTRIES.items():
                archive.writestr(entry, self.files[name])
        self.lock['apkSha256'] = sdk.digest(apk.read_bytes())
        destination = self.directory / 'prepared'
        sdk.prepare(apk, destination, self.lock)
        sdk.prepare(apk, destination, self.lock)
        sdk.verify(destination, self.lock)

    def test_duplicate_room_is_rejected(self):
        blob = dex(sdk.REQUIRED_CLASSES | {'Landroidx/room/Room;'})
        (self.directory / 'icar-sdk.dex').write_bytes(blob)
        self.lock['files']['icar-sdk.dex'] = {'size': len(blob), 'sha256': sdk.digest(blob)}
        with self.assertRaisesRegex(ValueError, 'embedded Room'):
            sdk.verify(self.directory, self.lock)


class PackagingPolicyTests(unittest.TestCase):
    def test_variant_identity_matches_mobile_style_debug_suffix(self):
        for variant, app_id in [('debug', 'moe.feng.wheelplay.plugin.carlink.debug'),
                                ('release', 'moe.feng.wheelplay.plugin.carlink')]:
            sdk.validate_variant({'variantName': variant, 'applicationId': app_id}, variant)
            with self.assertRaisesRegex(ValueError, 'mismatch'):
                sdk.validate_variant({'variantName': variant, 'applicationId': app_id},
                                     'release' if variant == 'debug' else 'debug')
        with self.assertRaisesRegex(ValueError, 'mismatch'):
            sdk.validate_variant({'variantName': 'release', 'applicationId': 'other.app'}, 'release')

    def test_release_requires_explicit_signing_and_does_not_put_passwords_in_arguments(self):
        with patch.dict('os.environ', {}, clear=True):
            with self.assertRaisesRegex(ValueError, 'Release signing requires'):
                sdk.signing_arguments('release')
        with patch.dict('os.environ', {'ANDROID_KEY_ALIAS': 'release-key',
                                      'ANDROID_KEYSTORE_PASSWORD': 'store-secret',
                                      'ANDROID_KEY_PASSWORD': 'key-secret'}, clear=True):
            args = sdk.signing_arguments('release')
            self.assertIn('release-key', args)
            self.assertIn('env:ANDROID_KEYSTORE_PASSWORD', args)
            self.assertIn('env:ANDROID_KEY_PASSWORD', args)
            self.assertNotIn('store-secret', args)
            self.assertNotIn('key-secret', args)
            self.assertNotIn('androiddebugkey', args)

    def test_debug_never_uses_release_credentials(self):
        with patch.dict('os.environ', {'ANDROID_KEY_ALIAS': 'release-key'}, clear=True):
            self.assertIn('androiddebugkey', sdk.signing_arguments('debug'))
            self.assertNotIn('release-key', sdk.signing_arguments('debug'))


if __name__ == '__main__':
    unittest.main()
