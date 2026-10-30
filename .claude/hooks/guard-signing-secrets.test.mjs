#!/usr/bin/env node
// Pipes sample PreToolUse JSON through guard-signing-secrets.mjs the way Claude Code does, and checks the decision.
// Run: node .claude/hooks/guard-signing-secrets.test.mjs   (exit 0 = all pass)

import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const script = path.join(here, 'guard-signing-secrets.mjs');

const PS = 'PowerShell';
const SH = 'Bash';
const ANDROID = 'C:\\Users\\jakub\\AndroidStudioProjects\\AdhdTimeOrganizerActivityTrackingAndroid';
const KEYS = 'C:\\Users\\jakub\\.android-keys';

// [tool, tool_input, expected] — expected is 'deny' or 'allow' (no output).
const CASES = [
    // File tools
    ['Read', { file_path: `${ANDROID}\\keystore.properties` }, 'deny'],
    ['Read', { file_path: `${ANDROID}/KEYSTORE.PROPERTIES` }, 'deny'],
    ['Write', { file_path: `${ANDROID}\\keystore.properties`, content: 'storeFile=x' }, 'deny'],
    ['Edit', { file_path: `${ANDROID}\\keystore.properties`, old_string: 'a', new_string: 'b' }, 'deny'],
    ['Read', { file_path: `${KEYS}\\adhd-tracker-release.jks` }, 'deny'],
    ['Write', { file_path: `${KEYS}\\notes.txt`, content: 'x' }, 'deny'],
    ['Read', { file_path: 'C:/Users/jakub/.android/debug.keystore' }, 'deny'],
    ['Read', { file_path: 'D:/backup/key.P12' }, 'deny'],
    ['MultiEdit', { file_path: 'C:/x/release.pfx', edits: [] }, 'deny'],
    ['Read', { file_path: `${ANDROID}\\app\\build.gradle.kts` }, 'allow'],
    ['Read', { file_path: `${ANDROID}\\scripts\\prod-release.ps1` }, 'allow'],
    ['Read', { file_path: `${ANDROID}\\app\\src\\main\\java\\com\\timeorganizer\\usagetracker\\auth\\KeystoreCipher.kt` }, 'allow'],
    ['Read', { file_path: `${ANDROID}\\keystore.properties.example` }, 'allow'],
    ['Edit', { file_path: `${ANDROID}\\CLAUDE.md`, old_string: 'a', new_string: 'reads keystore.properties' }, 'allow'],
    ['Read', { file_path: `${ANDROID}\\local.properties` }, 'allow'],

    // Grep
    ['Grep', { pattern: 'Password', path: `${ANDROID}\\keystore.properties` }, 'deny'],
    ['Grep', { pattern: 'x', path: KEYS }, 'deny'],
    ['Grep', { pattern: 'x', path: ANDROID, glob: 'keystore.*' }, 'deny'],
    ['Grep', { pattern: 'x', path: ANDROID, glob: '*.jks' }, 'deny'],
    ['Grep', { pattern: 'x', path: ANDROID, glob: '*.properties' }, 'deny'],
    ['Grep', { pattern: 'x', path: ANDROID }, 'allow'],
    ['Grep', { pattern: 'x', path: ANDROID, glob: '**/*' }, 'allow'],
    ['Grep', { pattern: 'x', path: ANDROID, glob: '*.{kt,kts}' }, 'allow'],
    ['Grep', { pattern: 'keystore\\.properties', path: ANDROID, glob: '*.kts' }, 'allow'],
    ['Grep', { pattern: 'x', path: ANDROID, type: 'kotlin' }, 'allow'],

    // Shell
    [PS, 'Get-Content keystore.properties', 'deny'],
    [PS, `Select-String -Path ${ANDROID}\\keystore.properties -Pattern '^storePassword=\\S' -Quiet`, 'deny'],
    [PS, `Test-Path "$env:USERPROFILE\\.android-keys\\adhd-tracker-release.jks"`, 'deny'],
    [PS, 'Get-ChildItem -Recurse -Filter *.jks', 'deny'],
    [PS, 'Remove-Item "$env:USERPROFILE\\.android-keys" -Recurse', 'deny'],
    [PS, 'Copy-Item a.txt keystore.properties', 'deny'],
    [PS, 'keytool -list -keystore release.keystore', 'deny'],
    [SH, 'cat keystore.properties', 'deny'],
    [SH, 'ls ~/.android-keys', 'deny'],
    [PS, 'git add keystore.properties', 'deny'],
    ['Monitor', { command: 'Get-Content -Wait keystore.properties' }, 'deny'],
    [PS, 'pwsh -NoProfile -File scripts\\prod-release.ps1 -ServerDeployed -DryRun', 'allow'],
    [PS, '.\\gradlew.bat testDebugUnitTest assembleRelease', 'allow'],
    [PS, 'apksigner verify --print-certs app\\build\\outputs\\apk\\release\\app-release.apk', 'allow'],
    [PS, "Select-String -Path app\\src\\main\\java\\x\\KeystoreCipher.kt -Pattern 'android.security.keystore.KeyGenParameterSpec'", 'allow'],
    [PS, 'git status --short', 'allow'],
    [PS, 'git check-ignore -v CHANGELOG.md', 'allow'],

    // Commit and tag messages may talk about it; anything that runs inside one, or outside it, still counts
    [PS, "git commit -m 'build: read keystore.properties' -- app/build.gradle.kts", 'allow'],
    [PS, "git commit -m @'\nchore: ignore *.jks and keystore.properties\n'@ -- .gitignore", 'allow'],
    [SH, "git commit -m \"$(cat <<'EOF'\nbuild: the .android-keys folder\nEOF\n)\"", 'allow'],
    [SH, 'git commit -m "$(cat keystore.properties)"', 'deny'],
    [PS, "git commit -m 'about it' -- keystore.properties", 'deny'],

    // MCP
    ['mcp__rider__read_file', { pathInProject: 'keystore.properties' }, 'deny'],
    ['mcp__rider__execute_terminal_command', { command: 'type keystore.properties' }, 'deny'],
    ['mcp__rider__create_new_file', { pathInProject: 'docs/x.md', text: 'fill keystore.properties' }, 'allow'],
    ['mcp__rider__read_file', { pathInProject: 'app/build.gradle.kts' }, 'allow'],
];

// Raw stdin strings: everything here must allow (fail open) with exit 0.
const MALFORMED = [
    '',
    'not json',
    '{',
    '{}',
    'null',
    '[]',
    '{"tool_name":"Read"}',
    '{"tool_name":"Read","tool_input":null}',
    '{"tool_name":"Read","tool_input":{"file_path":42}}',
    '{"tool_name":"Bash","tool_input":{"command":42}}',
];

function run(stdin) {
    const result = spawnSync(process.execPath, [script], { input: stdin, encoding: 'utf8' });
    let decision = 'allow';
    let output = null;
    if (result.stdout.trim()) {
        output = JSON.parse(result.stdout).hookSpecificOutput;
        decision = output.permissionDecision;
    }
    return { status: result.status, decision, output, stderr: result.stderr };
}

let failures = 0;

for (const [tool, raw, expected] of CASES) {
    const toolInput = typeof raw === 'string' ? { command: raw } : raw;
    const input = JSON.stringify({
        session_id: 'test',
        hook_event_name: 'PreToolUse',
        cwd: ANDROID,
        tool_name: tool,
        tool_input: toolInput,
    });
    const got = run(input);
    let ok = got.status === 0 && got.decision === expected;
    if (ok && expected === 'deny') ok = got.output.permissionDecisionReason.startsWith('Blocked by');
    if (!ok) {
        failures++;
        console.log(`FAIL [${tool}] ${JSON.stringify(toolInput)} → ${got.decision} (exit ${got.status}), expected ${expected}`);
        if (got.stderr) console.log(got.stderr);
    }
}

for (const stdin of MALFORMED) {
    const got = run(stdin);
    if (got.status !== 0 || got.decision !== 'allow') {
        failures++;
        console.log(`FAIL malformed ${JSON.stringify(stdin)} → ${got.decision} (exit ${got.status})`);
    }
}

const total = CASES.length + MALFORMED.length;
console.log(failures ? `${failures} of ${total} failed` : `all ${total} passed`);
process.exit(failures ? 1 : 0);
