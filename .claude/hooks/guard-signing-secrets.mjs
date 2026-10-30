#!/usr/bin/env node
// PreToolUse guard: the release signing key and its passwords never load into a Claude session, and a session never
// creates, edits, copies, moves or deletes them. The owner keeps them; Gradle and scripts/prod-release.ps1 read them
// themselves, without the session seeing what they read.
//
// Registered twice: in this repo's .claude/settings.json (sessions started here) and in the user's
// ~/.claude/settings.json (sessions started in the server repo, which add this one with /add-dir — a project's hooks
// load only for sessions started in it).
//
// Guarded, in any directory, any case:
//   - keystore.properties                       the key's path, alias and both passwords
//   - *.jks, *.keystore, *.p12, *.pfx           key stores: the private key itself
//   - anything under a .android-keys directory  where the owner keeps the release key
//
// - Read / Edit / Write / MultiEdit / NotebookEdit on such a file → deny (creating one too).
// - Grep with `path` at such a file or directory, or a glob that targets one → deny. Other searches are left alone:
//   ripgrep skips git-ignored files, and these are git-ignored here (and the key lives outside every repo). No glob is
//   appended, so this hook never competes with another guard's rewrite of the same Grep.
// - A shell command (Bash, PowerShell, Monitor) that names such a file or directory → deny, listing included: use the
//   Glob tool to see whether one exists. The text of a `git commit` / `git tag` message is skipped first, so a message
//   may talk about keystore.properties; one that can run something (`$(…)`, `${…}`, a backtick) is still read.
// - Any other MCP tool whose path / file / command arguments name one → deny.
//
// Not caught: a command that reaches the file without naming it (a recursive search, a script that reads it). Running
// scripts/prod-release.ps1 or Gradle is meant to work: they read the key for the build and print no secret.
// Anything unexpected — malformed JSON, a bug in here — allows: a guard must never wedge the session.
//
// Self-test: node .claude/hooks/guard-signing-secrets.test.mjs

import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HOOK = '.claude/hooks/guard-signing-secrets.mjs (Android repo)';

const REASON =
    `Blocked by ${HOOK}: the release signing key and its passwords never load into a Claude session, and a session ` +
    'never creates, edits, moves or deletes them (keystore.properties, *.jks / *.keystore / *.p12 / *.pfx, anything ' +
    'under .android-keys). Gradle and scripts/prod-release.ps1 read them themselves; the certificate fingerprints come ' +
    'from `apksigner verify --print-certs` on the built APK. Ask the owner to change one. To see whether a file exists, ' +
    'use the Glob tool.';

const FILE_TOOLS = new Set(['Read', 'Edit', 'Write', 'MultiEdit', 'NotebookEdit']);
const SHELL_TOOLS = new Set(['Bash', 'PowerShell', 'Monitor']);
const SAMPLE_NAMES = ['keystore.properties', 'release.jks', 'debug.keystore', 'key.p12', 'key.pfx', '.android-keys/x'];

export function isSecretPath(name) {
    const segments = String(name).replace(/^["'`]+|["'`]+$/g, '').split(/[\\/]+/).filter(Boolean);
    if (segments.some(segment => /^\.android-keys$/i.test(segment))) return true;
    const base = segments.pop() ?? '';
    return /^keystore\.properties$/i.test(base) || /\.(jks|keystore|p12|pfx)$/i.test(base);
}

// Free text: a word ending in one of the names, or naming the key directory. `KeystoreCipher.kt`, `AndroidKeyStore`
// and a package like `android.security.keystore.KeyGenParameterSpec` don't count; `keystore.properties.example`
// doesn't either.
export function mentionsSecret(text) {
    const value = String(text);
    return /(?<![\w-])keystore\.properties(?![\w-]|\.\w)/i.test(value) ||
        /\.(jks|keystore|p12|pfx)(?![\w-]|\.\w)/i.test(value) ||
        /(?<![\w-])\.android-keys(?![\w-])/i.test(value);
}

// Inside an expandable message these run code: `-m "$(cat keystore.properties)"` reads the file into the commit.
const EXPANDS = /\$\(|\$\{|`/;
const FLAG = String.raw`(?<![\w-])(-m|--message)`;
const QUOTED_HERE_STRING = new RegExp(String.raw`${FLAG}(\s+)@'\r?\n[\s\S]*?\r?\n'@`, 'g');
const EXPANDABLE_HERE_STRING = new RegExp(String.raw`${FLAG}(\s+)@"\r?\n([\s\S]*?)\r?\n"@`, 'g');
const QUOTED_HEREDOC = new RegExp(
    String.raw`${FLAG}(\s+)"\$\(cat\s+<<-?\s*(['"])(\w+)\3\s*\r?\n[\s\S]*?\r?\n\s*\4\s*\r?\n?\s*\)"`,
    'g',
);
const SINGLE_QUOTED = new RegExp(String.raw`${FLAG}(=|\s+)'(?:[^']|'')*'`, 'g');
const DOUBLE_QUOTED = new RegExp(String.raw`${FLAG}(=|\s+)"((?:[^"\\` + '`' + String.raw`]|\\.|` + '`' + String.raw`.)*)"`, 'g');

// Drops the text of `git commit` / `git tag` messages, keeping the flag, so only the rest of the line is judged.
export function withoutCommitMessages(command) {
    if (!/\bgit\b[\s\S]*\b(?:commit|tag)\b/.test(command)) return command;
    function keepIfExpands(all, flag, gap, body) {
        return EXPANDS.test(body) ? all : `${flag}${gap}''`;
    }
    return command
        .replace(QUOTED_HERE_STRING, "$1$2''")
        .replace(EXPANDABLE_HERE_STRING, keepIfExpands)
        .replace(QUOTED_HEREDOC, "$1$2''")
        .replace(SINGLE_QUOTED, "$1$2''")
        .replace(DOUBLE_QUOTED, keepIfExpands);
}

function globToRegExp(glob) {
    let source = '';
    for (let i = 0; i < glob.length; i++) {
        const c = glob[i];
        if (c === '*' && glob[i + 1] === '*') {
            source += glob[i + 2] === '/' ? '(?:.*/)?' : '.*';
            i += glob[i + 2] === '/' ? 2 : 1;
        } else if (c === '*') source += '[^/]*';
        else if (c === '?') source += '[^/]';
        else if (c === '{') source += '(?:';
        else if (c === '}') source += ')';
        else if (c === ',') source += '|';
        else source += c.replace(/[.+^$()|[\]\\]/g, '\\$&');
    }
    return new RegExp(`^${source}$`, 'i');
}

// The Grep tool splits its glob on whitespace, and on commas outside braces, into ripgrep `--glob`s.
function globTokens(glob) {
    return String(glob ?? '')
        .split(/\s+/)
        .filter(Boolean)
        .flatMap(token => (token.includes('{') ? [token] : token.split(',').filter(Boolean)));
}

function judgeGrep(toolInput) {
    if (typeof toolInput.path === 'string' && isSecretPath(toolInput.path)) return { decision: 'deny' };
    const positive = globTokens(toolInput.glob).filter(token => !token.startsWith('!'));
    const targets = positive.some(token => {
        // `**/*` and its like match everything; ripgrep's ignore rules still keep these files out, so only a glob
        // that names one of them is judged.
        if (/^(\*\*\/)?\*(\.\*)?$/.test(token)) return false;
        const pattern = globToRegExp(token.includes('/') ? token : `**/${token}`);
        return SAMPLE_NAMES.some(name => pattern.test(name) || pattern.test(`dir/${name}`));
    });
    return targets ? { decision: 'deny' } : null;
}

// MCP arguments that name a file or run a command; free text (a file's new content, a message) is not judged.
function mcpArgumentStrings(value, key = '') {
    if (typeof value === 'string') return /path|file|command|cmd|dir|args|cwd/i.test(key) ? [value] : [];
    if (Array.isArray(value)) return value.flatMap(item => mcpArgumentStrings(item, key));
    if (value && typeof value === 'object') {
        return Object.entries(value).flatMap(([childKey, child]) => mcpArgumentStrings(child, childKey));
    }
    return [];
}

export function decide(input) {
    const tool = String(input?.tool_name ?? '');
    const toolInput = input?.tool_input;
    if (!toolInput || typeof toolInput !== 'object') return null;

    if (FILE_TOOLS.has(tool)) {
        const file = toolInput.file_path ?? toolInput.notebook_path;
        return typeof file === 'string' && isSecretPath(file) ? { decision: 'deny' } : null;
    }
    if (tool === 'Grep') return judgeGrep(toolInput);
    if (SHELL_TOOLS.has(tool)) {
        return typeof toolInput.command === 'string' && mentionsSecret(withoutCommitMessages(toolInput.command))
            ? { decision: 'deny' }
            : null;
    }
    if (tool.startsWith('mcp__')) {
        return mcpArgumentStrings(toolInput).some(text => mentionsSecret(withoutCommitMessages(text)))
            ? { decision: 'deny' }
            : null;
    }
    return null;
}

function main() {
    let verdict = null;
    try {
        verdict = decide(JSON.parse(readFileSync(0, 'utf8')));
    } catch {
        verdict = null;
    }
    if (verdict) {
        process.stdout.write(JSON.stringify({
            hookSpecificOutput: {
                hookEventName: 'PreToolUse',
                permissionDecision: 'deny',
                permissionDecisionReason: REASON,
            },
        }));
    }
    process.exit(0);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
