#!/bin/sh
# MafiaBrain.jar 만들기: sh brain/build.sh [서버 폴더]
# 서버 폴더(기본 C:\mcserverpacks\mafia)의 libraries 안 jar(paper-api, adventure 등)로 컴파일한다.
set -e
cd "$(dirname "$0")"
SRV="${1:-/c/mcserverpacks/mafia}"
CP=$(find "$SRV/libraries" -name "*.jar" | grep -v "1.21.10-R0.1" | while read -r f; do cygpath -w "$f" 2>/dev/null || echo "$f"; done | tr "
" ";")
rm -rf out && mkdir -p out
javac --release 21 -encoding UTF-8 -cp "$CP" -d out src/mafiabrain/*.java
cp src/plugin.yml out/
java MakeJar.java out MafiaBrain.jar
rm -rf out
echo "built brain/MafiaBrain.jar"
