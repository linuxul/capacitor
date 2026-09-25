import type { HttpResponse } from '../core-plugins';
import type { CapacitorInstance, WindowCapacitor } from '../definitions-internal';

import { convertBody } from './http-body';

const CAPACITOR_HTTP_INTERCEPTOR = '/_capacitor_http_interceptor_';
const CAPACITOR_HTTP_INTERCEPTOR_URL_PARAM = 'u';

// Requests with these methods stay in the WebView: external URLs are only rewritten to go through the
// native proxy. Every other method is sent with the CapacitorHttp plugin.
const WEB_METHODS = ['GET', 'HEAD', 'OPTIONS', 'TRACE'];
const isWebMethod = (method: string | undefined): boolean => !method || WEB_METHODS.includes(method.toUpperCase());

// Statuses a Response must have a null body for (Fetch standard "null body status").
const NULL_BODY_STATUSES = [101, 103, 204, 205, 304];

// Case-insensitive lookup in the plain header object the native side returns.
const getHeader = (headers: Record<string, string> | undefined, name: string): string | undefined => {
  if (!headers) return undefined;
  const lowerName = name.toLowerCase();
  const key = Object.keys(headers).find((k) => k.toLowerCase() === lowerName);
  return key === undefined ? undefined : headers[key];
};

const abortReason = (signal: AbortSignal): unknown =>
  signal.reason ?? new DOMException('The operation was aborted.', 'AbortError');

// The native request cannot be cancelled, but the caller's promise settles as soon as the signal aborts.
const settleOnAbort = <T>(promise: Promise<T>, signal: AbortSignal | null | undefined): Promise<T> => {
  if (!signal) return promise;
  if (signal.aborted) return Promise.reject(abortReason(signal));
  return new Promise<T>((resolve, reject) => {
    const onAbort = () => reject(abortReason(signal));
    signal.addEventListener('abort', onAbort, { once: true });
    promise.then(
      (value) => {
        signal.removeEventListener('abort', onAbort);
        resolve(value);
      },
      (error) => {
        signal.removeEventListener('abort', onAbort);
        reject(error);
      },
    );
  });
};

export const isRelativeOrProxyUrl = (url: string | URL | undefined): boolean => {
  const href = url === undefined ? '' : String(url);
  return (
    !href || !(href.startsWith('http:') || href.startsWith('https:')) || href.indexOf(CAPACITOR_HTTP_INTERCEPTOR) > -1
  );
};

export const createProxyUrl = (url: string, win: WindowCapacitor): string => {
  if (isRelativeOrProxyUrl(url)) return url;
  const bridgeUrl = new URL(win.Capacitor?.getServerUrl() ?? '');
  bridgeUrl.pathname = CAPACITOR_HTTP_INTERCEPTOR;
  bridgeUrl.searchParams.append(CAPACITOR_HTTP_INTERCEPTOR_URL_PARAM, url);

  return bridgeUrl.toString();
};

/**
 * Replaces fetch and XMLHttpRequest so that requests to other origins go through the native
 * CapacitorHttp plugin, when it is enabled.
 */
export const patchHttp = (win: WindowCapacitor, cap: CapacitorInstance, platform: 'android' | 'ios'): void => {
  // patch fetch / XHR on Android/iOS
  // store original fetch & XHR functions
  win.CapacitorWebFetch = window.fetch;
  win.CapacitorWebXMLHttpRequest = {
    abort: window.XMLHttpRequest.prototype.abort,
    constructor: window.XMLHttpRequest.prototype.constructor,
    fullObject: window.XMLHttpRequest,
    getAllResponseHeaders: window.XMLHttpRequest.prototype.getAllResponseHeaders,
    getResponseHeader: window.XMLHttpRequest.prototype.getResponseHeader,
    open: window.XMLHttpRequest.prototype.open,
    prototype: window.XMLHttpRequest.prototype,
    send: window.XMLHttpRequest.prototype.send,
    setRequestHeader: window.XMLHttpRequest.prototype.setRequestHeader,
  };

  let doPatchHttp = false;

  // check if capacitor http is disabled before patching
  if (platform === 'ios') {
    // Use prompt to synchronously get capacitor http config.
    // https://stackoverflow.com/questions/29249132/wkwebview-complex-communication-between-javascript-native-code/49474323#49474323

    const payload = {
      type: 'CapacitorHttp',
    };

    const isHttpEnabled = prompt(JSON.stringify(payload));
    if (isHttpEnabled === 'true') {
      doPatchHttp = true;
    }
  } else if (typeof win.CapacitorHttpAndroidInterface !== 'undefined') {
    const isHttpEnabled = win.CapacitorHttpAndroidInterface.isEnabled();
    if (isHttpEnabled === true) {
      doPatchHttp = true;
    }
  }

  if (doPatchHttp) {
    // fetch patch
    window.fetch = async (resource: RequestInfo | URL, options?: RequestInit) => {
      // Work on copies: the caller's init and headers objects are never modified.
      const init: RequestInit = { ...options };
      const headers = new Headers(options?.headers);
      const contentType = headers.get('Content-Type');
      if (
        options?.body instanceof FormData &&
        contentType?.includes('multipart/form-data') &&
        !contentType.includes('boundary')
      ) {
        // let the Request add the multipart boundary itself
        headers.delete('Content-Type');
        init.headers = headers;
      }

      // Built once and reused below: building a Request from a Request consumes the body of the
      // original, so the original must not be fetched again.
      const request = new Request(resource, init);
      if (request.url.startsWith(`${cap.getServerUrl()}/`)) {
        return win.CapacitorWebFetch(request);
      }

      if (isWebMethod(request.method)) {
        const proxiedRequest = new Request(createProxyUrl(request.url, win), request);
        // a workaround for following android webview issue:
        // https://issues.chromium.org/issues/40450316
        // Sets the user-agent header to a custom value so that its not stripped
        // on its way to the native layer
        const userAgent = request.headers.get('User-Agent');
        if (platform === 'android' && userAgent !== null) {
          proxiedRequest.headers.set('x-cap-user-agent', userAgent);
        }
        return win.CapacitorWebFetch(proxiedRequest);
      }

      const tag = `CapacitorHttp fetch ${Date.now()} ${request.url}`;
      console.time(tag);

      try {
        if (request.signal?.aborted) {
          throw abortReason(request.signal);
        }

        const requestHeaders = Object.fromEntries(request.headers.entries());
        const {
          data: requestData,
          type,
          headers: bodyHeaders,
        } = await convertBody(
          options?.body ?? request.body ?? undefined,
          request.headers.get('Content-Type') ?? undefined,
        );

        const nativeHeaders: Record<string, string> = {
          ...bodyHeaders,
          ...requestHeaders,
        };

        const userAgent = request.headers.get('User-Agent');
        if (platform === 'android' && userAgent !== null) {
          nativeHeaders['User-Agent'] = userAgent;
        }

        const nativeResponse: HttpResponse = await settleOnAbort(
          cap.nativePromise('CapacitorHttp', 'request', {
            url: request.url,
            method: request.method,
            data: requestData,
            dataType: type,
            headers: nativeHeaders,
          }),
          request.signal,
        );

        const status = nativeResponse.status;
        if (!(status >= 200 && status <= 599)) {
          // A Response cannot represent it; report it like the network error it is.
          throw new TypeError(`CapacitorHttp returned an invalid HTTP status: ${status}`);
        }

        const responseHeaders = nativeResponse.headers ?? {};
        let data = getHeader(responseHeaders, 'Content-Type')?.startsWith('application/json')
          ? JSON.stringify(nativeResponse.data)
          : nativeResponse.data;
        if (data !== null && typeof data === 'object') {
          // parsed JSON under another content type
          data = JSON.stringify(data);
        }

        // intercept & parse response before returning
        const response = new Response(NULL_BODY_STATUSES.includes(status) ? null : data, {
          headers: responseHeaders,
          status,
        });

        /*
         * A Response built from native data has an empty `url`, so callers reading `response.url`
         * would see ''. `url` is an inherited getter on Response, hence `Object.defineProperty`.
         * see: https://stackoverflow.com/a/57382543
         * */
        Object.defineProperty(response, 'url', {
          value: nativeResponse.url,
        });

        return response;
      } finally {
        console.timeEnd(tag);
      }
    };

    // XHR patch
    //
    // The patched constructor is a subclass of the WebView's XMLHttpRequest, so `instanceof`, the
    // static constants and every request that is not intercepted keep the native behaviour, and the
    // shared XMLHttpRequest.prototype is left untouched. The state of an intercepted request lives
    // in a WeakMap, keyed by the instance.
    interface InterceptedXhrState {
      method: string;
      url: string;
      // true when the request is sent with CapacitorHttp instead of the WebView
      intercepted: boolean;
      aborted: boolean;
      requestHeaders: Record<string, string>;
      responseHeaders: Record<string, string>;
      readyState: number;
      status: number;
      response: any;
      responseText: string;
      responseURL: string;
    }

    const xhrStates = new WeakMap<object, InterceptedXhrState>();
    const WebXMLHttpRequest = win.CapacitorWebXMLHttpRequest.fullObject as { new (): any };

    const isProgressEventAvailable = () =>
      typeof ProgressEvent !== 'undefined' && ProgressEvent.prototype instanceof Event;

    class CapacitorXMLHttpRequest extends WebXMLHttpRequest {
      get readyState(): number {
        const state = xhrStates.get(this);
        return state?.intercepted ? state.readyState : super.readyState;
      }

      get status(): number {
        const state = xhrStates.get(this);
        return state?.intercepted ? state.status : super.status;
      }

      get response(): any {
        const state = xhrStates.get(this);
        return state?.intercepted ? state.response : super.response;
      }

      get responseText(): string {
        const state = xhrStates.get(this);
        return state?.intercepted ? state.responseText : super.responseText;
      }

      get responseURL(): string {
        const state = xhrStates.get(this);
        return state?.intercepted ? state.responseURL : super.responseURL;
      }

      open(method: string, url: string | URL, async = true, username?: string | null, password?: string | null) {
        const href = String(url);
        const state: InterceptedXhrState = {
          method: method.toUpperCase(),
          url: href,
          intercepted: false,
          aborted: false,
          requestHeaders: {},
          responseHeaders: {},
          readyState: 0,
          status: 0,
          response: '',
          responseText: '',
          responseURL: '',
        };
        xhrStates.set(this, state);

        // A synchronous request cannot wait for the bridge, so it keeps the WebView's implementation.
        if (!async || isRelativeOrProxyUrl(href)) {
          return super.open(method, url, async, username, password);
        }

        if (isWebMethod(state.method)) {
          state.url = createProxyUrl(href, win);
          return super.open(method, state.url, async, username, password);
        }

        state.intercepted = true;
        setTimeout(() => {
          this.dispatchEvent(new Event('loadstart'));
        });
        this.setInterceptedReadyState(state, 1);
      }

      setRequestHeader(header: string, value: string) {
        // a workaround for the following android web view issue:
        // https://issues.chromium.org/issues/40450316
        // Sets the user-agent header to a custom value so that its not stripped
        // on its way to the native layer
        if (platform === 'android' && header.toLowerCase() === 'user-agent') {
          header = 'x-cap-user-agent';
        }

        const state = xhrStates.get(this);
        if (!state?.intercepted) {
          return super.setRequestHeader(header, value);
        }
        // repeated headers are combined, as the WebView does
        const existing = state.requestHeaders[header];
        state.requestHeaders[header] = existing === undefined ? value : `${existing}, ${value}`;
      }

      abort() {
        const state = xhrStates.get(this);
        if (!state?.intercepted) {
          return super.abort();
        }
        state.aborted = true;
        this.setInterceptedReadyState(state, 0);
        setTimeout(() => {
          this.dispatchEvent(new Event('abort'));
          this.dispatchEvent(new Event('loadend'));
        });
      }

      send(body?: Document | XMLHttpRequestBodyInit | null) {
        const state = xhrStates.get(this);
        if (!state?.intercepted) {
          return super.send(body);
        }

        const tag = `CapacitorHttp XMLHttpRequest ${Date.now()} ${state.url}`;
        console.time(tag);
        this.setInterceptedReadyState(state, 2);

        // ignore a response that arrives after abort() or after the request was opened again
        const isCurrent = () => !state.aborted && xhrStates.get(this) === state;

        convertBody(body ?? undefined)
          .then(({ data, type, headers }) => {
            let otherHeaders = Object.keys(state.requestHeaders).length > 0 ? state.requestHeaders : undefined;

            if (body instanceof FormData && getHeader(state.requestHeaders, 'Content-Type') === undefined) {
              otherHeaders = {
                ...otherHeaders,
                'Content-Type': `multipart/form-data; boundary=----WebKitFormBoundary${Math.random().toString(36).substring(2, 15)}`,
              };
            }

            // intercept request & pass to the bridge
            return cap.nativePromise<unknown, HttpResponse>('CapacitorHttp', 'request', {
              url: state.url,
              method: state.method,
              data: data !== null ? data : undefined,
              headers: {
                ...headers,
                ...otherHeaders,
              },
              dataType: type,
            });
          })
          .then((nativeResponse: HttpResponse) => {
            if (!isCurrent()) return;

            const responseHeaders = nativeResponse.headers ?? {};
            const length = typeof nativeResponse.data === 'string' ? nativeResponse.data.length : 0;
            //TODO: Add progress event emission on native side
            if (isProgressEventAvailable()) {
              this.dispatchEvent(
                new ProgressEvent('progress', { lengthComputable: true, loaded: length, total: length }),
              );
            }
            state.responseHeaders = responseHeaders;
            state.status = nativeResponse.status;
            if (this.responseType === '' || this.responseType === 'text') {
              state.response =
                typeof nativeResponse.data !== 'string' ? JSON.stringify(nativeResponse.data) : nativeResponse.data;
            } else {
              state.response = nativeResponse.data;
            }
            state.responseText = getHeader(responseHeaders, 'Content-Type')?.startsWith('application/json')
              ? JSON.stringify(nativeResponse.data)
              : nativeResponse.data;
            state.responseURL = nativeResponse.url;
            this.setInterceptedReadyState(state, 4);
            setTimeout(() => {
              this.dispatchEvent(new Event('load'));
              this.dispatchEvent(new Event('loadend'));
            });
          })
          .catch(() => {
            if (!isCurrent()) return;

            // a network error: status 0 and no response, as the WebView reports it
            state.status = 0;
            state.responseHeaders = {};
            state.response = this.responseType === '' || this.responseType === 'text' ? '' : null;
            state.responseText = '';
            state.responseURL = '';
            this.setInterceptedReadyState(state, 4);
            if (isProgressEventAvailable()) {
              this.dispatchEvent(new ProgressEvent('progress', { lengthComputable: false, loaded: 0, total: 0 }));
            }
            setTimeout(() => {
              this.dispatchEvent(new Event('error'));
              this.dispatchEvent(new Event('loadend'));
            });
          })
          .finally(() => console.timeEnd(tag));
      }

      getAllResponseHeaders() {
        const state = xhrStates.get(this);
        if (!state?.intercepted) {
          return super.getAllResponseHeaders();
        }
        // lower-cased and sorted, without Set-Cookie, as the WebView returns them
        return Object.keys(state.responseHeaders)
          .filter((key) => !['set-cookie', 'set-cookie2'].includes(key.toLowerCase()))
          .map((key) => [key.toLowerCase(), state.responseHeaders[key]])
          .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
          .map(([key, value]) => `${key}: ${value}\r\n`)
          .join('');
      }

      getResponseHeader(name: string) {
        const state = xhrStates.get(this);
        if (!state?.intercepted) {
          return super.getResponseHeader(name);
        }
        return getHeader(state.responseHeaders, name) ?? null;
      }

      private setInterceptedReadyState(state: InterceptedXhrState, readyState: number) {
        state.readyState = readyState;
        setTimeout(() => {
          this.dispatchEvent(new Event('readystatechange'));
        });
      }
    }

    window.XMLHttpRequest = CapacitorXMLHttpRequest as unknown as typeof XMLHttpRequest;
  }
};
