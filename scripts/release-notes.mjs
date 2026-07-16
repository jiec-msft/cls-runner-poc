import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { pathToFileURL } from 'node:url';

export function normalizeReleaseVersion(version) {
  const match = /^v?(\d+\.\d+\.\d+)(?:-rc\.\d+)?$/.exec(version);
  if (!match) {
    throw new Error(`Unsupported release version: ${version}`);
  }

  return match[1];
}

export function resolveReleaseNotesSection(releaseKind, version) {
  if (releaseKind === 'nightly') {
    return 'Unreleased';
  }
  if (releaseKind === 'release') {
    return normalizeReleaseVersion(version);
  }

  throw new Error(`Unsupported release kind: ${releaseKind}`);
}

export function findChangelogSectionRange(changelog, section) {
  const lines = changelog.split(/\r?\n/);
  const heading = `## [${section}]`;
  const startIndex = lines.findIndex((line) => line.trim() === heading);
  if (startIndex < 0) {
    throw new Error(`Changelog section is missing: ${heading}`);
  }

  const nextHeadingOffset = lines
    .slice(startIndex + 1)
    .findIndex((line) => line.startsWith('## '));
  const nextHeadingIndex =
    nextHeadingOffset < 0 ? lines.length : startIndex + 1 + nextHeadingOffset;

  let endIndex = nextHeadingIndex - 1;
  while (endIndex > startIndex && lines[endIndex].trim() === '') {
    endIndex -= 1;
  }

  return {
    startLine: startIndex + 1,
    endLine: endIndex + 1,
  };
}

export function createReleaseBody(
  releaseNotes,
  { repository, commit, startLine, endLine, serverUrl = 'https://github.com' },
) {
  const notes = releaseNotes.trim();
  if (!notes) {
    throw new Error('Release notes are empty');
  }

  const sourceUrl =
    `${serverUrl}/${repository}/blob/${commit}/CHANGELOG.md` +
    `?plain=1#L${startLine}-L${endLine}`;
  const source = `_Release notes source: [CHANGELOG.md at ${commit.slice(0, 7)}](${sourceUrl})._`;

  return `${notes}\n\n---\n\n${source}\n`;
}

function parseOptions(args) {
  const options = {};
  for (let index = 0; index < args.length; index += 2) {
    const name = args[index];
    const value = args[index + 1];
    if (!name?.startsWith('--') || value === undefined) {
      throw new Error(`Invalid option list: ${args.join(' ')}`);
    }
    options[name.slice(2)] = value;
  }
  return options;
}

function requireOption(options, name) {
  const value = options[name];
  if (!value) {
    throw new Error(`Missing required option: --${name}`);
  }
  return value;
}

function runCli(args) {
  const [command, ...commandArgs] = args;
  if (command === 'section') {
    const [releaseKind, version] = commandArgs;
    console.log(resolveReleaseNotesSection(releaseKind, version));
    return;
  }

  if (command === 'body') {
    const options = parseOptions(commandArgs);
    const changelogPath = requireOption(options, 'changelog');
    const notesPath = requireOption(options, 'notes');
    const section = requireOption(options, 'section');
    const repository = requireOption(options, 'repository');
    const commit = requireOption(options, 'commit');
    const outputPath = requireOption(options, 'output');
    const range = findChangelogSectionRange(readFileSync(changelogPath, 'utf8'), section);
    const body = createReleaseBody(readFileSync(notesPath, 'utf8'), {
      repository,
      commit,
      ...range,
      serverUrl: options['server-url'],
    });

    mkdirSync(dirname(outputPath), { recursive: true });
    writeFileSync(outputPath, body);
    console.log(JSON.stringify({ section, ...range, outputPath }));
    return;
  }

  throw new Error(`Unsupported command: ${command ?? '<missing>'}`);
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  try {
    runCli(process.argv.slice(2));
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
