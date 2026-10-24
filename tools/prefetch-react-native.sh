#!/bin/bash
# Prepare a disposable dependency cache; do not create the mobile app.
set -eu
task_root="$(cd "$(dirname "$0")/.." && pwd)"
source "$task_root/tools/android-env.sh"
mkdir -p "$task_root/.tooling/react-native-dependencies"
cd "$task_root"
if [ ! -f .tooling/react-native-community-template-0.86.3.tgz ]; then
  npm pack @react-native-community/template@0.86.3 --pack-destination .tooling --ignore-scripts --silent
fi
tar -xOf .tooling/react-native-community-template-0.86.3.tgz package/template/package.json |
  node -e 'let data=""; process.stdin.on("data", chunk => data += chunk); process.stdin.on("end", () => {const p=JSON.parse(data); process.stdout.write(JSON.stringify({name:"siwc-tooling-cache",version:"0.0.0",private:true,dependencies:p.dependencies,devDependencies:p.devDependencies},null,2)+"\n");});' > .tooling/react-native-dependencies/package.json
npm install --prefix "$task_root/.tooling/react-native-dependencies" --ignore-scripts --no-audit --no-fund
printf '\nDependency cache prepared. No app, Android project or APK was created.\n'
