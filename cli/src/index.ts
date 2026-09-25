import { Option, program } from 'commander';
import { resolve } from 'path';

import c from './colors';
import { loadConfig } from './config';
import type { Config, PackageManager, Writable } from './definitions';
import { fatal, isFatal } from './errors';
import { logger, output } from './log';
import { wrapAction } from './util/cli';
import { emoji as _e } from './util/emoji';

process.on('unhandledRejection', (error) => {
  console.error(c.failure('[fatal]'), error);
});

export async function run(): Promise<void> {
  try {
    const config = await loadConfig();
    runProgram(config);
  } catch (e: any) {
    process.exitCode = isFatal(e) ? e.exitCode : 1;
    logger.error(e.message ? e.message : String(e));
  }
}

async function getPackageManager(config: Config, packageManager: any): Promise<PackageManager> {
  if (packageManager === 'cocoapods') {
    if ((await config.ios.packageManager) === 'bundler') {
      return 'bundler';
    }
    return 'Cocoapods';
  }
  return 'SPM';
}

export function runProgram(config: Config): void {
  program.version(config.cli.package.version);

  program
    .command('config', { hidden: true })
    .description(`print evaluated Capacitor config`)
    .option('--json', 'Print in JSON format')
    .action(
      wrapAction(async ({ json }) => {
        const { configCommand } = await import('./tasks/config');
        await configCommand(config, json);
      }),
    );

  program
    .command('init [appName] [appId]')
    .description(`Initialize Capacitor configuration`)
    .option('--web-dir <value>', 'Optional: Directory of your projects built web assets')
    .option('--skip-appid-validation', 'Optional: Skip validating the app ID for iOS and Android compatibility')
    .action(
      wrapAction(async (appName, appId, { webDir, skipAppidValidation }) => {
        const { initCommand } = await import('./tasks/init');
        await initCommand(config, appName, appId, webDir, skipAppidValidation);
      }),
    );

  program
    .command('serve', { hidden: true })
    .description('Serves a Capacitor Progressive Web App in the browser')
    .action(
      wrapAction(async () => {
        const { serveCommand } = await import('./tasks/serve');
        await serveCommand();
      }),
    );

  program
    .command('sync [platform]')
    .description(`${c.input('copy')} + ${c.input('update')}`)
    .option('--deployment', 'Optional: if provided, pod install will use --deployment option')
    .option(
      '--inline',
      'Optional: if true, all source maps will be inlined for easier debugging on mobile devices',
      false,
    )
    .action(
      wrapAction(async (platform, { deployment, inline }) => {
        const { syncCommand } = await import('./tasks/sync');
        await syncCommand(config, platform, deployment, inline);
      }),
    );

  program
    .command('update [platform]')
    .description(`updates the native plugins and dependencies based on ${c.strong('package.json')}`)
    .option('--deployment', 'Optional: if provided, pod install will use --deployment option')
    .action(
      wrapAction(async (platform, { deployment }) => {
        const { updateCommand } = await import('./tasks/update');
        await updateCommand(config, platform, deployment);
      }),
    );

  program
    .command('copy [platform]')
    .description('copies the web app build into the native app')
    .option(
      '--inline',
      'Optional: if true, all source maps will be inlined for easier debugging on mobile devices',
      false,
    )
    .action(
      wrapAction(async (platform, { inline }) => {
        const { copyCommand } = await import('./tasks/copy');
        await copyCommand(config, platform, inline);
      }),
    );

  program
    .command('build <platform>')
    .description('builds the release version of the selected platform')
    .option('--scheme <schemeToBuild>', 'iOS Scheme to build')
    .option('--flavor <flavorToBuild>', 'Android Flavor to build')
    .option('--keystorepath <keystorePath>', 'Path to the keystore')
    .option('--keystorepass <keystorePass>', 'Password to the keystore')
    .option('--keystorealias <keystoreAlias>', 'Key Alias in the keystore')
    .option('--configuration <name>', 'Configuration name of the iOS Scheme')
    .option('--keystorealiaspass <keystoreAliasPass>', 'Password for the Key Alias')
    .addOption(
      new Option('--androidreleasetype <androidreleasetype>', 'Android release type; APK or AAB').choices([
        'AAB',
        'APK',
      ]),
    )
    .addOption(
      new Option('--signing-type <signingtype>', 'Program used to sign apps (default: jarsigner)').choices([
        'apksigner',
        'jarsigner',
      ]),
    )
    .addOption(
      new Option('--xcode-team-id <xcodeTeamID>', 'The Developer team to use for building and exporting the archive'),
    )
    .addOption(
      new Option(
        '--xcode-export-method <xcodeExportMethod>',
        'Describes how xcodebuild should export the archive (default:  app-store-connect)',
      ).choices([
        'app-store-connect',
        'release-testing',
        'enterprise',
        'debugging',
        'developer-id',
        'mac-application',
        'validation',
      ]),
    )
    .addOption(
      new Option(
        '--xcode-signing-style <xcodeSigningStyle>',
        'The iOS signing style to use when building the app for distribution (default: automatic)',
      ).choices(['automatic', 'manual']),
    )
    .addOption(
      new Option(
        '--xcode-signing-certificate <xcodeSigningCertificate>',
        'A certificate name, SHA-1 hash, or automatic selector to use for signing for iOS builds',
      ),
    )
    .addOption(
      new Option(
        '--xcode-provisioning-profile <xcodeProvisioningProfile>',
        'A provisioning profile name or UUID for iOS builds',
      ),
    )
    .action(
      wrapAction(
        async (
          platform,
          {
            scheme,
            flavor,
            keystorepath,
            keystorepass,
            keystorealias,
            keystorealiaspass,
            androidreleasetype,
            signingType,
            configuration,
            xcodeTeamId,
            xcodeExportMethod,
            xcodeSigningStyle,
            xcodeSigningCertificate,
            xcodeProvisioningProfile,
          },
        ) => {
          const { buildCommand } = await import('./tasks/build');
          await buildCommand(config, platform, {
            scheme,
            flavor,
            keystorepath,
            keystorepass,
            keystorealias,
            keystorealiaspass,
            androidreleasetype,
            signingtype: signingType,
            configuration,
            xcodeTeamId,
            xcodeExportMethod,
            xcodeSigningType: xcodeSigningStyle,
            xcodeSigningCertificate,
            xcodeProvisioningProfile,
          });
        },
      ),
    );
  program
    .command(`run [platform]`)
    .description(`runs ${c.input('sync')}, then builds and deploys the native app`)
    .option('--scheme <schemeName>', 'set the scheme of the iOS project')
    .option('--flavor <flavorName>', 'set the flavor of the Android project (flavor dimensions not yet supported)')
    .option('--list', 'list targets, then quit')
    .addOption(new Option('--json').hideHelp())
    .option('--target <id>', 'use a specific target')
    .option('--target-name <name>', 'use a specific target by name')
    .option(
      '--target-name-sdk-version <version>',
      'use a specific sdk version when using --target-name, ex: 26.0 (for iOS 26) or 35 (for Android API 35)',
    )
    .option('--no-sync', `do not run ${c.input('sync')}`)
    .option('--forwardPorts <port:port>', 'Automatically run "adb reverse" for better live-reloading support')
    .option('-l, --live-reload', 'Set live-reload URL via CLI (uses defaults, overrides server.url config)')
    .option('--host <host>', 'Configure host for live-reload URL (used with --live-reload)')
    .option('--port <port>', 'Configure port for live-reload URL (used with --live-reload)')
    .option('--configuration <name>', 'Configuration name of the iOS Scheme')
    .option('--https', 'Use https:// instead of http:// for live-reload URL (used with --live-reload)')
    .action(
      wrapAction(
        async (
          platform,
          {
            scheme,
            flavor,
            list,
            json,
            target,
            targetName,
            targetNameSdkVersion,
            sync,
            forwardPorts,
            liveReload,
            host,
            port,
            configuration,
            https,
          },
        ) => {
          const { runCommand } = await import('./tasks/run');
          await runCommand(config, platform, {
            scheme,
            flavor,
            list,
            json,
            target,
            targetName,
            targetNameSdkVersion,
            sync,
            forwardPorts,
            liveReload,
            host,
            port,
            configuration,
            https,
          });
        },
      ),
    );

  program
    .command('open [platform]')
    .description('opens the native project workspace (Xcode for iOS)')
    .action(
      wrapAction(async (platform) => {
        const { openCommand } = await import('./tasks/open');
        await openCommand(config, platform);
      }),
    );

  program
    .command('add [platform]')
    .description('add a native platform project')
    .option(
      '--packagemanager <packageManager>',
      'The package manager to use for dependency installs (CocoaPods or SPM)',
    )
    .action(
      wrapAction(async (platform, { packagemanager }) => {
        const { addCommand } = await import('./tasks/add');

        const configWritable: Writable<Config> = config as Writable<Config>;
        configWritable.ios.packageManager = getPackageManager(config, packagemanager?.toLowerCase());
        if (packagemanager?.toLowerCase() === 'CocoaPods'.toLowerCase()) {
          configWritable.cli.assets.ios.platformTemplateArchive = 'ios-pods-template.tar.gz';
          configWritable.cli.assets.ios.platformTemplateArchiveAbs = resolve(
            configWritable.cli.assetsDirAbs,
            configWritable.cli.assets.ios.platformTemplateArchive,
          );
        }

        await addCommand(configWritable as Config, platform);
      }),
    );

  program
    .command('ls [platform]')
    .description('list installed Capacitor plugins')
    .action(
      wrapAction(async (platform) => {
        const { listCommand } = await import('./tasks/list');
        await listCommand(config, platform);
      }),
    );

  program
    .command('doctor [platform]')
    .description('checks the current setup for common errors')
    .action(
      wrapAction(async (platform) => {
        const { doctorCommand } = await import('./tasks/doctor');
        await doctorCommand(config, platform);
      }),
    );

  program
    .command('telemetry [on|off]', { hidden: true })
    .description('report that this fork collects no telemetry')
    .action(
      wrapAction(async (onOrOff) => {
        const { telemetryCommand } = await import('./tasks/telemetry');
        await telemetryCommand(onOrOff);
      }),
    );

  program
    .command('migrate')
    .option('--noprompt', 'do not prompt for confirmation')
    .option('--packagemanager <packageManager>', 'The package manager to use for dependency installs (npm, pnpm, yarn)')
    .description('Migrate your current Capacitor app to the latest major version of Capacitor.')
    .action(
      wrapAction(async ({ noprompt, packagemanager }) => {
        const { migrateCommand } = await import('./tasks/migrate');
        await migrateCommand(config, noprompt, packagemanager);
      }),
    );

  program
    .command('spm-migration-assistant')
    .description('Remove Cocoapods from project and switch to Swift Package Manager')
    .action(
      wrapAction(async () => {
        const { migrateToSPM } = await import('./tasks/migrate-spm');
        await migrateToSPM(config);
      }),
    );

  program.arguments('[command]').action(
    wrapAction(async (cmd) => {
      if (typeof cmd === 'undefined') {
        output.write(
          `\n  ${_e('⚡️', '--')}  ${c.strong(
            'Capacitor - Cross-Platform apps with JavaScript and the Web',
          )}  ${_e('⚡️', '--')}\n\n`,
        );
        program.outputHelp();
      } else {
        fatal(`Unknown command: ${c.input(cmd)}`);
      }
    }),
  );

  program.parse(process.argv);
}
