@echo off
SET REPO_URL=https://github.com/tokebohl/MNN_Next.git

echo ==========================================================
echo   Uploader MNN_Next (Jarvis Edition) til GitHub...
echo   Repository: %REPO_URL%
echo ==========================================================

if not exist ".git" (
    git init
)

git branch -M main
git add .
git commit -m "Complete MNN_Next Jarvis AI Brain for OnePlus 12R + HayaiTTS-Next 10T Auto-Bridge"

git remote remove origin 2>nul
git remote add origin %REPO_URL%
git push -u origin main --force

echo ==========================================================
echo   FAERDIG! Koden ligger nu paa: https://github.com/tokebohl/MNN_Next
echo   GitHub Actions bygger nu automatisk din APK under Releases!
echo ==========================================================
pause
