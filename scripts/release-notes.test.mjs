import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { suite, test } from 'node:test';
import { fileURLToPath } from 'node:url';

import {
  createReleaseBody,
  findChangelogSectionRange,
  normalizeReleaseVersion,
  resolveReleaseNotesSection,
} from './release-notes.mjs';

suite('normalizeReleaseVersion', () => {
  test('should map an RC tag to its base release version', () => {
    assert.equal(normalizeReleaseVersion('v1.14.0-rc.2'), '1.14.0');
  });

  test('should preserve stable and old-line hotfix versions', () => {
    assert.equal(normalizeReleaseVersion('v1.14.0'), '1.14.0');
    assert.equal(normalizeReleaseVersion('1.12.2'), '1.12.2');
  });

  test('should reject unsupported version formats', () => {
    assert.throws(() => normalizeReleaseVersion('1.14'), /Unsupported release version/);
    assert.throws(() => normalizeReleaseVersion('1.14.0-beta.1'), /Unsupported release version/);
  });
});

suite('resolveReleaseNotesSection', () => {
  test('should select Unreleased for a nightly build', () => {
    assert.equal(resolveReleaseNotesSection('nightly', '1.14.0'), 'Unreleased');
  });

  test('should select the base version for RC and stable releases', () => {
    assert.equal(resolveReleaseNotesSection('release', 'v1.14.0-rc.2'), '1.14.0');
    assert.equal(resolveReleaseNotesSection('release', 'v1.14.0'), '1.14.0');
  });
});

suite('findChangelogSectionRange', () => {
  test('should return the exact source lines for a release section', () => {
    const changelog = [
      '## [Unreleased]',
      '',
      '- Nightly note',
      '',
      '## [1.14.0]',
      '',
      '### Bug Fixes',
      '',
      '- Fixed an auth issue.',
      '',
      '## [1.13.1]',
    ].join('\n');

    assert.deepEqual(findChangelogSectionRange(changelog, '1.14.0'), {
      startLine: 5,
      endLine: 9,
    });
  });

  test('should reject a missing changelog section', () => {
    assert.throws(
      () => findChangelogSectionRange('## [Unreleased]\r\n', '1.14.0'),
      /Changelog section is missing/,
    );
  });
});

suite('createReleaseBody', () => {
  test('should append an immutable link to the exact changelog section', () => {
    const commit = '0123456789abcdef0123456789abcdef01234567';

    assert.equal(
      createReleaseBody('### Bug Fixes\n\n- Fixed auth.', {
        repository: 'microsoft/copilot-intellij',
        commit,
        startLine: 10,
        endLine: 14,
      }),
      [
        '### Bug Fixes',
        '',
        '- Fixed auth.',
        '',
        '---',
        '',
        `_Release notes source: [CHANGELOG.md at 0123456](https://github.com/microsoft/copilot-intellij/blob/${commit}/CHANGELOG.md?plain=1#L10-L14)._`,
        '',
      ].join('\n'),
    );
  });

  test('should reject empty release notes', () => {
    assert.throws(
      () =>
        createReleaseBody(' \n', {
          repository: 'microsoft/copilot-intellij',
          commit: '0123456789abcdef0123456789abcdef01234567',
          startLine: 1,
          endLine: 1,
        }),
      /Release notes are empty/,
    );
  });

  test('should reject repository links in reviewed release-note content', () => {
    assert.throws(
      () =>
        createReleaseBody('- Internal details: https://github.com/jiec-msft/cls-runner-poc/issues/1', {
          repository: 'jiec-msft/cls-runner-poc',
          commit: '0123456789abcdef0123456789abcdef01234567',
          startLine: 1,
          endLine: 1,
        }),
      /Release notes must not contain repository links/,
    );
  });
});

suite('release-notes CLI', () => {
  test('should print the selected section for workflow use', () => {
    const script = fileURLToPath(new URL('./release-notes.mjs', import.meta.url));
    const result = spawnSync(process.execPath, [script, 'section', 'release', 'v1.14.0-rc.2'], {
      encoding: 'utf8',
    });

    assert.equal(result.status, 0);
    assert.equal(result.stdout.trim(), '1.14.0');
  });

  test('should write a complete release body for workflow use', () => {
    const directory = mkdtempSync(join(tmpdir(), 'release-notes-'));
    const changelogPath = join(directory, 'CHANGELOG.md');
    const notesPath = join(directory, 'notes.md');
    const outputPath = join(directory, 'body.md');
    const commit = '0123456789abcdef0123456789abcdef01234567';

    try {
      writeFileSync(changelogPath, '## [Unreleased]\n\n## [1.14.0]\n\n- Fixed auth.\n');
      writeFileSync(notesPath, '- Fixed auth.\n');

      const script = fileURLToPath(new URL('./release-notes.mjs', import.meta.url));
      const result = spawnSync(
        process.execPath,
        [
          script,
          'body',
          '--changelog',
          changelogPath,
          '--notes',
          notesPath,
          '--section',
          '1.14.0',
          '--repository',
          'microsoft/copilot-intellij',
          '--commit',
          commit,
          '--output',
          outputPath,
        ],
        { encoding: 'utf8' },
      );

      assert.equal(result.status, 0, result.stderr);
      assert.match(readFileSync(outputPath, 'utf8'), /CHANGELOG\.md\?plain=1#L3-L5/);
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  });
});
