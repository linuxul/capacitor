import { copyFileSync, existsSync, mkdirpSync, readFileSync, writeFileSync } from 'fs-extra';
import { dirname, join, relative } from 'path';

import c from '../colors';
import { runTask } from '../common';
import type { Config } from '../definitions';
import { logger } from '../log';
import { convertToUnixPath } from '../util/fs';
import { withExtractedTemplate } from '../util/template';

import { debugBuildDeclaresCleartext } from './cleartext';
import { findLegacyCordovaGradleLines, legacyCordovaPluginsDir } from './update';

/**
 * Does the Android half of the "Updating an existing Android app" section of BREAKING.md:
 * takes the leftover Cordova references out of the Gradle files, and adds the debug manifest
 * that keeps live reload working over http.
 *
 * The contract for the Gradle part is all or nothing per file: either the file comes back with
 * no mention of the Cordova project left in it, so `checkLegacyCordovaGradleReferences` passes,
 * or it is left byte for byte as it was and the user gets the same list of lines that check
 * would have stopped on.
 *
 * Has to run before `cap sync`, because `cap update android` stops with an error while the
 * Gradle references are still there.
 */
export async function cleanupLegacyCordovaAndroid(config: Config): Promise<void> {
  await runTask(`Removing Cordova references from the Android Gradle files.`, async () => {
    rewriteGradleFile(
      config,
      join(config.android.platformDirAbs, 'settings.gradle'),
      removeLegacyCordovaFromSettingsGradle,
    );
    rewriteGradleFile(config, join(config.android.appDirAbs, 'build.gradle'), removeLegacyCordovaFromAppBuildGradle);
  });

  await runTask(`Adding the debug AndroidManifest.xml that allows cleartext live reload.`, () => {
    return addDebugManifestIfMissing(config);
  });
}

function rewriteGradleFile(config: Config, path: string, rewrite: (source: string) => GradleRewrite): void {
  if (!existsSync(path)) {
    return;
  }
  const name = c.strong(convertToUnixPath(relative(config.app.rootDir, path)));
  const original = readFileSync(path);
  const source = original.toString('utf-8');
  if (!source.includes(legacyCordovaPluginsDir)) {
    return;
  }

  // Gradle reads build scripts in the JVM's default charset, so a CP949 or Windows-1252
  // build.gradle is a real thing to find. Decoding one as UTF-8 turns every byte that is not
  // valid UTF-8 into U+FFFD, and writing that back would destroy it, so the round trip through
  // UTF-8 has to come out byte for byte before anything is rewritten.
  const isUtf8 = Buffer.from(source, 'utf-8').equals(original);
  const result = isUtf8
    ? rewrite(source)
    : { contents: source, changed: false, unhandled: describeLegacyLines(source) };

  if (result.unhandled.length > 0) {
    const reason = isUtf8
      ? `it references ${c.strong(legacyCordovaPluginsDir)} in a way this migration cannot rewrite safely`
      : `it is not valid UTF-8, and rewriting it would replace every byte this cannot decode`;
    logger.warn(
      `${name} was left untouched: ${reason}. ${c.input('npx cap sync')} keeps failing until these lines are ` +
        `removed by hand:\n` +
        result.unhandled.map((line) => `    ${line}`).join('\n'),
    );
    return;
  }
  if (!result.changed) {
    return;
  }
  writeFileSync(path, result.contents, { encoding: 'utf-8' });
  logger.info(`Removed the ${c.strong(legacyCordovaPluginsDir)} references from ${name}.`);
}

/**
 * Live reload serves over http. Nothing generates a Cordova manifest with `usesCleartextTraffic`
 * anymore, so an app that predates its removal needs the same debug manifest new apps get. It is
 * copied out of the shipped Android template, so it can never drift from what a new app gets.
 *
 * An app that already says how cleartext traffic is handled is left alone: the template manifest
 * declares `usesCleartextTraffic="true"`, and adding that on top of a manifest that declares it
 * false, or that points at a network security config, would fail the manifest merger. A flavoured
 * project keeps its own debug manifest in `src/<flavor>Debug` rather than `src/debug`, so the
 * question goes to every source set that ends up in a debug build, not only `src/main`. One that
 * carries a manifest but says nothing about cleartext is no obstacle: the two merge.
 */
export async function addDebugManifestIfMissing(config: Config): Promise<boolean> {
  const manifestPath = join(config.android.srcDirAbs, 'debug', 'AndroidManifest.xml');
  if (existsSync(manifestPath)) {
    return false;
  }
  if (await debugBuildDeclaresCleartext(config)) {
    logger.info(
      `Did not add ${c.strong(convertToUnixPath(relative(config.app.rootDir, manifestPath)))}: the app's own ` +
        `${c.strong('AndroidManifest.xml')} already declares how cleartext traffic is handled, and a debug ` +
        `manifest saying otherwise would fail the manifest merger. See BREAKING.md, "Cleartext traffic".`,
    );
    return false;
  }

  try {
    return await withExtractedTemplate(config.cli.assets.android.platformTemplateArchiveAbs, (dir) => {
      const templatePath = join(dir, 'app', 'src', 'debug', 'AndroidManifest.xml');
      if (!existsSync(templatePath)) {
        warnAboutDebugManifest(config, manifestPath, `it is missing from the shipped Android template`);
        return false;
      }
      mkdirpSync(dirname(manifestPath));
      copyFileSync(templatePath, manifestPath);
      return true;
    });
  } catch (e) {
    warnAboutDebugManifest(config, manifestPath, e instanceof Error ? e.message : String(e));
    return false;
  }
}

function warnAboutDebugManifest(config: Config, manifestPath: string, reason: string): void {
  logger.warn(
    `Could not create ${c.strong(convertToUnixPath(relative(config.app.rootDir, manifestPath)))} (${reason}). ` +
      `Live reload serves over http, so create it by hand with an ` +
      `${c.strong('<application android:usesCleartextTraffic="true" />')} element, otherwise the app shows a ` +
      `blank screen. See BREAKING.md, "Cleartext traffic".`,
  );
}

export interface GradleRewrite {
  /** The rewritten file, or the source untouched when nothing was or could be changed. */
  contents: string;
  /** True when `contents` differs from the source. */
  changed: boolean;
  /**
   * References the rewrite did not dare to touch, as `"<line number>: <line>"`. When this is not
   * empty the file is left exactly as it was, so the lines can be removed by hand.
   */
  unhandled: string[];
}

const legacyCordovaProjectPath = `:${legacyCordovaPluginsDir}`;

function escapeForRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

const LEGACY_PROJECT = escapeForRegExp(legacyCordovaProjectPath);

/**
 * The whole of `implementation project(':capacitor-cordova-android-plugins')`, in any of its
 * spellings: another configuration name, the `path:` form, either quote style, the whole thing
 * wrapped in the configuration's own parentheses.
 *
 * Both patterns are matched against what `readStatementLine` hands back - one line's worth of
 * code, trimmed, with its comments blanked and its trailing semicolon taken off - and are anchored
 * at both ends, so a line that carries anything else never matches.
 */
const legacyProjectDependencyLine = new RegExp(
  `^[A-Za-z_][A-Za-z0-9_]*(?:` +
    `\\s+project\\s*\\(\\s*(?:path\\s*:\\s*)?['"]${LEGACY_PROJECT}['"]\\s*\\)` +
    `|\\s*\\(\\s*project\\s*\\(\\s*(?:path\\s*:\\s*)?['"]${LEGACY_PROJECT}['"]\\s*\\)\\s*\\)` +
    `)$`,
);

/**
 * The whole of `project(':capacitor-cordova-android-plugins').projectDir = new File(...)`, the
 * statement settings.gradle uses to point the project at its directory. The value has to be on the
 * line: `projectDir =` with the value below it is a statement this cannot take out by the line.
 */
const legacyProjectSettingsLine = new RegExp(
  `^project\\s*\\(\\s*['"]${LEGACY_PROJECT}['"]\\s*\\)\\s*\\.\\s*[A-Za-z_][A-Za-z0-9_]*\\s*=\\s*\\S.*$`,
);

/**
 * The whole of `include ':capacitor-cordova-android-plugins'`, naming that project and nothing
 * else. A live include is taken out by filtering its argument list, which keeps the other projects
 * it names; this is what recognises the statement when the sweep finds one commented out.
 */
const legacyIncludeLine = new RegExp(
  `^include\\s*(?:\\(\\s*['"]${LEGACY_PROJECT}['"]\\s*\\)|['"]${LEGACY_PROJECT}['"])$`,
);

/**
 * The opening delimiter of Groovy's dollar-slashy string, `$/ ... /$`. `scanGradle` does not model
 * that form - and teaching the scanner one more string form is how this class of bug keeps coming
 * back - so its contents would be read as code and a legacy statement quoted inside one would be
 * cut out of the middle of a string. A file that holds the delimiter anywhere is handed back
 * untouched instead, with every offending line reported.
 */
const DOLLAR_SLASHY_STRING = '$/';

/**
 * A UTF-8 BOM is bytes the user wrote rather than content to be rewritten, so no cut ever starts
 * in front of it. This is what "the start of the file" means everywhere below.
 */
const BYTE_ORDER_MARK = '﻿';

function contentStart(source: string): number {
  return source.startsWith(BYTE_ORDER_MARK) ? BYTE_ORDER_MARK.length : 0;
}

interface GradleString {
  start: number;
  /** Index just past the closing quote. */
  end: number;
  /** The raw text between the quotes. */
  value: string;
}

interface GradleComment {
  start: number;
  /** Index just past the comment. A `//` comment stops before the line terminator. */
  end: number;
  /** True for a `//` comment, false for a block comment. */
  toEndOfLine: boolean;
}

interface ScannedGradle {
  /** The source with every comment and string blanked to spaces, newlines kept. */
  mask: string;
  /** The source with only the comments blanked, so string literals still stand out. */
  code: string;
  /** Every string literal, keyed by the index of its opening quote. */
  strings: Map<number, GradleString>;
  comments: GradleComment[];
}

interface GradleName {
  start: number;
  /** Index just past the name. */
  end: number;
}

interface GradleBlock {
  nameStart: number;
  braceStart: number;
  /** Index of the closing brace. */
  braceEnd: number;
}

interface Cut {
  start: number;
  end: number;
  /**
   * True for a cut that is meant to take whole lines. Those are checked for line alignment
   * before anything is written, which catches an off-by-one in a cut boundary.
   */
  wholeLines?: boolean;
}

interface DirsStatement {
  nameStart: number;
  /** Index just past the argument list. */
  end: number;
  entries: GradleString[];
}

type EntryCuts = { kind: 'cuts'; cuts: Cut[] } | { kind: 'all' } | { kind: 'decline' };

/**
 * Removes `include ':capacitor-cordova-android-plugins'` and the `project(...)` statement that
 * goes with it, filtering the argument list when the include names several projects at once.
 */
export function removeLegacyCordovaFromSettingsGradle(source: string): GradleRewrite {
  if (source.includes(DOLLAR_SLASHY_STRING)) {
    return declineWholeFile(source);
  }
  const scan = scanGradle(source);
  const cuts: Cut[] = [];

  for (const include of findNames(scan, 'include')) {
    const list = parseStringList(scan, include.end, scan.mask.length);
    if (!list?.entries.some(isLegacyProjectEntry)) {
      continue;
    }
    const dropped = cutsForDroppedEntries(list.entries, isLegacyProjectEntry, scan.comments);
    if (dropped.kind === 'cuts') {
      cuts.push(...dropped.cuts);
    } else if (dropped.kind === 'all') {
      pushCut(cuts, cutWholeLines(source, scan, include.start, list.end, cuts));
    }
    // 'decline': a comment sits between the entries. The sweep reports the line instead.
  }

  return finish(source, cuts, sweepLeftovers(source, scan, cuts, [legacyProjectSettingsLine, legacyIncludeLine]));
}

/**
 * Filters the Cordova directory out of every `flatDir { dirs ... }` and removes the
 * `implementation project(':capacitor-cordova-android-plugins')` dependency. A `flatDir` left with
 * no directories goes away, and a `repositories` block left with nothing in it goes with it. Other
 * `dirs` entries stay, so an app that loads `.aar` files out of `app/libs` keeps its
 * `flatDir { dirs 'libs' }`, as BREAKING.md asks.
 */
export function removeLegacyCordovaFromAppBuildGradle(source: string): GradleRewrite {
  if (source.includes(DOLLAR_SLASHY_STRING)) {
    return declineWholeFile(source);
  }
  const scan = scanGradle(source);
  const cuts: Cut[] = [];
  const emptiedFlatDirs: GradleBlock[] = [];

  for (const flatDir of findBlocks(scan, 'flatDir')) {
    if (!source.slice(flatDir.braceStart + 1, flatDir.braceEnd).includes(legacyCordovaPluginsDir)) {
      continue;
    }
    const statements = readDirsStatements(scan, flatDir);
    if (statements === null || statements.length === 0) {
      // Not a plain list of directories: the sweep reports it and the file is left alone.
      continue;
    }

    const entryCuts: Cut[] = [];
    const emptied: DirsStatement[] = [];
    let declined = false;

    for (const statement of statements) {
      if (!statement.entries.some(isLegacyDirEntry)) {
        continue;
      }
      const dropped = cutsForDroppedEntries(statement.entries, isLegacyDirEntry, scan.comments);
      if (dropped.kind === 'cuts') {
        entryCuts.push(...dropped.cuts);
      } else if (dropped.kind === 'all') {
        emptied.push(statement);
      } else {
        declined = true;
      }
    }

    if (declined) {
      continue;
    }
    if (emptied.length === statements.length) {
      // Every directory the block declared goes, so the block itself has no reason to stay -
      // but only when nothing else lives in it. A hand-written `content { ... }` filter is
      // somebody's own configuration and is not ours to delete.
      if (isFlatDirBodyOnlyDirs(source, scan, flatDir, statements)) {
        emptiedFlatDirs.push(flatDir);
      }
      continue;
    }
    cuts.push(...entryCuts);
    for (const statement of emptied) {
      pushCut(cuts, cutWholeLines(source, scan, statement.nameStart, statement.end, cuts));
    }
  }

  cutsForEmptiedFlatDirs(source, scan, emptiedFlatDirs, cuts);

  return finish(source, cuts, sweepLeftovers(source, scan, cuts, [legacyProjectDependencyLine]));
}

/**
 * Cuts the `flatDir` blocks that lost every directory, taking the enclosing `repositories` block
 * with them when the `flatDir` was all it held. A comment left inside `repositories` keeps it:
 * deleting somebody's note is worse than leaving an empty block behind.
 */
function cutsForEmptiedFlatDirs(source: string, scan: ScannedGradle, emptied: GradleBlock[], cuts: Cut[]): void {
  if (emptied.length === 0) {
    return;
  }
  const repositories = findBlocks(scan, 'repositories');
  const grouped = new Map<GradleBlock, GradleBlock[]>();

  for (const flatDir of emptied) {
    // The innermost enclosing `repositories`, so two nested ones can never both be cut.
    const enclosing = repositories
      .filter((block) => contains(block, flatDir))
      .reduce<GradleBlock | null>((closest, block) => {
        return closest === null || block.braceStart > closest.braceStart ? block : closest;
      }, null);

    if (!enclosing) {
      pushCut(cuts, cutWholeLines(source, scan, flatDir.nameStart, flatDir.braceEnd + 1, cuts));
      continue;
    }
    grouped.set(enclosing, [...(grouped.get(enclosing) ?? []), flatDir]);
  }

  for (const [block, inner] of grouped) {
    if (isBodyEmptyWithout(source, block, inner)) {
      pushCut(cuts, cutWholeLines(source, scan, block.nameStart, block.braceEnd + 1, cuts));
    } else {
      for (const flatDir of inner) {
        pushCut(cuts, cutWholeLines(source, scan, flatDir.nameStart, flatDir.braceEnd + 1, cuts));
      }
    }
  }
}

/**
 * Keeps a cut that could be made, and drops one that could not. A reference the rewrite declined
 * to cut is left where it is, so the sweep finds it, reports its line, and `finish` puts the whole
 * file back exactly as it was.
 */
function pushCut(cuts: Cut[], cut: Cut | null): void {
  if (cut) {
    cuts.push(cut);
  }
}

function isLegacyProjectEntry(entry: GradleString): boolean {
  return entry.value.trim() === legacyCordovaProjectPath;
}

function isLegacyDirEntry(entry: GradleString): boolean {
  // A whole path segment, so a directory that merely starts with the same name is never dropped.
  return entry.value.split('/').some((segment) => segment === legacyCordovaPluginsDir);
}

/**
 * Blanks comments and strings so that braces, brackets, commas and identifiers can be found by
 * index without tripping over anything quoted. Both copies have the same length as the source,
 * so an index found in either one points at the same character of the source.
 */
function scanGradle(source: string): ScannedGradle {
  const mask = source.split('');
  const code = source.split('');
  const strings = new Map<number, GradleString>();
  const comments: GradleComment[] = [];

  const blank = (target: string[], from: number, to: number): void => {
    for (let i = from; i < to && i < target.length; i++) {
      if (target[i] !== '\n') {
        target[i] = ' ';
      }
    }
  };

  let i = 0;
  while (i < source.length) {
    const pair = source.slice(i, i + 2);
    if (pair === '//' || pair === '/*') {
      const toEndOfLine = pair === '//';
      let stop: number;
      if (toEndOfLine) {
        const newline = source.indexOf('\n', i);
        stop = newline === -1 ? source.length : newline;
        if (stop > i && source[stop - 1] === '\r') {
          // Leave the terminator out of the comment, so cutting one out of a Windows file
          // cannot turn that line's CRLF into an LF.
          stop--;
        }
      } else {
        const close = source.indexOf('*/', i + 2);
        stop = close === -1 ? source.length : close + 2;
      }
      blank(mask, i, stop);
      blank(code, i, stop);
      comments.push({ start: i, end: stop, toEndOfLine });
      i = stop;
      continue;
    }

    const char = source[i];
    if (char === '"' || char === `'`) {
      const triple = char + char + char;
      const quote = source.startsWith(triple, i) ? triple : char;
      let j = i + quote.length;
      let closed = false;
      while (j < source.length) {
        if (source[j] === '\\') {
          j += 2;
          continue;
        }
        if (source.startsWith(quote, j)) {
          closed = true;
          break;
        }
        j++;
      }
      const end = closed ? j + quote.length : source.length;
      blank(mask, i, end);
      strings.set(i, { start: i, end, value: source.slice(i + quote.length, closed ? j : source.length) });
      i = end;
      continue;
    }
    i++;
  }

  return { mask: mask.join(''), code: code.join(''), strings, comments };
}

function findNames(scan: ScannedGradle, name: string, from = 0, to = scan.mask.length): GradleName[] {
  const names: GradleName[] = [];
  const pattern = new RegExp(`(^|[^\\w.$])(${name})(?![\\w$])`, 'g');
  let match: RegExpExecArray | null;
  while ((match = pattern.exec(scan.mask)) !== null) {
    const start = match.index + match[1].length;
    if (start >= from && start + name.length <= to) {
      names.push({ start, end: start + name.length });
    }
  }
  return names;
}

function matchBrace(mask: string, braceStart: number): number {
  let depth = 0;
  for (let i = braceStart; i < mask.length; i++) {
    if (mask[i] === '{') {
      depth++;
    } else if (mask[i] === '}') {
      depth--;
      if (depth === 0) {
        return i;
      }
    }
  }
  return -1;
}

function findBlocks(scan: ScannedGradle, name: string, from = 0, to = scan.mask.length): GradleBlock[] {
  const blocks: GradleBlock[] = [];
  for (const found of findNames(scan, name, from, to)) {
    let i = found.end;
    while (i < to && /\s/.test(scan.mask[i])) {
      i++;
    }
    if (scan.mask[i] !== '{') {
      continue;
    }
    const braceEnd = matchBrace(scan.mask, i);
    if (braceEnd === -1 || braceEnd >= to) {
      continue;
    }
    blocks.push({ nameStart: found.start, braceStart: i, braceEnd });
  }
  return blocks;
}

function contains(outer: GradleBlock, inner: GradleBlock): boolean {
  return inner.nameStart > outer.braceStart && inner.braceEnd < outer.braceEnd;
}

/**
 * Every `dirs` statement in a `flatDir` body, or null when one of them is anything but a plain
 * list of string literals - the only shape that can be filtered without guessing.
 */
function readDirsStatements(scan: ScannedGradle, flatDir: GradleBlock): DirsStatement[] | null {
  const statements: DirsStatement[] = [];

  for (const name of findNames(scan, 'dirs', flatDir.braceStart + 1, flatDir.braceEnd)) {
    const list = parseStringList(scan, name.end, flatDir.braceEnd);
    if (!list) {
      return null;
    }
    statements.push({ nameStart: name.start, end: list.end, entries: list.entries });
  }

  return statements;
}

/**
 * Reads the argument list of a call like `include ':app', ':other'`, `dirs('a', 'b')` or
 * `dirs = ['a', 'b']`. Returns null unless every argument is a plain string literal.
 */
function parseStringList(
  scan: ScannedGradle,
  from: number,
  limit: number,
): { entries: GradleString[]; end: number } | null {
  // `code` keeps the string literals intact, so skipping whitespace stops at a quote instead of
  // walking through a blanked out literal; comments are already whitespace in it.
  const { code, strings } = scan;
  let pos = from;

  const skipSpace = (allowNewline: boolean): void => {
    while (pos < limit && (allowNewline ? /\s/.test(code[pos]) : /[ \t\r]/.test(code[pos]))) {
      pos++;
    }
  };

  skipSpace(false);
  if (code[pos] === '=') {
    pos++;
    skipSpace(false);
  }
  let close: string | null = null;
  if (code[pos] === '(') {
    close = ')';
    pos++;
  } else if (code[pos] === '[') {
    close = ']';
    pos++;
  }

  const entries: GradleString[] = [];
  let first = true;
  for (;;) {
    // A newline may only split the list inside brackets, or right after a comma.
    skipSpace(close !== null || !first);
    first = false;
    if (close !== null && code[pos] === close) {
      return { entries, end: pos + 1 };
    }
    const literal = strings.get(pos);
    if (!literal || literal.end > limit) {
      return null;
    }
    entries.push(literal);
    pos = literal.end;
    skipSpace(false);
    if (code[pos] === ',') {
      pos++;
      continue;
    }
    if (close !== null) {
      skipSpace(true);
      return code[pos] === close ? { entries, end: pos + 1 } : null;
    }
    if (pos >= limit || code[pos] === '\n' || code[pos] === ';' || code[pos] === '}') {
      return { entries, end: pos };
    }
    return null;
  }
}

/**
 * Cuts that take out the dropped entries and one separating comma each, so the entries that stay
 * keep the spacing and the quoting they had. A cut that would swallow a comment written between
 * two entries is refused rather than made, and `all` means every entry goes, which the caller
 * turns into the removal of the whole statement.
 */
function cutsForDroppedEntries(
  entries: GradleString[],
  drop: (entry: GradleString) => boolean,
  comments: GradleComment[],
): EntryCuts {
  const cuts: Cut[] = [];
  let i = 0;

  while (i < entries.length) {
    if (!drop(entries[i])) {
      i++;
      continue;
    }
    let last = i;
    while (last + 1 < entries.length && drop(entries[last + 1])) {
      last++;
    }
    // Either take the comma that follows the run, or the one in front of it.
    const after = last + 1 < entries.length ? { start: entries[i].start, end: entries[last + 1].start } : null;
    const before = i > 0 ? { start: entries[i - 1].end, end: entries[last].end } : null;

    if (after && !coversComment(comments, after)) {
      cuts.push(after);
    } else if (before && !coversComment(comments, before)) {
      cuts.push(before);
    } else if (!after && !before) {
      return { kind: 'all' };
    } else {
      return { kind: 'decline' };
    }
    i = last + 1;
  }

  return { kind: 'cuts', cuts };
}

function coversComment(comments: GradleComment[], cut: Cut): boolean {
  return comments.some((comment) => comment.start < cut.end && comment.end > cut.start);
}

/**
 * The cut that takes the whole line, or lines, the statement between `start` and `end` sits on.
 *
 * A line may only be taken when the statement has it to itself: with comments and string literals
 * blanked out, nothing but whitespace before it on its first line, and nothing but whitespace, at
 * most one `;` and at most a trailing comment after it on its last line. A line that also carries
 * a second statement, or a value the statement carries on below, is a line this only half
 * understands, and null says so: the caller leaves the file alone and reports the line instead.
 * Deleting a piece of somebody's build file is far worse than declining to touch it.
 *
 * `cuts` is what has been decided so far: the cut this returns never starts inside one of them,
 * so two cuts can never overlap and lose the text between them.
 */
function cutWholeLines(source: string, scan: ScannedGradle, start: number, end: number, cuts: Cut[] = []): Cut | null {
  const floor = Math.max(cutFloor(cuts, start), contentStart(source));
  let from = start;
  while (from > 0 && /[ \t\r]/.test(source[from - 1])) {
    from--;
  }
  if (from > contentStart(source) && source[from - 1] !== '\n') {
    // Something else shares the line, in front of the statement.
    return null;
  }

  // `code` has the comments blanked to spaces, so a trailing comment is stepped over with the
  // whitespace and goes out with the line, while a string literal still stops the walk.
  let to = end;
  const skipBlanks = (): void => {
    while (to < source.length && /[ \t\r]/.test(scan.code[to])) {
      to++;
    }
  };
  skipBlanks();
  if (scan.code[to] === ';') {
    // The semicolon that ends the statement goes with it, rather than being left behind on a
    // line of its own.
    to++;
    skipBlanks();
  }
  if (to < source.length && scan.code[to] !== '\n') {
    // A second statement follows on the same line.
    return null;
  }
  if (spansLineBreak(scan, to)) {
    // A block comment that opened on this line closes on a later one.
    return null;
  }
  if (to < source.length) {
    return clampToFloor(collapseBlankLine(source, { start: from, end: to + 1, wholeLines: true }), floor);
  }
  // The cut runs to the end of the file. It goes through `collapseBlankLine` like any other, and
  // then takes the terminator that ended the line before, so the file does not silently gain a
  // trailing newline it never had - unless the cut already carries the file's final newline, which
  // is what an unterminated string literal running to EOF leaves it holding.
  const cut = collapseBlankLine(source, { start: from, end: to, wholeLines: true });
  return clampToFloor(source.endsWith('\n') ? cut : takeTheTerminatorBefore(source, cut), floor);
}

/** Moves a cut back over the line terminator in front of it, CRLF and all. */
function takeTheTerminatorBefore(source: string, cut: Cut): Cut {
  if (cut.start === 0 || source[cut.start - 1] !== '\n') {
    return cut;
  }
  const crlf = cut.start > 1 && source[cut.start - 2] === '\r';
  return { ...cut, start: cut.start - (crlf ? 2 : 1) };
}

/**
 * The end of the last cut that finishes at or before `start`: where a new cut may begin without
 * running into one that is already decided.
 */
function cutFloor(cuts: Cut[], start: number): number {
  return cuts.reduce((floor, cut) => (cut.end <= start && cut.end > floor ? cut.end : floor), 0);
}

function clampToFloor(cut: Cut, floor: number): Cut {
  return cut.start < floor ? { ...cut, start: floor } : cut;
}

/**
 * The cut for a `//` comment that holds one of the statements being removed, or null for a comment
 * this will not touch.
 *
 * Two things have to be true. The comment has to start its line: a `//` with code in front of it
 * may not be a comment at all - the `//` inside a Groovy slashy string looks exactly the same - and
 * a line this tool only half understands is not one to cut into. And what the comment holds has to
 * be one of the legacy statements, commented out. Prose somebody wrote about the project - a TODO,
 * a ticket number, a link to a wiki page - is content, and content is reported rather than deleted.
 *
 * A commented out statement is still worth removing, because the check in `cap update android` is
 * a plain substring match that keeps failing on one.
 */
function cutLegacyStatementComment(
  source: string,
  scan: ScannedGradle,
  comment: GradleComment,
  cuts: Cut[],
  statementPatterns: RegExp[],
): Cut | null {
  if (!comment.toEndOfLine || !startsItsLine(source, comment.start)) {
    return null;
  }
  const held = source
    .slice(comment.start, comment.end)
    .replace(/^\/\/+/, '')
    .trim()
    .replace(/;$/, '')
    .trim();
  if (!statementPatterns.some((pattern) => pattern.test(held))) {
    return null;
  }
  return cutWholeLines(source, scan, comment.start, comment.end, cuts);
}

/** Whether nothing but whitespace comes before `index` on its line. */
function startsItsLine(source: string, index: number): boolean {
  let from = index;
  while (from > 0 && /[ \t]/.test(source[from - 1])) {
    from--;
  }
  return from === contentStart(source) || source[from - 1] === '\n';
}

/** A statement that ends on one of these is carried on by the line below. */
const continuesOnTheNextLine = /[=+\-*/%&|^~!<>?:,.([{\\]$/;

/**
 * One line's worth of code, ready to be matched against the line patterns, or null when the line
 * is not a single complete statement.
 *
 * Comments are blanked out, so an ordinary trailing comment does not stop a line that is otherwise
 * clean, while the string literals are kept so the patterns can match the quoted project name. The
 * statement also has to finish on the line: brackets it opened have to close, it may not end on
 * something that needs a right hand side, and a second `;`-separated statement rules the line out.
 */
function readStatementLine(scan: ScannedGradle, lineStart: number, lineEnd: number): string | null {
  if (spansLineBreak(scan, lineStart) || spansLineBreak(scan, lineEnd)) {
    // A string literal or a block comment runs across the edge of the line, so the line is part
    // of something longer and cannot be read on its own.
    return null;
  }

  let code = scan.code.slice(lineStart, lineEnd);
  let mask = scan.mask.slice(lineStart, lineEnd);
  const terminator = /;[ \t\r]*$/.exec(mask);
  if (terminator) {
    code = code.slice(0, terminator.index);
    mask = mask.slice(0, terminator.index);
  }
  if (mask.includes(';')) {
    return null;
  }
  if (bracketBalance(mask) !== 0) {
    return null;
  }

  const statement = code.trim();
  if (statement === '' || continuesOnTheNextLine.test(statement)) {
    return null;
  }
  return statement;
}

/** How many brackets the text leaves open, or null when it closes one it never opened. */
function bracketBalance(text: string): number | null {
  let depth = 0;
  for (const char of text) {
    if (char === '(' || char === '[' || char === '{') {
      depth++;
    } else if (char === ')' || char === ']' || char === '}') {
      depth--;
      if (depth < 0) {
        return null;
      }
    }
  }
  return depth;
}

/** Whether a string literal or a block comment runs across `index`, rather than stopping at it. */
function spansLineBreak(scan: ScannedGradle, index: number): boolean {
  for (const literal of scan.strings.values()) {
    if (literal.start < index && literal.end > index) {
      return true;
    }
  }
  return scan.comments.some((comment) => comment.start < index && comment.end > index);
}

/**
 * Whether `index` falls inside a string literal that runs over a line break. Those are the ones
 * that are text rather than code - a `"""..."""` block, or a quote the scanner never saw closed.
 * The quoted project name in `project(':capacitor-cordova-android-plugins')` is a string too, but
 * it is the argument of the statement on its own line, and that is what the patterns match.
 */
function insideMultiLineString(scan: ScannedGradle, index: number): boolean {
  for (const literal of scan.strings.values()) {
    if (index >= literal.start && index < literal.end && scan.mask.slice(literal.start, literal.end).includes('\n')) {
      return true;
    }
  }
  return false;
}

/**
 * Keeps a removed statement from leaving two blank lines where there was one. Only a blank line in
 * front of the cut lets the one behind it go: at the start of the file there is no line in front,
 * so a blank line the user put there is a separator of theirs and stays.
 */
function collapseBlankLine(source: string, cut: Cut): Cut {
  const before = source.slice(0, cut.start);
  const preceding = /(?:^|\n)([ \t\r]*\n)$/.exec(before);
  if (!preceding) {
    return cut;
  }
  const following = /^[ \t\r]*\n/.exec(source.slice(cut.end));
  if (following) {
    return { ...cut, end: cut.end + following[0].length };
  }
  if (preceding && cut.end >= source.length) {
    // The cut runs to the end of the file, so there is no blank line after it to absorb: take the
    // one in front of it instead, or the file silently gains a trailing blank line.
    return { ...cut, start: cut.start - preceding[1].length };
  }
  return cut;
}

/**
 * Deals with the mentions of the Cordova project the block rewrites did not already cover:
 * statements matching one of `linePatterns`, and `//` comments holding one of them. Anything else
 * is reported rather than guessed at.
 */
function sweepLeftovers(source: string, scan: ScannedGradle, cuts: Cut[], linePatterns: RegExp[]): string[] {
  const unhandled: string[] = [];
  let from = 0;

  for (;;) {
    const found = source.indexOf(legacyCordovaPluginsDir, from);
    if (found === -1) {
      return unhandled;
    }
    from = found + legacyCordovaPluginsDir.length;
    if (cuts.some((cut) => found >= cut.start && found < cut.end)) {
      continue;
    }

    const lineStart = source.lastIndexOf('\n', found) + 1;
    const newline = source.indexOf('\n', found);
    const lineEnd = newline === -1 ? source.length : newline;
    // The line as the user wrote it, for the report. The match is made against the scanner's
    // comment-blanked copy, so a line that is clean apart from a trailing comment still counts.
    const report = `${countLines(source, lineStart)}: ${source.slice(lineStart, lineEnd).replace(/\r$/, '').trim()}`;

    const comment = scan.comments.find((entry) => found >= entry.start && found < entry.end);
    if (comment) {
      // A commented out legacy statement goes with the rest of them. Anything else a comment has
      // to say about the project - including everything a block comment says - is the user's, and
      // is reported so they can decide.
      const cut = cutLegacyStatementComment(source, scan, comment, cuts, linePatterns);
      if (cut) {
        cuts.push(cut);
      } else {
        unhandled.push(report);
      }
      continue;
    }
    if (insideMultiLineString(scan, found)) {
      // Prose in a `"""..."""` block, or a quote that was never closed. Either way it is text and
      // not a statement, and cutting the line would take it out of the middle of a string.
      unhandled.push(report);
      continue;
    }
    if (cuts.some((cut) => cut.start < lineEnd && cut.end > lineStart)) {
      unhandled.push(report);
      continue;
    }
    const statement = readStatementLine(scan, lineStart, lineEnd);
    if (statement !== null && linePatterns.some((pattern) => pattern.test(statement))) {
      const cut = cutWholeLines(source, scan, lineStart, lineEnd, cuts);
      if (cut) {
        cuts.push(cut);
        continue;
      }
    }
    unhandled.push(report);
  }
}

/** The 1-based number of the line `index` sits on. */
function countLines(source: string, index: number): number {
  let line = 1;
  for (let i = 0; i < index; i++) {
    if (source[i] === '\n') {
      line++;
    }
  }
  return line;
}

/**
 * Whether a `flatDir` body holds nothing but its `dirs` statements and whitespace. A comment the
 * user wrote is content, not whitespace, and keeps the block - the same call `cutsForEmptiedFlatDirs`
 * makes one level up for `repositories`. Only a comment about the removed project goes with it.
 */
function isFlatDirBodyOnlyDirs(
  source: string,
  scan: ScannedGradle,
  flatDir: GradleBlock,
  statements: DirsStatement[],
): boolean {
  const spans = [
    ...statements.map((statement) => ({ start: statement.nameStart, end: statement.end })),
    ...scan.comments.filter((comment) => source.slice(comment.start, comment.end).includes(legacyCordovaPluginsDir)),
  ];
  return isBodyEmptyWithoutSpans(source, flatDir, spans);
}

/** Whether removing `inner` leaves `block` with nothing but whitespace in it. */
function isBodyEmptyWithout(source: string, block: GradleBlock, inner: GradleBlock[]): boolean {
  return isBodyEmptyWithoutSpans(
    source,
    block,
    inner.map((child) => ({ start: child.nameStart, end: child.braceEnd + 1 })),
  );
}

function isBodyEmptyWithoutSpans(source: string, block: GradleBlock, spans: { start: number; end: number }[]): boolean {
  const from = block.braceStart + 1;
  const body = source.slice(from, block.braceEnd).split('');

  for (const span of spans) {
    for (let i = Math.max(span.start, from); i < Math.min(span.end, block.braceEnd); i++) {
      body[i - from] = ' ';
    }
  }

  return body.join('').trim() === '';
}

function applyCuts(source: string, cuts: Cut[]): string | null {
  const ordered = cuts.filter((cut) => cut.end > cut.start).sort((a, b) => a.start - b.start);
  for (let i = 1; i < ordered.length; i++) {
    if (ordered[i].start < ordered[i - 1].end) {
      return null;
    }
  }

  let result = '';
  let pos = 0;
  for (const cut of ordered) {
    result += source.slice(pos, cut.start);
    pos = cut.end;
  }
  return result + source.slice(pos);
}

/**
 * A cut meant to take whole lines has to start where a line starts and end where one ends, or
 * stay inside a single line. Anything else would glue two of the user's lines together.
 */
function isLineAligned(source: string, cut: Cut): boolean {
  if (!source.slice(cut.start, cut.end).includes('\n')) {
    return true;
  }
  const startsALine = cut.start === contentStart(source) || source[cut.start - 1] === '\n';
  // A cut that runs to the end of a file with no final newline starts on the terminator of the
  // line before, which is `\r\n` in a Windows file and `\n` in a Unix one.
  const takesTheNewlineBefore =
    cut.end === source.length && (source[cut.start] === '\n' || source.startsWith('\r\n', cut.start));
  const endsALine = cut.end === source.length || source[cut.end - 1] === '\n';
  return (startsALine || takesTheNewlineBefore) && endsALine;
}

function braceBalance(source: string): number {
  let balance = 0;
  for (const char of scanGradle(source).mask) {
    if (char === '{') {
      balance++;
    } else if (char === '}') {
      balance--;
    }
  }
  return balance;
}

/**
 * The cuts, with the run of removed lines that ends a file with no final newline taking the
 * terminator of the line in front of it - so such a file does not gain a final newline it never
 * had. `cutWholeLines` does this for a single removed line; a run of them is several cuts that
 * touch, and only the run as a whole knows where it begins, so the run is merged into one cut.
 */
function keepTheMissingFinalNewline(source: string, cuts: Cut[]): Cut[] {
  const ordered = [...cuts].sort((a, b) => a.start - b.start);
  const last = ordered[ordered.length - 1];
  if (source.endsWith('\n') || !last?.wholeLines || last.end !== source.length) {
    return ordered;
  }

  let index = ordered.length - 1;
  while (index > 0 && ordered[index - 1].wholeLines && ordered[index - 1].end === ordered[index].start) {
    index--;
  }
  const start = ordered[index].start;
  if (start === 0 || source[start - 1] !== '\n') {
    // Either the run already took the terminator, or something of the user's is in front of it.
    return ordered;
  }
  const crlf = start > 1 && source[start - 2] === '\r';
  const run: Cut = { start: start - (crlf ? 2 : 1), end: source.length, wholeLines: true };
  return [...ordered.slice(0, index), run];
}

function declineWholeFile(source: string): GradleRewrite {
  return { contents: source, changed: false, unhandled: describeLegacyLines(source) };
}

function describeLegacyLines(source: string): string[] {
  return source
    .split(/\r?\n/)
    .map((line, index) => ({ line, index }))
    .filter(({ line }) => line.includes(legacyCordovaPluginsDir))
    .map(({ line, index }) => `${index + 1}: ${line.trim()}`);
}

function finish(source: string, cuts: Cut[], unhandled: string[]): GradleRewrite {
  if (unhandled.length > 0) {
    // The file is left as it was, so report every line that mentions the project, not only the
    // ones the rewrite choked on: the user has to remove all of them for `cap sync` to pass.
    return declineWholeFile(source);
  }
  if (cuts.length === 0) {
    return { contents: source, changed: false, unhandled: [] };
  }

  const prepared = keepTheMissingFinalNewline(source, cuts);
  const contents = applyCuts(source, prepared);
  // Belt and braces: never hand back something that came out of overlapping edits, still has a
  // reference, has a different brace balance, or was cut across a line boundary by mistake.
  if (
    contents === null ||
    findLegacyCordovaGradleLines(contents).length > 0 ||
    braceBalance(contents) !== braceBalance(source) ||
    !prepared.filter((cut) => cut.wholeLines).every((cut) => isLineAligned(source, cut))
  ) {
    return declineWholeFile(source);
  }

  return { contents, changed: contents !== source, unhandled: [] };
}
