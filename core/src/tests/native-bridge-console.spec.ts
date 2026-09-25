/**
 * @jest-environment jsdom
 */

import { initBridge } from '../../native-bridge';
import type { WindowCapacitor } from '../definitions-internal';

// On iOS the bridge forwards console output to native (the Xcode console). This covers that patch and
// window.onerror reporting, using a separate console object so Jest's own console stays untouched.
describe('native-bridge iOS console patch', () => {
  let win: WindowCapacitor;
  let posted: any[];
  let postMessage: jest.Mock;
  let original: Record<string, jest.Mock>;

  beforeEach(() => {
    posted = [];
    postMessage = jest.fn((message) => posted.push(message));
    original = {
      debug: jest.fn(),
      error: jest.fn(),
      info: jest.fn(),
      log: jest.fn(),
      trace: jest.fn(),
      warn: jest.fn(),
    };
    // eslint-disable-next-line @typescript-eslint/no-empty-function
    window.prompt = () => null;
    win = {
      console: { ...original },
      webkit: { messageHandlers: { bridge: { postMessage } } },
    } as unknown as WindowCapacitor;
    initBridge(win);
  });

  const consoleMessages = () => posted.filter((m) => m.pluginId === 'Console').map((m) => m.options);

  it('forwards the level and the message, then calls the original method', () => {
    win.console.warn('careful', 3);

    expect(consoleMessages()).toEqual([{ level: 'warn', message: 'careful 3' }]);
    expect(original.warn).toHaveBeenCalledWith('careful', 3);
  });

  it('serializes an Error with its name and message instead of "{}"', () => {
    win.console.error(new TypeError('bad input'));

    expect(consoleMessages()[0].message).toMatch(/^TypeError: bad input/);
  });

  it('serializes an object that refers to itself', () => {
    const node: any = { name: 'root' };
    node.self = node;

    win.console.log(node);

    expect(consoleMessages()[0].message).toBe('{"name":"root","self":"[Circular]"}');
  });

  it('leaves the patched methods writable and configurable', () => {
    const descriptor = Object.getOwnPropertyDescriptor(win.console, 'log');

    expect(descriptor?.writable).toBe(true);
    expect(descriptor?.configurable).toBe(true);
  });

  it('does not recurse when forwarding to native fails', () => {
    postMessage.mockImplementation(() => {
      throw new Error('bridge gone');
    });

    expect(() => win.console.log('hello')).not.toThrow();
    expect(postMessage).toHaveBeenCalledTimes(1);
    expect(original.log).toHaveBeenCalledWith('hello');
    expect(original.error).toHaveBeenCalledTimes(1);
  });

  it('reports a window error whose message is an Event', () => {
    const error = new Error('load failed');

    expect(() => win.Capacitor?.handleWindowError(new Event('error'), 'app.js', 1, 2, error)).not.toThrow();
    const report = posted.find((m) => m.type === 'js.error');
    expect(report.error.message).toBe('load failed');
    expect(JSON.parse(report.error.errorObject)).toMatchObject({ name: 'Error', message: 'load failed' });
  });
});
