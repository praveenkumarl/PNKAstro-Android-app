@echo off
REM commit_to_main.bat -- Safely commit working tree to local main using COMMIT_MSG.txt
REM Usage: double-click or run from cmd.exe in the repo root.

cd /d %~dp0
echo Repository root: %CD%

:: Check for git
git --version >nul 2>&1
if errorlevel 1 (
  echo ERROR: git not found in PATH. Install Git for Windows: https://git-scm.com/download/win
  pause
  exit /b 1
)

:: Show status
echo --- Current git status ---
git status --porcelain -b --untracked-files=all
echo --------------------------

:: If not on main, prompt to switch (safe flow)
echo Checking current branch...
for /f "usebackq delims=" %%b in (`git rev-parse --abbrev-ref HEAD`) do set CURBR=%%b
echo Current branch is: %CURBR%
if /i not "%CURBR%"=="main" (
  echo You are not on 'main'. It's safer to switch to 'main' before committing.
  choice /m "Switch to 'main' now? (If you have local uncommitted changes they'll be preserved via stash)"
  if errorlevel 2 goto abort
  echo Stashing local changes (including untracked)...
  git stash push -u -m "WIP: automated stash before switch to main"
  echo Switching to main...
  git checkout main
  echo Applying stashed changes...
  git stash pop || echo "Nothing to pop or conflict occurred; resolve manually"
)

:: Stage and commit using COMMIT_MSG.txt if available
echo Staging all changes...
git add -A
if exist COMMIT_MSG.txt (
  echo Committing using COMMIT_MSG.txt
  git commit -F COMMIT_MSG.txt || (echo Commit failed; check 'git status' && pause && exit /b 1)
) else (
  echo COMMIT_MSG.txt not found. Please enter a commit message:
  set /p USERMSG=Commit message:
  if "%USERMSG%"=="" (
    echo Empty message, aborting.
    goto abort
  )
  git commit -m "%USERMSG%" || (echo Commit failed; check 'git status' && pause && exit /b 1)
)

echo Successfully created commit on branch 'main'.
echo --- Last commit ---
git log -1 --oneline --stat
pause
:abort
echo Aborted.
pause
exit /b 1
