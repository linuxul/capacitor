/**
 * @jest-environment jsdom
 */

import { CapacitorCookiesPluginWeb, CapacitorHttpPluginWeb } from '../core-plugins';

describe('CapacitorHttpPluginWeb', () => {
  const fetchMock = jest.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    fetchMock.mockResolvedValue({
      ok: true,
      status: 200,
      url: 'https://example.com/',
      headers: { get: (): string => 'text/plain', forEach: (): void => undefined },
      text: async () => 'ok',
    });
    (globalThis as any).fetch = fetchMock;
  });

  afterEach(() => {
    delete (globalThis as any).fetch;
  });

  it('joins array params without an empty pair between keys', async () => {
    await new CapacitorHttpPluginWeb().request({
      url: 'https://example.com/search',
      params: { tag: ['a', 'b'], page: '2' },
    });

    expect(fetchMock.mock.calls[0][0]).toBe('https://example.com/search?tag=a&tag=b&page=2');
  });

  it('keeps an array param that is the last one', async () => {
    await new CapacitorHttpPluginWeb().request({
      url: 'https://example.com/search',
      params: { page: '2', tag: ['a', 'b'] },
    });

    expect(fetchMock.mock.calls[0][0]).toBe('https://example.com/search?page=2&tag=a&tag=b');
  });
});

describe('CapacitorCookiesPluginWeb', () => {
  const clearCookies = () => {
    document.cookie.split(';').forEach((cookie) => {
      const name = cookie.split('=')[0].trim();
      document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT`;
    });
  };

  beforeEach(clearCookies);
  afterEach(clearCookies);

  it('reads values that contain "="', async () => {
    document.cookie = 'token=a=b=c';

    const cookies = await new CapacitorCookiesPluginWeb().getCookies();

    expect(cookies.token).toBe('a=b=c');
  });

  it('does not throw on a cookie without "="', async () => {
    const descriptor = Object.getOwnPropertyDescriptor(Document.prototype, 'cookie');
    Object.defineProperty(document, 'cookie', { configurable: true, get: () => 'flag; session=1' });

    try {
      const cookies = await new CapacitorCookiesPluginWeb().getCookies();
      expect(cookies).toEqual({ flag: '', session: '1' });
    } finally {
      delete (document as any).cookie;
      if (descriptor) {
        Object.defineProperty(Document.prototype, 'cookie', descriptor);
      }
    }
  });
});
