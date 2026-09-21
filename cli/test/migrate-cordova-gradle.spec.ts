import { readFileSync } from 'fs-extra';
import { resolve } from 'path';

import {
  removeLegacyCordovaFromAppBuildGradle,
  removeLegacyCordovaFromSettingsGradle,
} from '../src/android/cordova-cleanup';
import { findLegacyCordovaGradleLines } from '../src/android/update';

/** `android/settings.gradle` exactly as an app created before Cordova support was removed has it. */
const CAPACITOR_7_SETTINGS_GRADLE =
  `include ':app'\n` +
  `include ':capacitor-cordova-android-plugins'\n` +
  `project(':capacitor-cordova-android-plugins').projectDir = new File('./capacitor-cordova-android-plugins/')\n` +
  `\n` +
  `apply from: 'capacitor.settings.gradle'`;

/** The part of `android/app/build.gradle` that same app has, `flatDir{` and all. */
const CAPACITOR_7_APP_BUILD_GRADLE = `apply plugin: 'com.android.application'

android {
    namespace = "com.getcapacitor.myapp"
    compileSdk = rootProject.ext.compileSdkVersion
}

repositories {
    flatDir{
        dirs '../capacitor-cordova-android-plugins/src/main/libs', 'libs'
    }
}

dependencies {
    implementation fileTree(include: ['*.jar'], dir: 'libs')
    implementation "androidx.appcompat:appcompat:$androidxAppCompatVersion"
    implementation project(':capacitor-android')
    testImplementation "junit:junit:$junitVersion"
    implementation project(':capacitor-cordova-android-plugins')
}

apply from: 'capacitor.build.gradle'
`;

describe('removeLegacyCordovaFromSettingsGradle', () => {
  it('turns the real pre-removal template into the one shipped today, byte for byte', () => {
    const shipped = readFileSync(resolve(__dirname, '..', '..', 'android-template', 'settings.gradle'), 'utf-8');

    const result = removeLegacyCordovaFromSettingsGradle(CAPACITOR_7_SETTINGS_GRADLE);

    expect(result.unhandled).toEqual([]);
    expect(result.changed).toBe(true);
    // Including the missing final newline the template has.
    expect(result.contents).toBe(shipped);
  });

  it('filters an include that names several projects at once', () => {
    const result = removeLegacyCordovaFromSettingsGradle(
      `include ':app', ':capacitor-cordova-android-plugins'\n` +
        `project(':capacitor-cordova-android-plugins').projectDir = new File('./capacitor-cordova-android-plugins/')\n`,
    );

    expect(result.unhandled).toEqual([]);
    expect(result.contents).toBe(`include ':app'\n`);
  });

  it('filters an include list that is split over several lines and uses double quotes', () => {
    const result = removeLegacyCordovaFromSettingsGradle(`include ":app",
        ":capacitor-cordova-android-plugins",
        ":other"
project(":capacitor-cordova-android-plugins").projectDir = new File("./capacitor-cordova-android-plugins/")
`);

    expect(result.unhandled).toEqual([]);
    expect(result.contents).toBe(`include ":app",
        ":other"
`);
  });

  it('filters the parenthesised include form', () => {
    const result = removeLegacyCordovaFromSettingsGradle(`include(':app', ':capacitor-cordova-android-plugins')\n`);

    expect(result.unhandled).toEqual([]);
    expect(result.contents).toBe(`include(':app')\n`);
  });

  it('keeps CRLF line endings', () => {
    const result = removeLegacyCordovaFromSettingsGradle(
      `include ':app'\r\n` +
        `include ':capacitor-cordova-android-plugins'\r\n` +
        `project(':capacitor-cordova-android-plugins').projectDir = new File('./capacitor-cordova-android-plugins/')\r\n`,
    );

    expect(result.unhandled).toEqual([]);
    expect(result.contents).toBe(`include ':app'\r\n`);
  });

  it('does not add a trailing newline to a file that never had one', () => {
    const result = removeLegacyCordovaFromSettingsGradle(
      `include ':app'\ninclude ':capacitor-cordova-android-plugins'`,
    );

    expect(result.unhandled).toEqual([]);
    expect(result.contents).toBe(`include ':app'`);
  });

  it('does not add a trailing newline to a CRLF file that never had one', () => {
    // The CRLF twin of the test above. The cut starts on the `\r` of the line before, which is
    // still a cut that takes whole lines - a file that only differs in its line endings has to
    // get the same answer, not be declined.
    const result = removeLegacyCordovaFromSettingsGradle(
      `include ':app'\r\ninclude ':capacitor-cordova-android-plugins'`,
    );

    expect(result.unhandled).toEqual([]);
    expect(result.changed).toBe(true);
    expect(result.contents).toBe(`include ':app'`);
  });

  it('turns the CRLF twin of the pre-removal template into the CRLF twin of the shipped one', () => {
    const shipped = readFileSync(resolve(__dirname, '..', '..', 'android-template', 'settings.gradle'), 'utf-8');

    const result = removeLegacyCordovaFromSettingsGradle(CAPACITOR_7_SETTINGS_GRADLE.replace(/\n/g, '\r\n'));

    expect(result.unhandled).toEqual([]);
    expect(result.contents).toBe(shipped.replace(/\n/g, '\r\n'));
  });

  it('does not add a trailing newline when the last two lines of a file that had none both go', () => {
    // The same promise as the single-line case above, for a run of lines: the `project(...)` line
    // runs to the end of the file, so the run wants the newline that ended the line in front of
    // it - which is the one the `include` line sat on.
    const result = removeLegacyCordovaFromSettingsGradle(
      `include ':app'\n` +
        `include ':capacitor-cordova-android-plugins'\n` +
        `project(':capacitor-cordova-android-plugins').projectDir = new File('./capacitor-cordova-android-plugins/')`,
    );

    expect(result.unhandled).toEqual([]);
    expect(result.changed).toBe(true);
    expect(result.contents).toBe(`include ':app'`);
  });

  it('does not add a trailing newline when the last two lines of a CRLF file that had none both go', () => {
    const result = removeLegacyCordovaFromSettingsGradle(
      `include ':app'\r\n` +
        `include ':capacitor-cordova-android-plugins'\r\n` +
        `project(':capacitor-cordova-android-plugins').projectDir = new File('./capacitor-cordova-android-plugins/')`,
    );

    expect(result.unhandled).toEqual([]);
    expect(result.changed).toBe(true);
    expect(result.contents).toBe(`include ':app'`);
  });

  it('leaves a projectDir statement that is not the whole line alone and reports it', () => {
    // The `project(...)` call is the tail of a conditional: cutting the line would silently
    // delete the `if` in front of it.
    const source =
      `include ':app'\n` +
      `if (useCordova) project(':capacitor-cordova-android-plugins').projectDir = new File('./x/')\n`;

    const result = removeLegacyCordovaFromSettingsGradle(source);

    expect(result.changed).toBe(false);
    expect(result.contents).toBe(source);
    expect(result.unhandled).toEqual([
      `2: if (useCordova) project(':capacitor-cordova-android-plugins').projectDir = new File('./x/')`,
    ]);
  });

  it('leaves an include whose line opens a block comment that closes later alone and reports it', () => {
    // Taking the line would take the `/*` with it and leave the `*/` behind.
    const source = `include ':app'\n` + `include ':capacitor-cordova-android-plugins' /* why\n` + `this was here */\n`;

    const result = removeLegacyCordovaFromSettingsGradle(source);

    expect(result.changed).toBe(false);
    expect(result.contents).toBe(source);
    expect(result.contents).toContain('this was here */');
    expect(result.unhandled).toEqual([`2: include ':capacitor-cordova-android-plugins' /* why`]);
  });

  it('removes a commented out reference, which the update check also trips over', () => {
    const result = removeLegacyCordovaFromSettingsGradle(
      `include ':app'\n// include ':capacitor-cordova-android-plugins'\n`,
    );

    expect(result.unhandled).toEqual([]);
    expect(result.contents).toBe(`include ':app'\n`);
  });

  it('removes a commented out projectDir statement', () => {
    const result = removeLegacyCordovaFromSettingsGradle(
      `include ':app'\n` +
        `//project(':capacitor-cordova-android-plugins').projectDir = new File('./capacitor-cordova-android-plugins/')\n`,
    );

    expect(result.unhandled).toEqual([]);
    expect(result.contents).toBe(`include ':app'\n`);
  });

  it('leaves a note the user wrote about the project alone and reports it', () => {
    // The comment holds prose, not a commented out statement: the ticket number and the link are
    // the user's, and are theirs to decide about.
    const source =
      `include ':app'\n` +
      `// TODO(ACME-77): drop capacitor-cordova-android-plugins once QA signs off, see wiki/Build\n` +
      `apply from: 'capacitor.settings.gradle'\n`;

    const result = removeLegacyCordovaFromSettingsGradle(source);

    expect(result.changed).toBe(false);
    expect(result.contents).toBe(source);
    expect(result.contents).toContain('ACME-77');
    expect(result.unhandled).toEqual([
      `2: // TODO(ACME-77): drop capacitor-cordova-android-plugins once QA signs off, see wiki/Build`,
    ]);
  });

  it('leaves a slashy string whose contents look like a comment alone and reports it', () => {
    // `/x//capacitor-cordova-android-plugins/` is one Groovy slashy string, a form the scanner
    // does not model, so the `//` in the middle of it looks exactly like the start of a comment.
    // Cutting that "comment" would take the rest of the line with it.
    const source = `include ':app'\ndef p = /x//capacitor-cordova-android-plugins/\n`;

    const result = removeLegacyCordovaFromSettingsGradle(source);

    expect(result.changed).toBe(false);
    expect(result.contents).toBe(source);
    expect(result.unhandled).toEqual([`2: def p = /x//capacitor-cordova-android-plugins/`]);
  });

  it('leaves a file that holds a dollar-slashy string alone and reports it', () => {
    // `$/ ... /$` is a string form the scanner does not model, so everything inside one would be
    // read as code - here, as a real `include` statement to take out of the middle of a string.
    const source = `def d = $/\n` + `include ':capacitor-cordova-android-plugins'\n` + `/$\n` + `include ':app'\n`;

    const result = removeLegacyCordovaFromSettingsGradle(source);

    expect(result.changed).toBe(false);
    expect(result.contents).toBe(source);
    expect(result.unhandled).toEqual([`2: include ':capacitor-cordova-android-plugins'`]);
  });

  it('keeps the UTF-8 BOM of a file whose first line goes', () => {
    const result = removeLegacyCordovaFromSettingsGradle(
      `\ufeffproject(':capacitor-cordova-android-plugins').projectDir = new File('./capacitor-cordova-android-plugins/')\n` +
        `include ':app'\n`,
    );

    expect(result.unhandled).toEqual([]);
    expect(result.changed).toBe(true);
    expect(result.contents).toBe(`\ufeffinclude ':app'\n`);
  });

  it('keeps the UTF-8 BOM when the include on the first line goes', () => {
    const result = removeLegacyCordovaFromSettingsGradle(
      `\ufeffinclude ':capacitor-cordova-android-plugins'\ninclude ':app'\n`,
    );

    expect(result.unhandled).toEqual([]);
    expect(result.changed).toBe(true);
    expect(result.contents).toBe(`\ufeffinclude ':app'\n`);
  });

  it('keeps a blank line the removed first line is separated from the rest by', () => {
    // There is no line in front of the first one, so there is no pair of blank lines to collapse:
    // the blank line is the user's separator and stays.
    const result = removeLegacyCordovaFromSettingsGradle(
      `include ':capacitor-cordova-android-plugins'\n\ninclude ':app'\n`,
    );

    expect(result.unhandled).toEqual([]);
    expect(result.changed).toBe(true);
    expect(result.contents).toBe(`\ninclude ':app'\n`);
  });

  it('keeps the final newline of a file whose last line has an unterminated string', () => {
    // The unterminated literal runs to the end of the file, so the statement ends there too - but
    // the file does end with a newline, and it keeps it.
    const result = removeLegacyCordovaFromSettingsGradle(
      `include ':app'\ninclude ':capacitor-cordova-android-plugins\n`,
    );

    expect(result.unhandled).toEqual([]);
    expect(result.changed).toBe(true);
    expect(result.contents).toBe(`include ':app'\n`);
  });

  it('leaves a project whose name only looks like the Cordova one alone and reports it', () => {
    const source = `include ':app'\ninclude ':my-capacitor-cordova-android-plugins-fork'\n`;

    const result = removeLegacyCordovaFromSettingsGradle(source);

    expect(result.changed).toBe(false);
    expect(result.contents).toBe(source);
    expect(result.unhandled).toEqual([`2: include ':my-capacitor-cordova-android-plugins-fork'`]);
  });

  it('leaves the file alone when a comment sits between the include arguments', () => {
    const source =
      `include ':app',      // the app module\n` +
      `        ':capacitor-cordova-android-plugins'\n` +
      `project(':capacitor-cordova-android-plugins').projectDir = new File('./capacitor-cordova-android-plugins/')\n`;

    const result = removeLegacyCordovaFromSettingsGradle(source);

    expect(result.changed).toBe(false);
    expect(result.contents).toBe(source);
    // Every offending line is reported, not only the one that could not be rewritten: the file
    // is untouched, so `cap sync` trips on all of them.
    expect(result.unhandled).toEqual([
      `2: ':capacitor-cordova-android-plugins'`,
      `3: project(':capacitor-cordova-android-plugins').projectDir = new File('./capacitor-cordova-android-plugins/')`,
    ]);
  });

  it('leaves a projectDir line that carries a second statement alone and reports it', () => {
    // The line is two statements: cutting it whole would silently delete `include ':nativeSdk'`.
    const source =
      `include ':app'\n` +
      `include ':capacitor-cordova-android-plugins'\n` +
      `project(':capacitor-cordova-android-plugins').projectDir = new File('./capacitor-cordova-android-plugins/'); include ':nativeSdk'\n`;

    const result = removeLegacyCordovaFromSettingsGradle(source);

    expect(result.changed).toBe(false);
    expect(result.contents).toBe(source);
    expect(result.unhandled).toEqual([
      `2: include ':capacitor-cordova-android-plugins'`,
      `3: project(':capacitor-cordova-android-plugins').projectDir = new File('./capacitor-cordova-android-plugins/'); include ':nativeSdk'`,
    ]);
  });

  it('leaves an include that shares its line with a second statement alone and reports it', () => {
    // Cutting only the statement would leave `; include ':app'` at the head of the file, which
    // the Groovy parser Gradle ships rejects.
    const source = `include ':capacitor-cordova-android-plugins'; include ':app'\n`;

    const result = removeLegacyCordovaFromSettingsGradle(source);

    expect(result.changed).toBe(false);
    expect(result.contents).toBe(source);
    expect(result.unhandled).toEqual([`1: include ':capacitor-cordova-android-plugins'; include ':app'`]);
  });

  it('leaves a projectDir assignment whose value continues on the next line alone and reports it', () => {
    const source =
      `include ':capacitor-cordova-android-plugins'\n` +
      `project(':capacitor-cordova-android-plugins').projectDir =\n` +
      `        cordovaDir\n`;

    const result = removeLegacyCordovaFromSettingsGradle(source);

    expect(result.changed).toBe(false);
    expect(result.contents).toBe(source);
    // The continuation is still there, not orphaned on a line of its own.
    expect(result.contents).toContain('        cordovaDir\n');
    expect(result.unhandled).toEqual([
      `1: include ':capacitor-cordova-android-plugins'`,
      `2: project(':capacitor-cordova-android-plugins').projectDir =`,
    ]);
  });

  it('takes the semicolon that ends a statement with it', () => {
    const source =
      `include ":capacitor-cordova-android-plugins";\n` +
      `project(":capacitor-cordova-android-plugins").projectDir = new File("./capacitor-cordova-android-plugins/");\n` +
      `include ":app"\n`;

    const result = removeLegacyCordovaFromSettingsGradle(source);

    expect(result.unhandled).toEqual([]);
    // No `;` left behind on a line of its own.
    expect(result.contents).toBe(`include ":app"\n`);
  });

  it('leaves an include naming a fork of the project alone and reports it', () => {
    // `:capacitor-cordova-android-plugins-fork` is a module of the user's that merely starts with
    // the name being removed. An entry has to be the project itself, exactly, to be dropped.
    const source = `include ':app', ':capacitor-cordova-android-plugins-fork'\n`;

    const result = removeLegacyCordovaFromSettingsGradle(source);

    expect(result.changed).toBe(false);
    expect(result.contents).toBe(source);
    expect(result.unhandled).toEqual([`1: include ':app', ':capacitor-cordova-android-plugins-fork'`]);
  });

  it('leaves a projectDir call whose closing bracket is on the next line alone and reports it', () => {
    // The statement leaves a bracket open, so it is not over at the end of the line. Cutting the
    // line would leave the `)` behind on its own and hand Gradle a file it cannot parse.
    const source =
      `include ':app'\n` +
      `project(':capacitor-cordova-android-plugins').projectDir = new File(rootDir, 'capacitor-cordova-android-plugins'\n` +
      `        )\n`;

    const result = removeLegacyCordovaFromSettingsGradle(source);

    expect(result.changed).toBe(false);
    expect(result.contents).toBe(source);
    expect(result.contents).toContain('        )\n');
    expect(result.unhandled).toEqual([
      `2: project(':capacitor-cordova-android-plugins').projectDir = new File(rootDir, 'capacitor-cordova-android-plugins'`,
    ]);
  });

  it('leaves a projectDir assignment carried on by the next line alone and reports it', () => {
    // The line ends on a `+`, so the value is only half of it. Cutting the line would take the
    // head of the expression and orphan the continuation.
    const source =
      `include ':app'\n` +
      `project(':capacitor-cordova-android-plugins').projectDir = new File(rootDir.path) +\n` +
      `        suffix\n`;

    const result = removeLegacyCordovaFromSettingsGradle(source);

    expect(result.changed).toBe(false);
    expect(result.contents).toBe(source);
    expect(result.contents).toContain('        suffix\n');
    expect(result.unhandled).toEqual([
      `2: project(':capacitor-cordova-android-plugins').projectDir = new File(rootDir.path) +`,
    ]);
  });

  it('gives a CRLF file the same answer as its LF twin when a blank line follows the removed one', () => {
    const lf =
      `include ':app'\n` +
      `\n` +
      `include ':capacitor-cordova-android-plugins'\n` +
      `\n` +
      `apply from: 'capacitor.settings.gradle'\n`;

    const unix = removeLegacyCordovaFromSettingsGradle(lf);
    const windows = removeLegacyCordovaFromSettingsGradle(lf.replace(/\n/g, '\r\n'));

    expect(unix.unhandled).toEqual([]);
    expect(unix.contents).toBe(`include ':app'\n\napply from: 'capacitor.settings.gradle'\n`);
    // Line endings are the only difference there is allowed to be: a Windows checkout of the same
    // file cannot come back with an extra blank line in it.
    expect(windows.unhandled).toEqual([]);
    expect(windows.contents).toBe(unix.contents.replace(/\n/g, '\r\n'));
  });

  it('does nothing to a file without references', () => {
    const source = `include ':app'\n\napply from: 'capacitor.settings.gradle'`;

    const result = removeLegacyCordovaFromSettingsGradle(source);

    expect(result.changed).toBe(false);
    expect(result.contents).toBe(source);
    expect(result.unhandled).toEqual([]);
  });
});

describe('removeLegacyCordovaFromAppBuildGradle', () => {
  it('filters the flatDir list and removes the dependency a Capacitor 7 app has', () => {
    const result = removeLegacyCordovaFromAppBuildGradle(CAPACITOR_7_APP_BUILD_GRADLE);

    expect(result.unhandled).toEqual([]);
    expect(result.changed).toBe(true);
    // BREAKING.md: "If the app loads .aar files from app/libs through that flatDir block, keep a
    // `flatDir { dirs 'libs' }` entry." The `flatDir{` with no space is kept as written, too.
    expect(result.contents).toBe(`apply plugin: 'com.android.application'

android {
    namespace = "com.getcapacitor.myapp"
    compileSdk = rootProject.ext.compileSdkVersion
}

repositories {
    flatDir{
        dirs 'libs'
    }
}

dependencies {
    implementation fileTree(include: ['*.jar'], dir: 'libs')
    implementation "androidx.appcompat:appcompat:$androidxAppCompatVersion"
    implementation project(':capacitor-android')
    testImplementation "junit:junit:$junitVersion"
}

apply from: 'capacitor.build.gradle'
`);
  });

  it('leaves nothing for the update check to find', () => {
    const result = removeLegacyCordovaFromAppBuildGradle(CAPACITOR_7_APP_BUILD_GRADLE);

    // Asserted against the name itself rather than through `findLegacyCordovaGradleLines`, so a
    // detector that stopped finding anything could not make this pass. The detector has its own
    // tests below.
    expect(result.contents).not.toContain('capacitor-cordova-android-plugins');
  });

  it('is a no-op the second time round', () => {
    const once = removeLegacyCordovaFromAppBuildGradle(CAPACITOR_7_APP_BUILD_GRADLE).contents;

    const twice = removeLegacyCordovaFromAppBuildGradle(once);

    expect(twice.changed).toBe(false);
    expect(twice.contents).toBe(once);
  });

  it('removes the repositories block when the Cordova directory was the only one', () => {
    const result = removeLegacyCordovaFromAppBuildGradle(`android {
}

repositories {
    flatDir {
        dirs '../capacitor-cordova-android-plugins/src/main/libs'
    }
}

dependencies {
    implementation project(':capacitor-cordova-android-plugins')
}
`);

    expect(result.unhandled).toEqual([]);
    expect(result.contents).toBe(`android {
}

dependencies {
}
`);
  });

  it('removes only the flatDir when the repositories block holds other entries', () => {
    const result = removeLegacyCordovaFromAppBuildGradle(`repositories {
    google()
    flatDir {
        dirs '../capacitor-cordova-android-plugins/src/main/libs'
    }
    mavenCentral()
}
`);

    expect(result.unhandled).toEqual([]);
    expect(result.contents).toBe(`repositories {
    google()
    mavenCentral()
}
`);
  });

  it('keeps a repositories block that still has a comment in it', () => {
    const result = removeLegacyCordovaFromAppBuildGradle(`repositories {
    // aars used to live here
    flatDir {
        dirs '../capacitor-cordova-android-plugins/src/main/libs'
    }
}
`);

    expect(result.unhandled).toEqual([]);
    expect(result.contents).toBe(`repositories {
    // aars used to live here
}
`);
  });

  it('keeps a flatDir that still has a comment of the user’s in it', () => {
    // The same call the block above makes for `repositories`: a comment somebody wrote is
    // content, not whitespace, so the block stays and the directory is reported instead.
    const source = `repositories {
    flatDir {
        // .aar files for the vendor SDK, see TICKET-1234
        dirs '../capacitor-cordova-android-plugins/src/main/libs'
    }
    google()
}
`;

    const result = removeLegacyCordovaFromAppBuildGradle(source);

    expect(result.changed).toBe(false);
    expect(result.contents).toBe(source);
    expect(result.unhandled).toEqual([`4: dirs '../capacitor-cordova-android-plugins/src/main/libs'`]);
  });

  it('keeps a flatDir with a comment in it even when the repositories block has other entries', () => {
    const source = `repositories {
    google()
    flatDir {
        // keep this note
        dirs '../capacitor-cordova-android-plugins/src/main/libs'
    }
}
`;

    const result = removeLegacyCordovaFromAppBuildGradle(source);

    expect(result.changed).toBe(false);
    expect(result.contents).toBe(source);
    expect(result.unhandled).toEqual([`5: dirs '../capacitor-cordova-android-plugins/src/main/libs'`]);
  });

  it('takes a comment about the removed project out with the flatDir', () => {
    // The comment is about what is going away, so it goes too - the update check trips over a
    // commented out reference just the same.
    const result = removeLegacyCordovaFromAppBuildGradle(`dependencies {
}

repositories {
    flatDir {
        // aars from capacitor-cordova-android-plugins
        dirs '../capacitor-cordova-android-plugins/src/main/libs'
    }
}
`);

    expect(result.unhandled).toEqual([]);
    expect(result.contents).toBe(`dependencies {
}
`);
  });

  it('removes a flatDir that is not wrapped in a repositories block', () => {
    const result = removeLegacyCordovaFromAppBuildGradle(`android {
    namespace = "com.x"
    flatDir {
        dirs '../capacitor-cordova-android-plugins/src/main/libs'
    }
}
`);

    expect(result.unhandled).toEqual([]);
    expect(result.contents).toBe(`android {
    namespace = "com.x"
}
`);
  });

  it('drops one of two dirs statements and keeps the flatDir', () => {
    const result = removeLegacyCordovaFromAppBuildGradle(`repositories {
  flatDir {
    dirs '../capacitor-cordova-android-plugins/src/main/libs'
    dirs 'libs'
  }
}
`);

    expect(result.unhandled).toEqual([]);
    expect(result.contents).toBe(`repositories {
  flatDir {
    dirs 'libs'
  }
}
`);
  });

  it('filters an entry out of the middle of a longer list', () => {
    const result = removeLegacyCordovaFromAppBuildGradle(
      `repositories {\n    flatDir { dirs 'aars', '../capacitor-cordova-android-plugins/src/main/libs', 'libs' }\n}\n`,
    );

    expect(result.unhandled).toEqual([]);
    expect(result.contents).toBe(`repositories {\n    flatDir { dirs 'aars', 'libs' }\n}\n`);
  });

  it('filters the last entry of a list', () => {
    const result = removeLegacyCordovaFromAppBuildGradle(`repositories {
    flatDir {
        dirs "libs", "../capacitor-cordova-android-plugins/src/main/libs"
    }
}
`);

    expect(result.unhandled).toEqual([]);
    expect(result.contents).toBe(`repositories {
    flatDir {
        dirs "libs"
    }
}
`);
  });

  it('filters a bracketed dirs assignment spread over several lines', () => {
    const result = removeLegacyCordovaFromAppBuildGradle(`repositories {
  flatDir {
    dirs = [
      '../capacitor-cordova-android-plugins/src/main/libs',
      'libs',
    ]
  }
}
`);

    expect(result.unhandled).toEqual([]);
    expect(result.contents).toBe(`repositories {
  flatDir {
    dirs = [
      'libs',
    ]
  }
}
`);
  });

  it('filters the dirs(...) call form', () => {
    const result = removeLegacyCordovaFromAppBuildGradle(
      `repositories { flatDir { dirs('../capacitor-cordova-android-plugins/src/main/libs', 'libs') } }\n`,
    );

    expect(result.unhandled).toEqual([]);
    expect(result.contents).toBe(`repositories { flatDir { dirs('libs') } }\n`);
  });

  it('filters a dirs entry that interpolates a variable', () => {
    const result = removeLegacyCordovaFromAppBuildGradle(
      `repositories {\n    flatDir {\n        dirs "$rootDir/../capacitor-cordova-android-plugins/src/main/libs", "libs"\n    }\n}\n`,
    );

    expect(result.unhandled).toEqual([]);
    expect(result.contents).toBe(`repositories {\n    flatDir {\n        dirs "libs"\n    }\n}\n`);
  });

  it('keeps CRLF line endings', () => {
    const result = removeLegacyCordovaFromAppBuildGradle(
      `repositories {\r\n    flatDir{\r\n        dirs '../capacitor-cordova-android-plugins/src/main/libs', 'libs'\r\n    }\r\n}\r\n\r\ndependencies {\r\n    implementation project(':capacitor-cordova-android-plugins')\r\n}\r\n`,
    );

    expect(result.unhandled).toEqual([]);
    expect(result.contents).toBe(
      `repositories {\r\n    flatDir{\r\n        dirs 'libs'\r\n    }\r\n}\r\n\r\ndependencies {\r\n}\r\n`,
    );
  });

  it.each([
    `    api project(':capacitor-cordova-android-plugins')`,
    `    implementation(project(':capacitor-cordova-android-plugins'))`,
    `    implementation project(path: ':capacitor-cordova-android-plugins')`,
    `    implementation project (":capacitor-cordova-android-plugins")`,
    `    debugImplementation project(':capacitor-cordova-android-plugins');`,
  ])('removes the dependency written as %s', (line) => {
    const result = removeLegacyCordovaFromAppBuildGradle(
      `dependencies {\n${line}\n    implementation 'androidx.core:core:1.0.0'\n}\n`,
    );

    expect(result.unhandled).toEqual([]);
    expect(result.contents).toBe(`dependencies {\n    implementation 'androidx.core:core:1.0.0'\n}\n`);
  });

  it('leaves a comment that trails a statement alone and reports it', () => {
    // A `//` with code in front of it is not necessarily a comment at all - the `//` inside a
    // Groovy slashy string looks exactly the same - so a comment is only ever cut when it starts
    // its line. Here the user is told about the note instead of having it taken out from behind
    // a dependency that stays.
    const source = `dependencies {\r\n    implementation project(':capacitor-android') // replaced capacitor-cordova-android-plugins\r\n    implementation 'x:y:1'\r\n}\r\n`;

    const result = removeLegacyCordovaFromAppBuildGradle(source);

    expect(result.changed).toBe(false);
    expect(result.contents).toBe(source);
    expect(result.unhandled).toEqual([
      `2: implementation project(':capacitor-android') // replaced capacitor-cordova-android-plugins`,
    ]);
  });

  it('leaves a flatDir whose dirs argument is not a plain string alone and reports it', () => {
    const source = `repositories {
    flatDir {
        dirs file('../capacitor-cordova-android-plugins/src/main/libs')
    }
}
`;

    const result = removeLegacyCordovaFromAppBuildGradle(source);

    expect(result.changed).toBe(false);
    expect(result.contents).toBe(source);
    expect(result.unhandled).toEqual([`3: dirs file('../capacitor-cordova-android-plugins/src/main/libs')`]);
  });

  it('never drops a directory that only starts with the Cordova project name', () => {
    const source = `repositories {
    flatDir {
        dirs '../capacitor-cordova-android-plugins-backup/libs', 'libs'
    }
}
`;

    const result = removeLegacyCordovaFromAppBuildGradle(source);

    expect(result.changed).toBe(false);
    expect(result.contents).toBe(source);
    expect(result.unhandled).toEqual([`3: dirs '../capacitor-cordova-android-plugins-backup/libs', 'libs'`]);
  });

  it('leaves other content inside the flatDir alone and reports it', () => {
    const source = `repositories {
    flatDir {
        content { includeGroup 'com.example.aars' }
        dirs '../capacitor-cordova-android-plugins/src/main/libs'
    }
    google()
}
`;

    const result = removeLegacyCordovaFromAppBuildGradle(source);

    expect(result.changed).toBe(false);
    expect(result.contents).toBe(source);
    expect(result.unhandled).toEqual([`4: dirs '../capacitor-cordova-android-plugins/src/main/libs'`]);
  });

  it('leaves an unrelated use of the name alone and reports it', () => {
    const source = `android {
    defaultConfig {
        manifestPlaceholders = [extra: '../capacitor-cordova-android-plugins/x']
    }
}
`;

    const result = removeLegacyCordovaFromAppBuildGradle(source);

    expect(result.changed).toBe(false);
    expect(result.contents).toBe(source);
    expect(result.unhandled).toEqual([`3: manifestPlaceholders = [extra: '../capacitor-cordova-android-plugins/x']`]);
  });

  it('leaves a block comment alone and reports it', () => {
    const source = `/*
 implementation project(':capacitor-cordova-android-plugins')
*/
dependencies {
}
`;

    const result = removeLegacyCordovaFromAppBuildGradle(source);

    expect(result.changed).toBe(false);
    expect(result.contents).toBe(source);
    expect(result.unhandled).toEqual([`2: implementation project(':capacitor-cordova-android-plugins')`]);
  });

  it('declines rather than corrupt a file whose string literals it cannot follow', () => {
    // A Groovy slashy string the scanner does not understand throws the string mask out of step.
    const source = `def p = /don't/
repositories {
    flatDir {
        dirs '../capacitor-cordova-android-plugins/src/main/libs', 'libs'
    }
}
`;

    const result = removeLegacyCordovaFromAppBuildGradle(source);

    expect(result.changed).toBe(false);
    expect(result.contents).toBe(source);
  });

  it('declines a file that holds a dollar-slashy string', () => {
    const source = `def d = $/x/$\ndependencies {\n    implementation project(':capacitor-cordova-android-plugins')\n}\n`;

    const result = removeLegacyCordovaFromAppBuildGradle(source);

    expect(result.changed).toBe(false);
    expect(result.contents).toBe(source);
    expect(result.unhandled).toEqual([`3: implementation project(':capacitor-cordova-android-plugins')`]);
  });

  it('leaves a dependency line that declares a second project alone and reports it', () => {
    // Both projects are arguments of the one `implementation` call. Cutting the line would take
    // `:native-sdk` out of the build with it.
    const source = `dependencies {\n    implementation project(':capacitor-cordova-android-plugins'), project(':native-sdk')\n}\n`;

    const result = removeLegacyCordovaFromAppBuildGradle(source);

    expect(result.changed).toBe(false);
    expect(result.contents).toBe(source);
    expect(result.contents).toContain(`project(':native-sdk')`);
    expect(result.unhandled).toEqual([
      `2: implementation project(':capacitor-cordova-android-plugins'), project(':native-sdk')`,
    ]);
  });

  it('removes a commented out dependency', () => {
    const result = removeLegacyCordovaFromAppBuildGradle(
      `dependencies {\n    // implementation project(':capacitor-cordova-android-plugins')\n    implementation 'x:y:1'\n}\n`,
    );

    expect(result.unhandled).toEqual([]);
    expect(result.contents).toBe(`dependencies {\n    implementation 'x:y:1'\n}\n`);
  });

  it('does not throw on a file with an unterminated string', () => {
    const source = `dependencies {\n    implementation project(':capacitor-cordova-android-plugins\n}\n`;

    const result = removeLegacyCordovaFromAppBuildGradle(source);

    expect(result.changed).toBe(false);
    expect(result.contents).toBe(source);
  });

  it('removes a dependency that carries a trailing comment', () => {
    // The match is made against the comment-blanked copy, so an ordinary note at the end of the
    // line does not make a file that is otherwise perfectly clean decline.
    const result = removeLegacyCordovaFromAppBuildGradle(
      `dependencies {\n    implementation project(':capacitor-cordova-android-plugins') // legacy\n}\n`,
    );

    expect(result.unhandled).toEqual([]);
    expect(result.changed).toBe(true);
    expect(result.contents).toBe(`dependencies {\n}\n`);
  });

  it('leaves a reference inside a multi-line string alone and reports it', () => {
    const source =
      `def notes = """\n` +
      `implementation project(':capacitor-cordova-android-plugins')\n` +
      `"""\n` +
      `dependencies {\n` +
      `}\n`;

    const result = removeLegacyCordovaFromAppBuildGradle(source);

    expect(result.changed).toBe(false);
    expect(result.contents).toBe(source);
    expect(result.unhandled).toEqual([`2: implementation project(':capacitor-cordova-android-plugins')`]);
  });

  it('does not leave a trailing blank line when the removed block ends the file', () => {
    const result = removeLegacyCordovaFromAppBuildGradle(`dependencies {
    implementation 'x:y:1'
}

repositories {
    flatDir {
        dirs '../capacitor-cordova-android-plugins/src/main/libs'
    }
}
`);

    expect(result.unhandled).toEqual([]);
    expect(result.contents).toBe(`dependencies {
    implementation 'x:y:1'
}
`);
  });

  it('takes a whitespace-only line with the block that ends a file with no final newline', () => {
    const result = removeLegacyCordovaFromAppBuildGradle(
      `dependencies {\n}\n   \nrepositories {\n    flatDir {\n` +
        `        dirs '../capacitor-cordova-android-plugins/src/main/libs'\n    }\n}`,
    );

    expect(result.unhandled).toEqual([]);
    expect(result.changed).toBe(true);
    // Not `}\n   ` with a line of spaces dangling off the end: the end-of-file cut absorbs the
    // blank line the same way a cut in the middle of the file does.
    expect(result.contents).toBe(`dependencies {\n}`);
  });

  it('takes a whitespace-only line with the block that ends a CRLF file with no final newline', () => {
    const result = removeLegacyCordovaFromAppBuildGradle(
      `dependencies {\r\n}\r\n   \r\nrepositories {\r\n    flatDir {\r\n` +
        `        dirs '../capacitor-cordova-android-plugins/src/main/libs'\r\n    }\r\n}`,
    );

    expect(result.unhandled).toEqual([]);
    expect(result.changed).toBe(true);
    expect(result.contents).toBe(`dependencies {\r\n}`);
  });

  it('leaves a dependency that is not the whole line alone and reports it', () => {
    // The `implementation` call is the tail of a conditional: cutting the line would silently
    // delete the `if` in front of it.
    const source = `dependencies {\n    if (legacy) implementation project(':capacitor-cordova-android-plugins')\n}\n`;

    const result = removeLegacyCordovaFromAppBuildGradle(source);

    expect(result.changed).toBe(false);
    expect(result.contents).toBe(source);
    expect(result.unhandled).toEqual([`2: if (legacy) implementation project(':capacitor-cordova-android-plugins')`]);
  });

  it('leaves a dependency whose line closes a block comment alone and reports it', () => {
    // The line is the tail of a comment that opened above it. Taking it would leave `/* note`
    // open and swallow the rest of the file.
    const source = `repositories {\n}\n/* note\n*/ implementation project(':capacitor-cordova-android-plugins')\n`;

    const result = removeLegacyCordovaFromAppBuildGradle(source);

    expect(result.changed).toBe(false);
    expect(result.contents).toBe(source);
    expect(result.contents).toContain('/* note');
    expect(result.unhandled).toEqual([`4: */ implementation project(':capacitor-cordova-android-plugins')`]);
  });

  it('does nothing to a file without references', () => {
    const source = `dependencies {\n    implementation project(':capacitor-android')\n}\n`;

    const result = removeLegacyCordovaFromAppBuildGradle(source);

    expect(result.changed).toBe(false);
    expect(result.contents).toBe(source);
    expect(result.unhandled).toEqual([]);
  });
});

/**
 * The detector `cap update android` stops on and `cap migrate` checks its own work against. The
 * rewrite tests above assert what is left in the file directly, so this is the only thing standing
 * behind it.
 */
describe('findLegacyCordovaGradleLines', () => {
  it('finds every line that mentions the Cordova project', () => {
    expect(findLegacyCordovaGradleLines(CAPACITOR_7_SETTINGS_GRADLE)).toEqual([
      `include ':capacitor-cordova-android-plugins'`,
      `project(':capacitor-cordova-android-plugins').projectDir = new File('./capacitor-cordova-android-plugins/')`,
    ]);
  });

  it('finds a commented out reference, which stops cap update android just the same', () => {
    expect(findLegacyCordovaGradleLines(`include ':app'\n// include ':capacitor-cordova-android-plugins'\n`)).toEqual([
      `// include ':capacitor-cordova-android-plugins'`,
    ]);
  });

  it('splits a CRLF file on its line endings', () => {
    expect(findLegacyCordovaGradleLines(`include ':app'\r\ninclude ':capacitor-cordova-android-plugins'\r\n`)).toEqual([
      `include ':capacitor-cordova-android-plugins'`,
    ]);
  });

  it('finds a name the Cordova one is only part of, so the rewrite has to leave it alone', () => {
    expect(findLegacyCordovaGradleLines(`include ':my-capacitor-cordova-android-plugins-fork'\n`)).toEqual([
      `include ':my-capacitor-cordova-android-plugins-fork'`,
    ]);
  });

  it('finds nothing in a clean file', () => {
    expect(findLegacyCordovaGradleLines(`include ':app'\n\napply from: 'capacitor.settings.gradle'\n`)).toEqual([]);
  });
});
