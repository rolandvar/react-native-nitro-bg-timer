#!/usr/bin/env node
// @ts-check
/* eslint-disable no-console */

/**
 * yarn release — automates publishing a new version of
 * react-native-nitro-bg-timer as a GitHub Release with an attached
 * npm tarball, so downstream consumers can install from an immutable
 * release asset URL instead of a git commit SHA (which GitHub codeload
 * sometimes re-packs non-deterministically, breaking `yarn install
 * --immutable` in CI).
 *
 * Flow:
 *   1. Sanity-check env: gh in PATH, clean worktree, expected branch,
 *      tag not already taken (locally or on remote)
 *   2. Push current branch to origin so the tag anchors a visible commit
 *   3. Clean previous build + tarball artifacts
 *   4. `npx bob build` to generate lib/
 *   5. `npm pack` to produce the .tgz
 *   6. Create + push git tag `v<version>`
 *   7. `gh release create` with the tarball attached
 *   8. Print the stable asset URL for downstream package.json updates
 *
 * Usage:
 *   yarn release                                # bumps nothing, uses package.json version
 *
 * Environment overrides:
 *   GH_BIN=<path-to-gh>        Override `gh` binary path (useful on Windows where
 *                              gh.exe lives under "C:\\Program Files\\GitHub CLI\\")
 *   GH_REPO=owner/repo         Override repo detection from git remote
 *   GIT_REMOTE=<name>          Override `origin`
 *   GIT_BRANCH=<name>          Override `release/next`
 *   SKIP_VERIFY=1              Skip lint + typecheck pre-release validation
 */

const { execSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const PKG = JSON.parse(
  fs.readFileSync(path.join(ROOT, 'package.json'), 'utf8')
);
const VERSION = PKG.version;
const NAME = PKG.name;
const TAG = `v${VERSION}`;
const TARBALL = `${NAME}-${VERSION}.tgz`;

const GH = process.env.GH_BIN || 'gh';
const REMOTE = process.env.GIT_REMOTE || 'origin';
const EXPECTED_BRANCH = process.env.GIT_BRANCH || 'release/next';
const SKIP_VERIFY = process.env.SKIP_VERIFY === '1';

function run(cmd) {
  execSync(cmd, { stdio: 'inherit', cwd: ROOT });
}

function capture(cmd) {
  return execSync(cmd, { cwd: ROOT }).toString().trim();
}

function silent(cmd) {
  return execSync(cmd, { cwd: ROOT, stdio: 'ignore' });
}

function die(msg) {
  console.error(`\n✖ ${msg}\n`);
  process.exit(1);
}

function ok(msg) {
  console.log(`  ✓ ${msg}`);
}

function step(msg) {
  console.log(`\n→ ${msg}`);
}

// ─── Sanity checks ───────────────────────────────────────────────────────────

function detectRepo() {
  try {
    const url = capture(`git remote get-url ${REMOTE}`);
    const match = url.match(/github\.com[:/]([^/]+)\/([^/.]+?)(?:\.git)?$/);
    if (match) return `${match[1]}/${match[2]}`;
  } catch {
    /* fall through */
  }
  return null;
}

const REPO =
  process.env.GH_REPO ||
  detectRepo() ||
  die(`Cannot detect GitHub repo from remote '${REMOTE}'. Set GH_REPO env var.`);

function assertGhAvailable() {
  try {
    silent(`"${GH}" --version`);
    ok(`gh CLI available`);
  } catch {
    die(
      `GitHub CLI (gh) not found. Install from https://cli.github.com/ ` +
        `or set GH_BIN to its full path.`
    );
  }
}

function assertCurrentBranch() {
  const branch = capture('git rev-parse --abbrev-ref HEAD');
  if (branch !== EXPECTED_BRANCH) {
    die(`Current branch is '${branch}', expected '${EXPECTED_BRANCH}'.`);
  }
  ok(`on branch ${EXPECTED_BRANCH}`);
}

function assertCleanWorkTree() {
  const status = capture('git status --porcelain');
  if (status) {
    die(
      `Working tree is not clean:\n${status}\n\n` +
        `Commit or stash changes before releasing.`
    );
  }
  ok(`working tree clean`);
}

function assertTagFree() {
  try {
    silent(`git rev-parse ${TAG}`);
    die(`Tag ${TAG} already exists locally. Bump version in package.json first.`);
  } catch {
    /* tag does not exist locally, good */
  }

  try {
    const remoteRefs = capture(`git ls-remote --tags ${REMOTE} ${TAG}`);
    if (remoteRefs.trim()) {
      die(
        `Tag ${TAG} already exists on ${REMOTE}. Bump version in package.json first.`
      );
    }
  } catch {
    /* ls-remote fail is non-fatal; surface via next step */
  }
  ok(`tag ${TAG} is available`);
}

// ─── Notes generator ─────────────────────────────────────────────────────────

function generateNotes() {
  try {
    const lastTag = capture('git describe --tags --abbrev=0');
    if (lastTag && lastTag !== TAG) {
      const log = capture(
        `git log ${lastTag}..HEAD --pretty=format:"- %s" --no-merges`
      );
      if (log) return `## Changes since ${lastTag}\n\n${log}\n`;
    }
  } catch {
    /* no prior tag or log empty */
  }
  return `Release ${TAG}\n`;
}

// ─── Main ────────────────────────────────────────────────────────────────────

(function main() {
  console.log(`\n→ Releasing ${NAME}@${VERSION} as ${TAG} → ${REPO}`);

  step('Sanity checks');
  assertGhAvailable();
  assertCurrentBranch();
  assertCleanWorkTree();
  assertTagFree();

  if (!SKIP_VERIFY) {
    step('Verification (lint + typecheck)');
    try {
      run('npx eslint "**/*.{js,ts,tsx}"');
      ok('lint clean');
    } catch {
      die(
        `ESLint failed. Fix the issues or re-run with SKIP_VERIFY=1 to bypass.`
      );
    }
    try {
      run('npx tsc --noEmit');
      ok('typecheck clean');
    } catch {
      die(
        `TypeScript check failed. Fix the issues or re-run with SKIP_VERIFY=1 to bypass.`
      );
    }
  } else {
    step('Verification skipped (SKIP_VERIFY=1)');
  }

  step(`Pushing ${EXPECTED_BRANCH} to ${REMOTE}`);
  run(`git push ${REMOTE} HEAD:${EXPECTED_BRANCH}`);

  step('Cleaning previous build artifacts');
  fs.rmSync(path.join(ROOT, 'lib'), { recursive: true, force: true });
  for (const f of fs.readdirSync(ROOT)) {
    if (f.endsWith('.tgz')) {
      fs.rmSync(path.join(ROOT, f));
      ok(`removed stale ${f}`);
    }
  }

  step('Building (react-native-builder-bob)');
  run('npx bob build');

  step('Packing (npm pack)');
  run('npm pack');
  const tarballPath = path.join(ROOT, TARBALL);
  if (!fs.existsSync(tarballPath)) {
    die(`Expected ${TARBALL} not found after npm pack`);
  }
  ok(TARBALL);

  step(`Tagging ${TAG}`);
  run(`git tag ${TAG}`);
  run(`git push ${REMOTE} ${TAG}`);

  step(`Creating GitHub Release ${TAG}`);
  const notesFile = path.join(ROOT, '.release-notes.tmp.md');
  fs.writeFileSync(notesFile, generateNotes());
  try {
    run(
      `"${GH}" release create ${TAG} ${TARBALL} ` +
        `--repo ${REPO} ` +
        `--title ${TAG} ` +
        `--notes-file "${notesFile}"`
    );
  } finally {
    fs.rmSync(notesFile, { force: true });
  }

  const url = `https://github.com/${REPO}/releases/download/${TAG}/${TARBALL}`;
  console.log(`\n✅ Release ${TAG} published.\n`);
  console.log(`  Tarball URL:  ${url}\n`);
  console.log(
    `  To update a downstream consumer, set the dependency in its package.json to:`
  );
  console.log(`    "${NAME}": "${url}"`);
  console.log(`  then run \`yarn install\` to refresh the lockfile.\n`);
})();
