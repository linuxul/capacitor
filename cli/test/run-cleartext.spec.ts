import { warnIfCleartextBlocked } from '../src/android/cleartext';
import { runAndroid } from '../src/android/run';
import type { Config } from '../src/definitions';
import { runIOS } from '../src/ios/run';
import { logger } from '../src/log';
import type { RunCommandOptions } from '../src/tasks/run';
import { runCommand } from '../src/tasks/run';
import { CapLiveReloadHelper } from '../src/util/livereload';

// `cap run` itself: that the cleartext warning is wired into the live reload path for Android and
// nowhere else. What the warning decides once it is called lives in android-cleartext.spec.ts.
jest.mock('../src/android/cleartext', () => ({ warnIfCleartextBlocked: jest.fn() }));
jest.mock('../src/android/run', () => ({ runAndroid: jest.fn() }));
jest.mock('../src/ios/run', () => ({ runIOS: jest.fn() }));
jest.mock('../src/util/native-run', () => ({ getPlatformTargets: jest.fn() }));
jest.mock('../src/tasks/sync', () => ({ sync: jest.fn() }));
jest.mock('../src/util/livereload', () => ({
  CapLiveReloadHelper: {
    getIpAddress: jest.fn(),
    editCapConfigForLiveReload: jest.fn(),
    revertCapConfigForLiveReload: jest.fn(),
  },
}));
jest.mock('../src/common', () => ({
  isValidPlatform: jest.fn(async (platform: string) => ['android', 'ios', 'web'].includes(platform)),
  selectPlatforms: jest.fn(async (_config: unknown, platform: string) => [platform]),
  promptForPlatform: jest.fn(async (platforms: string[]) => platforms[0]),
  resolvePlatform: jest.fn(() => null),
  runPlatformHook: jest.fn(),
  getPlatformTargetName: jest.fn((target: any) => target.name),
}));

const config = {
  app: { rootDir: '/app' },
  android: { name: 'android' },
  ios: { name: 'ios' },
  web: { name: 'web' },
} as unknown as Config;

describe('cap run', () => {
  let infoSpy: jest.SpyInstance;
  let running: Promise<void>;
  let order: string[];

  beforeEach(() => {
    // `runCommand` never returns on the live reload path - it waits for Ctrl+C - so the timers it
    // leaves behind are faked, and the last thing it logs is what says it got that far.
    jest.useFakeTimers({ doNotFake: ['nextTick', 'queueMicrotask', 'setImmediate'] });
    jest.clearAllMocks();
    (CapLiveReloadHelper.getIpAddress as jest.Mock).mockReturnValue('192.168.1.5');

    order = [];
    let started: () => void;
    running = new Promise<void>((resolve) => {
      started = resolve;
    });
    infoSpy = jest.spyOn(logger, 'info').mockImplementation((message: any) => {
      if (String(message).includes('live reload')) {
        order.push('app is up');
        started();
      }
      return undefined;
    });
  });

  afterEach(() => {
    infoSpy.mockRestore();
    process.removeAllListeners('SIGINT');
    jest.useRealTimers();
  });

  /** Starts a live reload run and waits until it is up, since it never finishes on its own. */
  async function startLiveReload(platform: string, options: RunCommandOptions = {}): Promise<void> {
    void runCommand(config, platform, { liveReload: true, ...options });
    await running;
  }

  it('warns about cleartext traffic for cap run android -l', async () => {
    await startLiveReload('android');

    expect(runAndroid).toHaveBeenCalledTimes(1);
    expect(warnIfCleartextBlocked).toHaveBeenCalledTimes(1);
    expect((warnIfCleartextBlocked as jest.Mock).mock.calls[0][0]).toBe(config);
    expect((warnIfCleartextBlocked as jest.Mock).mock.calls[0][1]).toMatchObject({ liveReload: true });
  });

  it('does not warn for cap run ios -l', async () => {
    await startLiveReload('ios');

    expect(runIOS).toHaveBeenCalledTimes(1);
    expect(warnIfCleartextBlocked).not.toHaveBeenCalled();
  });

  it('does not warn for cap run web -l', async () => {
    await startLiveReload('web');

    expect(warnIfCleartextBlocked).not.toHaveBeenCalled();
  });

  it('does not warn for a run without live reload', async () => {
    await runCommand(config, 'android', {});

    expect(runAndroid).toHaveBeenCalledTimes(1);
    expect(warnIfCleartextBlocked).not.toHaveBeenCalled();
  });

  it('warns before it reports that the app is up, so the message is not scrolled past', async () => {
    // The mock yields before it records, so the order only comes out right if `runCommand`
    // actually waits for the warning. A mock that recorded synchronously would be in order even
    // if the call were left unawaited.
    (warnIfCleartextBlocked as jest.Mock).mockImplementation(async () => {
      await new Promise((resolve) => process.nextTick(resolve));
      order.push('cleartext warning');
    });

    await startLiveReload('android');

    expect(order).toEqual(['cleartext warning', 'app is up']);
  });
});
