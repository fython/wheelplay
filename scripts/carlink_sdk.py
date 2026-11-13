#!/usr/bin/env python3
"""Offline, pinned CarLink provisioning and APK packaging. No network access."""
import argparse
import hashlib
import json
import os
import pathlib
import re
import shutil
import struct
import subprocess
import tempfile
import zipfile

DEFAULT_LOCK = pathlib.Path(__file__).resolve().parents[1] / 'projection-plugin-carlink/sdk-lock.json'
SDK_ENTRIES = {'icar-sdk.dex': 'classes3.dex', 'ccd.data': 'assets/ccd.data',
               'libovmsink.so': 'lib/arm64-v8a/libovmsink.so', 'libusbio.so': 'lib/arm64-v8a/libusbio.so'}
REQUIRED_CLASSES = {'Lcom/ucar/vehiclesdk/UCarAdapter;', 'Lcom/ucar/vehiclesdk/UCarConfig;',
                    'Lcom/share/connect/ShareLinkService;', 'Lcom/share/connect/ble/BluetoothLeService;',
                    'Lcom/share/connect/wifip2p/WifiP2pService;', 'Lcom/share/connect/wifiap/WifiApService;'}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def dex_types_and_classes(data):
    if not data.startswith(b'dex\n') or len(data) < 112:
        raise ValueError('Not a DEX file')
    u32 = lambda off: struct.unpack_from('<I', data, off)[0]
    strings = []
    for i in range(u32(56)):
        off = u32(u32(60) + i * 4)
        while data[off] & 128:
            off += 1
        off += 1
        strings.append(data[off:data.index(0, off)].decode('utf-8', 'replace'))
    types = [strings[u32(u32(68) + i * 4)] for i in range(u32(64))]
    return set(types), {types[u32(u32(100) + i * 32)] for i in range(u32(96))}


def dex_classes(data):
    return dex_types_and_classes(data)[1]


def missing_sdk_types(sdk_dex, definitions):
    types, _ = dex_types_and_classes(sdk_dex)
    platform = ('Ljava/', 'Ljavax/', 'Landroid/', 'Ldalvik/', 'Lorg/json/', 'Lorg/xml/', 'Lorg/w3c/', 'Lsun/')
    # R8's metadata annotation is intentionally absent from the reference release too.
    optional = {'Lcom/android/tools/r8/annotations/SynthesizedClassMap;'}
    return sorted(t for t in {t.lstrip('[') for t in types} - definitions - optional
                  if t.startswith('L') and not t.startswith(platform))


def verify(sdk, lock):
    for name, expected in lock['files'].items():
        path = sdk / name
        if not path.is_file():
            raise ValueError(f'Missing CarLink dependency: {path}. Run prepare with the pinned local MiAuto APK.')
        data = path.read_bytes()
        if len(data) != expected['size'] or digest(data) != expected['sha256']:
            raise ValueError(f'CarLink dependency does not match MiAuto v0.1.3: {name}')
        if name.endswith('.so'):
            if data[:5] != b'\x7fELF\x02' or data[5] != 1 or struct.unpack_from('<H', data, 18)[0] != 183:
                raise ValueError(f'Expected little-endian ELF64 AArch64: {name}')
    classes = dex_classes((sdk / 'icar-sdk.dex').read_bytes())
    if not REQUIRED_CLASSES <= classes:
        raise ValueError('SDK lacks required class definitions (strings/references are insufficient)')
    if any(c.startswith('Landroidx/room/') for c in classes):
        raise ValueError('Unexpected embedded Room; dependency layout has changed')


def prepare(apk, sdk, lock):
    if digest(apk.read_bytes()) != lock['apkSha256']:
        raise ValueError('APK is not the pinned MiAuto v0.1.3 release')
    # Validate in staging before replacing an existing bundle.
    sdk.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=sdk.parent) as temporary:
        stage = pathlib.Path(temporary)
        with zipfile.ZipFile(apk) as archive:
            for name, entry in SDK_ENTRIES.items():
                (stage / name).write_bytes(archive.read(entry))
        verify(stage, lock)
        sdk.mkdir(parents=True, exist_ok=True)
        for name in SDK_ENTRIES:
            shutil.copy2(stage / name, sdk / name)


def validate_variant(metadata, variant):
    expected_id = 'moe.feng.wheelplay.plugin.carlink' + ('.debug' if variant == 'debug' else '')
    if variant not in ('debug', 'release') or metadata.get('variantName') != variant or metadata.get('applicationId') != expected_id:
        raise ValueError('CarLink APK variant/application ID mismatch')


def signing_arguments(variant):
    if variant == 'debug':
        return ['--ks-key-alias', 'androiddebugkey', '--ks-pass', 'pass:android', '--key-pass', 'pass:android']
    if variant != 'release':
        raise ValueError('Unsupported signing variant')
    required = ('ANDROID_KEY_ALIAS', 'ANDROID_KEYSTORE_PASSWORD', 'ANDROID_KEY_PASSWORD')
    if any(not os.environ.get(name) for name in required):
        raise ValueError('Release signing requires ANDROID_KEY_ALIAS, ANDROID_KEYSTORE_PASSWORD and ANDROID_KEY_PASSWORD')
    # Pass environment references, never password values in process arguments or logs.
    return ['--ks-key-alias', os.environ['ANDROID_KEY_ALIAS'],
            '--ks-pass', 'env:ANDROID_KEYSTORE_PASSWORD', '--key-pass', 'env:ANDROID_KEY_PASSWORD']


def package(args, lock):
    verify(args.sdk, lock)
    source = args.input
    metadata = json.loads((source / 'output-metadata.json').read_text())
    validate_variant(metadata, args.variant)
    if len(metadata['elements']) != 1:
        raise ValueError('APK splits are not supported')
    filename = metadata['elements'][0]['outputFile']
    if pathlib.Path(filename).name != filename:
        raise ValueError('Unsafe output APK path')
    args.output.mkdir(parents=True, exist_ok=True)
    # Remove stale outputs: a failed build must not leave a previously valid final APK.
    for old in args.output.glob('*.apk'):
        old.unlink()
    final = args.output / filename
    with tempfile.TemporaryDirectory(dir=args.output) as temporary:
        temp = pathlib.Path(temporary)
        unsigned = temp / 'unsigned.apk'
        with zipfile.ZipFile(source / filename) as zin, zipfile.ZipFile(unsigned, 'w') as zout:
            names = zin.namelist()
            if len(names) != len(set(names)):
                raise ValueError('Duplicate APK entries')
            sdk_classes = dex_classes((args.sdk / 'icar-sdk.dex').read_bytes())
            for name in names:
                if re.fullmatch(r'classes(?:\d+)?\.dex', name):
                    overlap = dex_classes(zin.read(name)) & sdk_classes
                    if overlap:
                        raise ValueError(f'Stubs or duplicate SDK classes packaged: {sorted(overlap)[:3]}')
                if name.startswith('lib/') and '/arm64-v8a/' not in name:
                    raise ValueError('Unexpected non-arm64 native library')
                if name in ('assets/ccd.data', 'lib/arm64-v8a/libovmsink.so', 'lib/arm64-v8a/libusbio.so'):
                    raise ValueError(f'Unexpected pre-existing SDK entry: {name}')
                if name.startswith('META-INF/') and name.upper().endswith(('.RSA', '.DSA', '.EC', '.SF', '.MF')):
                    continue
                zout.writestr(zin.getinfo(name), zin.read(name))
            index = 2
            while f'classes{index}.dex' in names:
                index += 1
            zout.writestr(f'classes{index}.dex', (args.sdk / 'icar-sdk.dex').read_bytes(), compress_type=zipfile.ZIP_DEFLATED)
            zout.writestr('assets/ccd.data', (args.sdk / 'ccd.data').read_bytes(), compress_type=zipfile.ZIP_DEFLATED)
            for name in ('libovmsink.so', 'libusbio.so'):
                zout.writestr('lib/arm64-v8a/' + name, (args.sdk / name).read_bytes(), compress_type=zipfile.ZIP_STORED)
        aligned = temp / 'aligned.apk'
        signed = temp / 'signed.apk'
        subprocess.run([str(args.build_tools / 'zipalign'), '-f', '-p', '4', str(unsigned), str(aligned)], check=True)
        subprocess.run([str(args.build_tools / 'apksigner'), 'sign', '--ks', str(args.keystore),
                        *signing_arguments(args.variant),
                        '--out', str(signed), str(aligned)], check=True)
        subprocess.run([str(args.build_tools / 'apksigner'), 'verify', str(signed)], check=True, capture_output=True)
        subprocess.run([str(args.build_tools / 'zipalign'), '-c', '-p', '4', str(signed)], check=True)
        resources = subprocess.check_output([str(args.build_tools / 'aapt2'), 'dump', 'resources', str(signed)], text=True)
        if not re.search(r'0x7f0700a8\s+drawable/carlink_sdk_icon', resources):
            raise ValueError('SDK hard-coded bitmap resource ID was not preserved')
        with zipfile.ZipFile(signed) as archive:
            definitions = set()
            for entry in archive.namelist():
                if re.fullmatch(r'classes(?:\d+)?\.dex', entry):
                    definitions.update(dex_classes(archive.read(entry)))
            missing = missing_sdk_types((args.sdk / 'icar-sdk.dex').read_bytes(), definitions)
            if missing:
                raise ValueError(f'SDK references missing external classes: {missing}')
            for name, entry in SDK_ENTRIES.items():
                if name == 'icar-sdk.dex':
                    entry = f'classes{index}.dex'
                if digest(archive.read(entry)) != lock['files'][name]['sha256']:
                    raise ValueError(f'Final APK dependency mismatch: {name}')
        shutil.move(signed, final)
    (args.output / 'output-metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
    (args.output / 'carlink-build.json').write_text(json.dumps({'variant': args.variant, 'applicationId': metadata['applicationId'], 'apkSha256': digest(final.read_bytes()), 'sdk': lock}, indent=2) + '\n')
    print(f'Verified CarLink APK: {final}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['prepare', 'verify', 'package'])
    parser.add_argument('--sdk', type=pathlib.Path, required=True)
    parser.add_argument('--lock', type=pathlib.Path, default=DEFAULT_LOCK)
    parser.add_argument('--apk', type=pathlib.Path)
    parser.add_argument('--input', type=pathlib.Path)
    parser.add_argument('--output', type=pathlib.Path)
    parser.add_argument('--build-tools', type=pathlib.Path)
    parser.add_argument('--keystore', type=pathlib.Path)
    parser.add_argument('--variant', choices=['debug', 'release'], default='debug')
    args = parser.parse_args()
    lock = json.loads(args.lock.read_text())
    try:
        if args.command == 'prepare':
            if args.apk is None:
                parser.error('prepare requires --apk')
            prepare(args.apk, args.sdk, lock)
        elif args.command == 'verify':
            verify(args.sdk, lock)
        else:
            if any(x is None for x in (args.input, args.output, args.build_tools, args.keystore)):
                parser.error('package requires --input, --output, --build-tools and --keystore')
            package(args, lock)
    except (ValueError, OSError, KeyError, struct.error, zipfile.BadZipFile) as failure:
        parser.exit(1, f'CarLink: {failure}\n')


if __name__ == '__main__':
    main()
