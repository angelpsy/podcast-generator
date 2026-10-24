#!/usr/bin/env python3
"""Prepare pinned, hash-verified Android TTS assets. No pip dependencies."""
import hashlib
import json
from pathlib import Path
import shutil
import tarfile
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.tooling/tts'
MAIN = ROOT / 'mobile/android/app/src/main'
ARTIFACTS = json.loads((ROOT / 'tools/tts-artifacts.json').read_text())
MODEL_FILES = ('duration_predictor.int8.onnx', 'text_encoder.int8.onnx',
               'vector_estimator.int8.onnx', 'vocoder.int8.onnx',
               'tts.json', 'unicode_indexer.bin', 'voice.bin')


def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def download(item):
    path = CACHE / item['file']
    if not path.exists():
        partial = path.with_name(path.name + '.download')
        print('Downloading', path.name, flush=True)
        request = Request(item['url'], headers={'User-Agent': 'PodcastGeneratorPrototype'})
        with urlopen(request, timeout=120) as response, partial.open('wb') as out:
            shutil.copyfileobj(response, out)
        if sha(partial) != item['sha256']:
            raise RuntimeError(f'SHA-256 mismatch: {path.name}')
        partial.replace(path)
    if sha(path) != item['sha256']:
        raise RuntimeError(f'SHA-256 mismatch: {path.name}')
    return path


def extract_selected(archive, selection):
    # Never extract paths/symlinks supplied by an archive into the workspace.
    found = set()
    with tarfile.open(archive) as tar:
        for member in tar:
            key = member.name.removeprefix('./')
            if key in selection and member.isfile():
                target = selection[key]
                target.parent.mkdir(parents=True, exist_ok=True)
                with tar.extractfile(member) as src, target.open('wb') as out:
                    shutil.copyfileobj(src, out)
                found.add(key)
    if found != set(selection):
        raise RuntimeError(f'Missing archive entries: {set(selection) - found}')


def main():
    CACHE.mkdir(parents=True, exist_ok=True)
    archives = {key: download(item) for key, item in ARTIFACTS.items()}
    assets = MAIN / 'assets/tts/supertonic3'
    assets.mkdir(parents=True, exist_ok=True)
    prefix = ARTIFACTS['model']['file'].removesuffix('.tar.bz2')
    extract_selected(archives['model'], {f'{prefix}/{f}': assets / f for f in MODEL_FILES})
    extract_selected(archives['runtime'], {
        f'jniLibs/arm64-v8a/{name}': MAIN / 'jniLibs/arm64-v8a' / name
        for name in ('libonnxruntime.so', 'libsherpa-onnx-jni.so')
    })
    binding = MAIN / 'java/com/k2fsa/sherpa/onnx/Tts.kt'
    binding.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(archives['binding'], binding)
    licenses = MAIN / 'assets/licenses'
    licenses.mkdir(parents=True, exist_ok=True)
    for key in ('sherpa_license', 'model_license', 'onnxruntime_license'):
        shutil.copyfile(archives[key], licenses / (ARTIFACTS[key]['file'] + '.txt'))
    manifest = {
        'runtime': 'sherpa-onnx v2.0.0', 'package': prefix,
        'archiveSha256': ARTIFACTS['model']['sha256'],
        'files': {name: sha(assets / name) for name in MODEL_FILES},
    }
    (assets / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print('Prepared arm64 JNI, model assets, binding and licenses.', flush=True)


if __name__ == '__main__':
    main()
