/**
 * @jest-environment jsdom
 */

import { webcrypto } from 'crypto';

import { initBridge } from '../../native-bridge';
import type { CapacitorGlobal } from '../definitions';
import type { CapacitorInstance, MessageCallData, PluginResult, WindowCapacitor } from '../definitions-internal';
import { initCapacitorGlobal } from '../runtime';

// Listeners registered with a native plugin keep a callback in the bridge until they are removed.
describe('native-bridge listener callbacks', () => {
  let win: WindowCapacitor;
  let cap: CapacitorGlobal;
  let posted: MessageCallData[];
  let postMessage: jest.Mock;

  const nativeInstance = () => win.Capacitor as CapacitorInstance;

  const sendEvent = (call: MessageCallData, data: Record<string, unknown>) =>
    nativeInstance().fromNative({
      callbackId: call.callbackId,
      pluginId: call.pluginId,
      methodName: call.methodName,
      success: true,
      save: true,
      data,
    } as PluginResult);

  const addListenerCall = (pluginId: string, eventName: string) => {
    const call = posted.find(
      (m) => m.pluginId === pluginId && m.methodName === 'addListener' && m.options.eventName === eventName,
    );
    if (!call) throw new Error(`no addListener call for ${pluginId}.${eventName}`);
    return call;
  };

  beforeEach(() => {
    posted = [];
    postMessage = jest.fn((message: string) => {
      const call = JSON.parse(message);
      posted.push(call);
      // native resolves removeAllListeners; addListener and removeListener get no direct answer
      if (call.methodName === 'removeAllListeners') {
        Promise.resolve().then(() =>
          nativeInstance().fromNative({
            callbackId: call.callbackId,
            pluginId: call.pluginId,
            methodName: call.methodName,
            success: true,
            data: {},
          } as PluginResult),
        );
      }
    });
    win = { crypto: webcrypto as unknown as Crypto, androidBridge: { postMessage } } as WindowCapacitor;
    initBridge(win);

    const methods = [
      { name: 'addListener' },
      { name: 'removeListener' },
      { name: 'removeAllListeners', rtype: 'promise' },
    ];
    (win.Capacitor as any).PluginHeaders = [
      { name: 'Echo', methods },
      { name: 'Other', methods },
    ];
    cap = initCapacitorGlobal(win);
  });

  it('releases the callback of a removed listener', async () => {
    const Echo = cap.registerPlugin<any>('Echo');
    const listener = jest.fn();

    const handle = await Echo.addListener('tick', listener);
    const added = addListenerCall('Echo', 'tick');
    sendEvent(added, { n: 1 });
    await handle.remove();
    sendEvent(added, { n: 2 });

    expect(listener).toHaveBeenCalledTimes(1);
    expect(listener).toHaveBeenCalledWith({ n: 1 });
    const removal = posted.find((m) => m.methodName === 'removeListener');
    expect(removal?.callbackId).toBe('-1');
    expect(removal?.options).toEqual({ eventName: 'tick', callbackId: added.callbackId });
  });

  it('releases the listener callbacks of the plugin on removeAllListeners', async () => {
    const Echo = cap.registerPlugin<any>('Echo');
    const Other = cap.registerPlugin<any>('Other');
    const tick = jest.fn();
    const tock = jest.fn();
    const other = jest.fn();

    await Echo.addListener('tick', tick);
    await Echo.addListener('tock', tock);
    await Other.addListener('tick', other);
    await Echo.removeAllListeners();

    sendEvent(addListenerCall('Echo', 'tick'), {});
    sendEvent(addListenerCall('Echo', 'tock'), {});
    sendEvent(addListenerCall('Other', 'tick'), {});

    expect(tick).not.toHaveBeenCalled();
    expect(tock).not.toHaveBeenCalled();
    expect(other).toHaveBeenCalledTimes(1);
  });

  it('rejects addListener when the call cannot be sent, without calling the listener', async () => {
    jest.spyOn(console, 'error').mockImplementation(() => undefined);
    postMessage.mockImplementation(() => {
      throw new Error('bridge gone');
    });
    const Echo = cap.registerPlugin<any>('Echo');
    const listener = jest.fn();

    await expect(Echo.addListener('tick', listener)).rejects.toThrow('bridge gone');
    expect(listener).not.toHaveBeenCalled();
  });
});
