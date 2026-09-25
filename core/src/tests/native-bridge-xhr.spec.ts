/**
 * @jest-environment jsdom
 */

import { initBridge } from '../../native-bridge';
import type { CapacitorInstance, MessageCallData, PluginResult, WindowCapacitor } from '../definitions-internal';
import { createCapacitor } from '../runtime';

type NativeReply = Pick<PluginResult, 'success' | 'data' | 'error'> | null;

const w = window as any;
const SERVER_URL = 'http://localhost';
const WebXMLHttpRequest = window.XMLHttpRequest;
const webSend = WebXMLHttpRequest.prototype.send;

describe('native-bridge XMLHttpRequest patch (CapacitorHttp)', () => {
  let cap: CapacitorInstance;
  let nativeCalls: MessageCallData[];
  let nativeReply: NativeReply;
  let openSpy: jest.SpyInstance;

  beforeEach(() => {
    nativeCalls = [];
    nativeReply = { success: true, data: { status: 200, headers: {}, data: '', url: '' } };
    jest.spyOn(console, 'time').mockImplementation(() => undefined);
    jest.spyOn(console, 'timeEnd').mockImplementation(() => undefined);
    openSpy = jest.spyOn(WebXMLHttpRequest.prototype, 'open').mockImplementation(() => undefined);

    w.WEBVIEW_SERVER_URL = SERVER_URL;
    w.CapacitorHttpAndroidInterface = { isEnabled: () => true };
    w.androidBridge = {
      postMessage: (message: string) => {
        const call = JSON.parse(message);
        nativeCalls.push(call);
        const reply = nativeReply;
        if (reply) {
          setTimeout(() =>
            cap.fromNative({
              callbackId: call.callbackId,
              pluginId: call.pluginId,
              methodName: call.methodName,
              ...reply,
            }),
          );
        }
      },
    };
    delete w.Capacitor;

    initBridge(w as WindowCapacitor);
    cap = createCapacitor(w as WindowCapacitor);
  });

  afterEach(() => {
    jest.restoreAllMocks();
    window.XMLHttpRequest = WebXMLHttpRequest;
    for (const key of ['WEBVIEW_SERVER_URL', 'CapacitorHttpAndroidInterface', 'androidBridge', 'Capacitor']) {
      delete w[key];
    }
  });

  const whenDone = (xhr: XMLHttpRequest) =>
    new Promise<string[]>((resolve) => {
      const events: string[] = [];
      for (const type of ['load', 'error', 'abort']) {
        xhr.addEventListener(type, () => events.push(type));
      }
      xhr.addEventListener('loadend', () => resolve(events));
    });

  it('keeps instanceof, the static constants and the WebView prototype of XMLHttpRequest', () => {
    const xhr = new XMLHttpRequest();

    expect(xhr).toBeInstanceOf(window.XMLHttpRequest);
    expect(xhr).toBeInstanceOf(WebXMLHttpRequest);
    expect(XMLHttpRequest.DONE).toBe(4);
    expect(WebXMLHttpRequest.prototype.send).toBe(webSend);
  });

  it('sends a POST to another origin with CapacitorHttp', async () => {
    nativeReply = {
      success: true,
      data: {
        status: 201,
        headers: { 'Content-Type': 'application/json', 'X-Request-Id': 'abc' },
        data: { id: 7 },
        url: 'https://api.example.com/items',
      },
    };
    const xhr = new XMLHttpRequest();
    const done = whenDone(xhr);

    xhr.open('POST', 'https://api.example.com/items');
    xhr.setRequestHeader('X-Custom', 'one');
    xhr.setRequestHeader('X-Custom', 'two');
    xhr.send('{"name":"x"}');

    expect(await done).toEqual(['load']);
    expect(openSpy).not.toHaveBeenCalled();
    expect(nativeCalls[0].options).toMatchObject({
      url: 'https://api.example.com/items',
      method: 'POST',
      headers: { 'X-Custom': 'one, two' },
    });
    expect(xhr.readyState).toBe(4);
    expect(xhr.status).toBe(201);
    expect(xhr.responseURL).toBe('https://api.example.com/items');
    expect(JSON.parse(xhr.responseText)).toEqual({ id: 7 });
    expect(xhr.getResponseHeader('x-request-id')).toBe('abc');
    expect(xhr.getResponseHeader('X-Missing')).toBeNull();
    expect(xhr.getAllResponseHeaders()).toBe('content-type: application/json\r\nx-request-id: abc\r\n');
  });

  it('can be opened twice', () => {
    const xhr = new XMLHttpRequest();

    xhr.open('POST', 'https://api.example.com/a');
    expect(() => xhr.open('PUT', 'https://api.example.com/b')).not.toThrow();
  });

  it('accepts a URL object', async () => {
    const xhr = new XMLHttpRequest();
    const done = whenDone(xhr);

    xhr.open('POST', new URL('https://api.example.com/items') as any);
    xhr.send();

    expect(await done).toEqual(['load']);
    expect(nativeCalls[0].options).toMatchObject({ url: 'https://api.example.com/items' });
  });

  it('reports a native failure as a network error', async () => {
    nativeReply = { success: false, data: null, error: { message: 'offline' } };
    const xhr = new XMLHttpRequest();
    const done = whenDone(xhr);

    xhr.open('POST', 'https://api.example.com/items');
    xhr.send();

    expect(await done).toEqual(['error']);
    expect(xhr.readyState).toBe(4);
    expect(xhr.status).toBe(0);
    expect(xhr.responseText).toBe('');
  });

  it('ignores the native response after abort()', async () => {
    const xhr = new XMLHttpRequest();
    const done = whenDone(xhr);

    xhr.open('POST', 'https://api.example.com/items');
    xhr.send();
    xhr.abort();

    expect(await done).toEqual(['abort']);
    await new Promise((resolve) => setTimeout(resolve, 10));
    expect(xhr.readyState).toBe(0);
    expect(xhr.status).toBe(0);
  });

  it('leaves a synchronous request to the WebView', () => {
    const xhr = new XMLHttpRequest();

    xhr.open('POST', 'https://api.example.com/items', false);

    expect(openSpy).toHaveBeenCalledWith('POST', 'https://api.example.com/items', false, undefined, undefined);
  });

  it('routes a GET to another origin through the native proxy', () => {
    const xhr = new XMLHttpRequest();

    xhr.open('GET', 'https://api.example.com/items?page=2');

    const proxied = new URL(openSpy.mock.calls[0][1]);
    expect(proxied.origin).toBe(SERVER_URL);
    expect(proxied.searchParams.get('u')).toBe('https://api.example.com/items?page=2');
  });
});
