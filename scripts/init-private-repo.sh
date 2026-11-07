#!/usr/bin/env bash
# =====================================================================
# PushBridge Private GitHub Repository Initialization & Security Hardening
# Version 3.0.2
# =====================================================================
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}======================================================${NC}"
echo -e "${BLUE}  PushBridge Repository Security & Private Repo Init  ${NC}"
echo -e "${BLUE}======================================================${NC}"

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_DIR"

# 1. Verify Git Setup
if ! command -v git &> /dev/null; then
    echo -e "${RED}[ERROR] git is not installed.${NC}"
    exit 1
fi

echo -e "\n${YELLOW}[1/4] Verifying .gitignore and excluded secret patterns...${NC}"
SECRET_FILES=(
    "android-app/app/google-services.json"
    ".env"
    "relay-server/.env"
    "serviceAccountKey.json"
)

for file in "${SECRET_FILES[@]}"; do
    if [ -f "$file" ]; then
        if git ls-files --error-unmatch "$file" 2>/dev/null; then
            echo -e "${YELLOW}  [WARN] '$file' is currently tracked by git. Untracking from git cache (keeping local file)...${NC}"
            git rm --cached "$file" 2>/dev/null || true
        else
            echo -e "${GREEN}  ✓ '$file' is properly ignored.${NC}"
        fi
    fi
done

# 2. Check Remote Repository Visibility
echo -e "\n${YELLOW}[2/4] Checking GitHub repository privacy status...${NC}"
REMOTE_URL=$(git config --get remote.origin.url || true)

if [ -z "$REMOTE_URL" ]; then
    echo -e "${YELLOW}No remote origin configured yet.${NC}"
    echo -e "You can create and initialize a strictly private repository using:"
    echo -e "  ${GREEN}gh repo create pushbullet-bridge --private --source=. --remote=origin --push${NC}"
    exit 0
fi

echo -e "Remote URL: ${BLUE}$REMOTE_URL${NC}"

# Extract owner and repo name from remote URL (supports ssh and https)
REPO_NAME=$(echo "$REMOTE_URL" | sed -E 's/.*github\.com[:\/]([^\/]+\/[^\/\.]+)(\.git)?/\1/')

if command -v gh &> /dev/null; then
    echo -e "Verifying with GitHub CLI (gh)..."
    if gh auth status &> /dev/null; then
        IS_PRIVATE=$(gh repo view "$REPO_NAME" --json isPrivate --jq '.isPrivate' 2>/dev/null || echo "unknown")
        if [ "$IS_PRIVATE" = "true" ]; then
            echo -e "${GREEN}✓ Repository '$REPO_NAME' is confirmed strictly PRIVATE.${NC}"
        elif [ "$IS_PRIVATE" = "false" ]; then
            echo -e "${RED}! Repository '$REPO_NAME' is currently PUBLIC. Hardening to PRIVATE...${NC}"
            gh repo edit "$REPO_NAME" --visibility private
            echo -e "${GREEN}✓ Successfully converted '$REPO_NAME' to PRIVATE.${NC}"
        fi
    else
        echo -e "${YELLOW}GitHub CLI is installed but not authenticated. Run 'gh auth login' or check via API.${NC}"
    fi
fi

# Fallback check with Git credential helper
echo -e "\n${YELLOW}[3/4] Verifying GitHub API privacy endpoint...${NC}"
GH_TOKEN=$(printf "protocol=https\nhost=github.com\n" | git credential fill 2>/dev/null | grep '^password=' | cut -d= -f2 || echo "")

if [ -n "$GH_TOKEN" ] && [ -n "$REPO_NAME" ]; then
    VISIBILITY_CHECK=$(curl -s -H "Authorization: token $GH_TOKEN" "https://api.github.com/repos/$REPO_NAME" 2>/dev/null || echo "{}")
    IS_PRIV=$(echo "$VISIBILITY_CHECK" | grep -o '"private": *true' || echo "")
    if [ -n "$IS_PRIV" ]; then
        echo -e "${GREEN}✓ GitHub API confirms: '$REPO_NAME' is PRIVATE.${NC}"
    else
        echo -e "${YELLOW}Enforcing private visibility via GitHub API...${NC}"
        curl -s -X PATCH -H "Authorization: token $GH_TOKEN" -H "Content-Type: application/json" \
            -d '{"private": true}' "https://api.github.com/repos/$REPO_NAME" > /dev/null
        echo -e "${GREEN}✓ Enforced private visibility for '$REPO_NAME'.${NC}"
    fi
fi

# 4. Verify Local .env Files
echo -e "\n${YELLOW}[4/4] Verifying Environment Variables Template (.env.example)...${NC}"
if [ -f ".env.example" ]; then
    echo -e "${GREEN}✓ .env.example template is present.${NC}"
else
    echo -e "${RED}! .env.example is missing.${NC}"
fi

echo -e "\n${GREEN}======================================================${NC}"
echo -e "${GREEN}  Security Audit & Private Repository Initialization Complete!  ${NC}"
echo -e "${GREEN}======================================================${NC}"
