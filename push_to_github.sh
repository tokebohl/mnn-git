#!/usr/bin/env bash
set -euo pipefail

REPO_URL="https://github.com/tokebohl/MNN_Next.git"

echo "=========================================================="
echo "  Uploader MNN_Next (Jarvis Edition) til GitHub..."
echo "  Repository: $REPO_URL"
echo "=========================================================="

if [ ! -d ".git" ]; then
    git init
fi

git branch -M main
git add .
git commit -m "Complete MNN_Next Jarvis AI Brain for OnePlus 12R + HayaiTTS-Next 10T Auto-Bridge" || true

if git remote | grep -q "^origin$"; then
    git remote set-url origin "$REPO_URL"
else
    git remote add origin "$REPO_URL"
fi

git push -u origin main --force

echo "=========================================================="
echo "  FAERDIG! Koden ligger nu paa: https://github.com/tokebohl/MNN_Next"
echo "  GitHub Actions bygger nu automatisk din APK under Releases!"
echo "=========================================================="
