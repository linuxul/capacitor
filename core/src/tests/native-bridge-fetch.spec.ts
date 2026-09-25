/**
 * @jest-environment node
 */

// The fetch patch runs against the Fetch implementation of Node (Request, Response, Headers, Blob,
// AbortController), which jsdom does not provide. The DOM bits the bridge touches while it installs
// the patches are stubbed.

import { initBridge } from '../../native-bridge';
import type { CapacitorInstance, MessageCallData, PluginResult, WindowCapacitor } from '../definitions-internal';
import { createCapacitor } from '../runtime';

type NativeReply = Pick<PluginResult, 'success' | 'data' | 'error'> | null;

const g = globalThis as any;
const SERVER_URL = 'http://localhost';

describe('native-bridge fetch patch (CapacitorHttp)', () => {
  let cap: CapacitorInstance;
  let webFetch: jest.Mock;
  let nativeCalls: MessageCallData[];
  let nativeReply: NativeReply;
  const originalFetch = g.fetch;

  beforeEach(() => {
    nativeCalls = [];
    nativeReply = { success: true, data: { status: 200, headers: {}, data: '', url: '' } };
    webFetch = jest.fn(async () => new Response('from the web'));

    jest.spyOn(console, 'time').mockImplementation(() => undefined);
    jest.spyOn(console, 'timeEnd').mockImplementation(() => undefined);

    g.window = g;
    g.fetch = webFetch;
    g.XMLHttpRequest = class {};
    g.Document = class {};
    g.HTMLDocument = class {};
    g.document = {};
    g.WEBVIEW_SERVER_URL = SERVER_URL;
    g.CapacitorHttpAndroidInterface = { isEnabled: () => true };
    g.androidBridge = {
      postMessage: (message: string) => {
        const call = JSON.parse(message);
        nativeCalls.push(call);
        const reply = nativeReply;
        if (reply) {
          Promise.resolve().then(() =>
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
    delete g.Capacitor;

    initBridge(g as WindowCapacitor);
    cap = createCapacitor(g as WindowCapacitor);
  });

  afterEach(() => {
    jest.restoreAllMocks();
    g.fetch = originalFetch;
    for (const key of [
      'XMLHttpRequest',
      'Document',
      'HTMLDocument',
      'document',
      'WEBVIEW_SERVER_URL',
      'CapacitorHttpAndroidInterface',
      'androidBridge',
      'Capacitor',
      'CapacitorWebFetch',
      'CapacitorWebXMLHttpRequest',
    ]) {
      delete g[key];
    }
  });

  const respondWith = (data: Record<string, any>) => {
    nativeReply = { success: true, data };
  };

  it('keeps the body of a Request sent to the local server', async () => {
    await fetch(new Request(`${SERVER_URL}/api`, { method: 'POST', body: 'hello' }));

    const sent = webFetch.mock.calls[0][0] as Request;
    expect(sent.url).toBe(`${SERVER_URL}/api`);
    expect(await sent.text()).toBe('hello');
  });

  it('does not modify the init object of the caller', async () => {
    const body = new FormData();
    body.append('field', 'value');
    const init = { method: 'POST', body, headers: { 'Content-Type': 'multipart/form-data' } };

    await fetch(`${SERVER_URL}/upload`, init);

    expect(init.headers).toEqual({ 'Content-Type': 'multipart/form-data' });
    const sent = webFetch.mock.calls[0][0] as Request;
    expect(sent.headers.get('Content-Type')).toMatch(/^multipart\/form-data; boundary=/);
  });

  it('sends GET requests to other origins through the native proxy', async () => {
    await fetch('https://api.example.com/items?page=2', { headers: { 'User-Agent': 'custom' } });

    const sent = webFetch.mock.calls[0][0] as Request;
    const proxied = new URL(sent.url);
    expect(proxied.origin).toBe(SERVER_URL);
    expect(proxied.pathname).toBe('/_capacitor_http_interceptor_');
    expect(proxied.searchParams.get('u')).toBe('https://api.example.com/items?page=2');
    expect(sent.headers.get('x-cap-user-agent')).toBe('custom');
    expect(nativeCalls).toHaveLength(0);
  });

  it('sends other methods to CapacitorHttp and builds the response', async () => {
    respondWith({
      status: 201,
      headers: { 'Content-Type': 'application/json' },
      data: { id: 7 },
      url: 'https://api.example.com/items',
    });

    const response = await fetch('https://api.example.com/items', { method: 'POST', body: 'x' });

    expect(nativeCalls[0].pluginId).toBe('CapacitorHttp');
    expect(nativeCalls[0].options).toMatchObject({ url: 'https://api.example.com/items', method: 'POST' });
    expect(response.status).toBe(201);
    expect(response.url).toBe('https://api.example.com/items');
    expect(await response.json()).toEqual({ id: 7 });
  });

  it.each([204, 205, 304])('returns a null body for status %i', async (status) => {
    respondWith({ status, headers: { 'Content-Type': 'text/plain' }, data: '', url: 'https://api.example.com/x' });

    const response = await fetch('https://api.example.com/x', { method: 'PUT', body: 'x' });

    expect(response.status).toBe(status);
    expect(response.body).toBeNull();
  });

  it('accepts a native response without headers', async () => {
    respondWith({ status: 200, data: 'plain', url: 'https://api.example.com/x' });

    const response = await fetch('https://api.example.com/x', { method: 'DELETE' });

    expect(await response.text()).toBe('plain');
  });

  it('rejects with a TypeError when native reports a status a Response cannot have', async () => {
    respondWith({ status: 0, headers: {}, data: '', url: '' });

    await expect(fetch('https://api.example.com/x', { method: 'POST', body: 'x' })).rejects.toBeInstanceOf(TypeError);
  });

  it('does not send a request whose signal is already aborted', async () => {
    const controller = new AbortController();
    controller.abort();

    await expect(
      fetch('https://api.example.com/x', { method: 'POST', body: 'x', signal: controller.signal }),
    ).rejects.toMatchObject({ name: 'AbortError' });
    expect(nativeCalls).toHaveLength(0);
  });

  it('rejects when the signal aborts while native is still working', async () => {
    nativeReply = null;
    const controller = new AbortController();

    const pending = fetch('https://api.example.com/x', { method: 'POST', body: 'x', signal: controller.signal });
    await Promise.resolve();
    controller.abort();

    await expect(pending).rejects.toMatchObject({ name: 'AbortError' });
  });

  it('sends a Blob body base64 encoded', async () => {
    const bytes = new Uint8Array([0, 1, 2, 250, 255]);

    await fetch('https://api.example.com/upload', {
      method: 'POST',
      body: new Blob([bytes], { type: 'application/octet-stream' }),
    });

    expect(nativeCalls[0].options).toMatchObject({
      dataType: 'file',
      data: Buffer.from(bytes).toString('base64'),
    });
  });

  it('sends an ArrayBuffer body like a Uint8Array', async () => {
    await fetch('https://api.example.com/echo', {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain' },
      body: new TextEncoder().encode('bytes').buffer,
    });

    expect(nativeCalls[0].options).toMatchObject({ dataType: 'text', data: 'bytes' });
  });
});
