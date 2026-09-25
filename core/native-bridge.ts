/**
 * Note: When making changes to this file, run `npm run build:nativebridge`
 * afterwards to build the nativebridge.js files to the android and iOS projects.
 */
import type { HttpResponse } from './src/core-plugins';
import type {
  CallData,
  CapacitorInstance,
  ErrorCallData,
  MessageCallData,
  PluginResult,
  StoredCallback,
  WindowCapacitor,
  CapFormDataEntry,
} from './src/definitions-internal';
import { CapacitorException, ExceptionCode, getPlatformId } from './src/util';

// For removing exports for iOS/Android, keep let for reassignment
// eslint-disable-next-line
let dummy = {};

// Base64 of a Blob (File included). Blob.arrayBuffer replaces FileReader.readAsBinaryString, which
// is deprecated. The bytes are turned into a binary string in chunks so large bodies do not exceed
// the argument limit of String.fromCharCode.
const readBlobAsBase64 = async (blob: Blob): Promise<string> => {
  const bytes = new Uint8Array(await blob.arrayBuffer());
  let binary = '';
  for (let offset = 0; offset < bytes.length; offset += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + 0x8000));
  }
  return btoa(binary);
};

const convertFormData = async (formData: FormData): Promise<any> => {
  const newFormData: CapFormDataEntry[] = [];
  for (const pair of formData.entries()) {
    const [key, value] = pair;
    if (value instanceof File) {
      const base64File = await readBlobAsBase64(value);
      newFormData.push({
        key,
        value: base64File,
        type: 'base64File',
        contentType: value.type,
        fileName: value.name,
      });
    } else {
      newFormData.push({ key, value, type: 'string' });
    }
  }

  return newFormData;
};

const convertBody = async (
  body: Document | XMLHttpRequestBodyInit | ReadableStream<any> | undefined,
  contentType?: string,
): Promise<any> => {
  // Other binary views and plain buffers take the same path as a Uint8Array instead of being sent as JSON.
  if (body instanceof ArrayBuffer) {
    body = new Uint8Array(body);
  } else if (ArrayBuffer.isView(body) && !(body instanceof Uint8Array)) {
    body = new Uint8Array(body.buffer, body.byteOffset, body.byteLength);
  }

  if ((typeof ReadableStream !== 'undefined' && body instanceof ReadableStream) || body instanceof Uint8Array) {
    let encodedData;
    if (body instanceof ReadableStream) {
      const reader = body.getReader();
      const chunks: any[] = [];
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        chunks.push(value);
      }
      const concatenated = new Uint8Array(chunks.reduce((acc, chunk) => acc + chunk.length, 0));
      let position = 0;
      for (const chunk of chunks) {
        concatenated.set(chunk, position);
        position += chunk.length;
      }
      encodedData = concatenated;
    } else {
      encodedData = body;
    }

    let data = new TextDecoder().decode(encodedData);
    let type;
    if (contentType === 'application/json') {
      try {
        data = JSON.parse(data);
      } catch (ignored) {
        // ignore
      }
      type = 'json';
    } else if (contentType === 'multipart/form-data') {
      type = 'formData';
    } else if (contentType?.startsWith('image')) {
      type = 'image';
    } else if (contentType === 'application/octet-stream') {
      type = 'binary';
    } else {
      type = 'text';
    }

    return {
      data,
      type,
      headers: { 'Content-Type': contentType || 'application/octet-stream' },
    };
  } else if (body instanceof URLSearchParams) {
    return {
      data: body.toString(),
      type: 'text',
    };
  } else if (body instanceof FormData) {
    return {
      data: await convertFormData(body),
      type: 'formData',
    };
  } else if (typeof Blob !== 'undefined' && body instanceof Blob) {
    // A File, or any other Blob: sent base64 encoded so binary content survives the bridge.
    return {
      data: await readBlobAsBase64(body),
      type: 'file',
      headers: { 'Content-Type': body.type || contentType || 'application/octet-stream' },
    };
  }

  return { data: body, type: 'json' };
};

const CAPACITOR_HTTP_INTERCEPTOR = '/_capacitor_http_interceptor_';
const CAPACITOR_HTTP_INTERCEPTOR_URL_PARAM = 'u';

// The id a call carries when it expects no response. Mirrors PluginCall.CALLBACK_ID_DANGLING on Android.
const CALLBACK_ID_DANGLING = '-1';

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

// TODO: export as Cap function
const isRelativeOrProxyUrl = (url: string | URL | undefined): boolean => {
  const href = url === undefined ? '' : String(url);
  return (
    !href || !(href.startsWith('http:') || href.startsWith('https:')) || href.indexOf(CAPACITOR_HTTP_INTERCEPTOR) > -1
  );
};

// TODO: export as Cap function
const createProxyUrl = (url: string, win: WindowCapacitor): string => {
  if (isRelativeOrProxyUrl(url)) return url;
  const bridgeUrl = new URL(win.Capacitor?.getServerUrl() ?? '');
  bridgeUrl.pathname = CAPACITOR_HTTP_INTERCEPTOR;
  bridgeUrl.searchParams.append(CAPACITOR_HTTP_INTERCEPTOR_URL_PARAM, url);

  return bridgeUrl.toString();
};

const initBridge = (w: any): void => {
  const convertFileSrcServerUrl = (webviewServerUrl: string, filePath: string): string => {
    if (typeof filePath === 'string') {
      if (filePath.startsWith('/')) {
        return webviewServerUrl + '/_capacitor_file_' + filePath;
      } else if (filePath.startsWith('file://')) {
        return webviewServerUrl + filePath.replace('file://', '/_capacitor_file_');
      } else if (filePath.startsWith('content://')) {
        return webviewServerUrl + filePath.replace('content:/', '/_capacitor_content_');
      }
    }
    return filePath;
  };

  const initEvents = (win: WindowCapacitor, cap: CapacitorInstance) => {
    cap.addListener = (pluginName, eventName, callback) => {
      const callbackId = cap.nativeCallback(
        pluginName,
        'addListener',
        {
          eventName: eventName,
        },
        callback,
      );
      return {
        remove: async () => {
          win?.console?.debug('Removing listener', pluginName, eventName);
          cap.removeListener(pluginName, callbackId, eventName, callback);
        },
      };
    };

    cap.removeListener = (pluginName, callbackId, eventName, callback) => {
      cap.nativeCallback(
        pluginName,
        'removeListener',
        {
          callbackId: callbackId,
          eventName: eventName,
        },
        callback,
      );
    };

    cap.createEvent = (eventName, eventData) => {
      const doc = win.document;
      if (doc) {
        const ev = doc.createEvent('Events');
        ev.initEvent(eventName, false, false);
        if (eventData && typeof eventData === 'object') {
          for (const i in eventData) {
            // eslint-disable-next-line no-prototype-builtins
            if (eventData.hasOwnProperty(i)) {
              ev[i] = eventData[i];
            }
          }
        }
        return ev;
      }
      return null;
    };

    cap.triggerEvent = (eventName, target, eventData) => {
      const doc = win.document;
      eventData = eventData || {};
      const ev = cap.createEvent(eventName, eventData);

      if (ev) {
        if (target === 'document') {
          if (doc?.dispatchEvent) {
            return doc.dispatchEvent(ev);
          }
        } else if (target === 'window' && win.dispatchEvent) {
          return win.dispatchEvent(ev);
        } else if (doc?.querySelector) {
          const targetEl = doc.querySelector(target);
          if (targetEl) {
            return targetEl.dispatchEvent(ev);
          }
        }
      }
      return false;
    };

    win.Capacitor = cap;
  };

  // Cordova is not supported (see BREAKING.md) and window.cordova is no longer defined, but these
  // three shims are kept on purpose: navigator.app.exitApp(), the synthetic `deviceready` document
  // event and the `backbutton` interception. Ionic's hardware back button relies on the last two.
  const initLegacyHandlers = (win: WindowCapacitor, cap: CapacitorInstance) => {
    const doc = win.document;
    const nav = win.navigator;

    if (nav) {
      nav.app = nav.app || {};
      nav.app.exitApp = () => {
        if (!cap.Plugins?.App) {
          win.console.warn('App plugin not installed');
        } else {
          cap.nativeCallback('App', 'exitApp', {});
        }
      };
    }

    if (doc) {
      const docAddEventListener = doc.addEventListener;
      doc.addEventListener = (...args: any[]) => {
        const eventName = args[0];
        const handler = args[1];
        if (eventName === 'deviceready' && handler) {
          Promise.resolve().then(handler);
        } else if (eventName === 'backbutton' && cap.Plugins.App) {
          // Add a dummy listener so Capacitor doesn't do the default
          // back button action
          cap.Plugins.App.addListener('backButton', () => {
            // ignore
          });
        }
        return docAddEventListener.apply(doc, args);
      };
    }

    win.Capacitor = cap;
  };

  const initVendor = (win: WindowCapacitor, cap: CapacitorInstance) => {
    const Ionic = (win.Ionic = win.Ionic || {});
    const IonicWebView = (Ionic.WebView = Ionic.WebView || {});
    const Plugins = cap.Plugins;

    IonicWebView.getServerBasePath = (callback: (path: string) => void) => {
      Plugins?.WebView?.getServerBasePath().then((result: any) => {
        callback(result.path);
      });
    };

    IonicWebView.setServerAssetPath = (path: any) => {
      Plugins?.WebView?.setServerAssetPath({ path });
    };

    IonicWebView.setServerBasePath = (path: any) => {
      Plugins?.WebView?.setServerBasePath({ path });
    };

    IonicWebView.persistServerBasePath = () => {
      Plugins?.WebView?.persistServerBasePath();
    };

    IonicWebView.convertFileSrc = (url: string) => cap.convertFileSrc(url);

    win.Capacitor = cap;
    win.Ionic.WebView = IonicWebView;
  };

  const initLogger = (win: WindowCapacitor, cap: CapacitorInstance) => {
    const BRIDGED_CONSOLE_METHODS: (keyof Console)[] = ['debug', 'error', 'info', 'log', 'trace', 'warn'];

    const createLogFromNative = (c: Partial<Console>) => (result: PluginResult) => {
      if (isFullConsole(c)) {
        const success = result.success === true;

        const tagStyles = success
          ? 'font-style: italic; font-weight: lighter; color: gray'
          : 'font-style: italic; font-weight: lighter; color: red';
        c.groupCollapsed(
          '%cresult %c' + result.pluginId + '.' + result.methodName + ' (#' + result.callbackId + ')',
          tagStyles,
          'font-style: italic; font-weight: bold; color: #444',
        );
        if (result.success === false) {
          c.error(result.error);
        } else {
          c.dir(JSON.stringify(result.data));
        }
        c.groupEnd();
      } else {
        if (result.success === false) {
          c.error('LOG FROM NATIVE', result.error);
        } else {
          c.log('LOG FROM NATIVE', result.data);
        }
      }
    };

    const createLogToNative = (c: Partial<Console>) => (call: MessageCallData) => {
      if (isFullConsole(c)) {
        c.groupCollapsed(
          '%cnative %c' + call.pluginId + '.' + call.methodName + ' (#' + call.callbackId + ')',
          'font-weight: lighter; color: gray',
          'font-weight: bold; color: #000',
        );
        c.dir(call);
        c.groupEnd();
      } else {
        c.log('LOG TO NATIVE: ', call);
      }
    };

    const isFullConsole = (c: Partial<Console>): c is Console => {
      if (!c) {
        return false;
      }

      return typeof c.groupCollapsed === 'function' || typeof c.groupEnd === 'function' || typeof c.dir === 'function';
    };

    const serializeConsoleMessage = (msg: unknown): string => {
      if (msg instanceof Error) {
        // JSON.stringify(error) would give "{}"
        return `${msg.name}: ${msg.message}${msg.stack ? `\n${msg.stack}` : ''}`;
      }
      if (typeof msg === 'object' && msg !== null) {
        // an object seen twice (a cycle, or a shared reference) is written once
        const seen = new WeakSet<object>();
        try {
          const json = JSON.stringify(msg, (_key, value) => {
            if (value instanceof Error) {
              return `${value.name}: ${value.message}`;
            }
            if (typeof value === 'object' && value !== null) {
              if (seen.has(value)) {
                return '[Circular]';
              }
              seen.add(value);
            }
            return value;
          });
          return json ?? String(msg);
        } catch (e) {
          return String(msg);
        }
      }
      return String(msg);
    };

    const platform = getPlatformId(win);

    if (platform == 'android' || platform == 'ios') {
      // patch document.cookie on Android/iOS
      win.CapacitorCookiesDescriptor =
        Object.getOwnPropertyDescriptor(Document.prototype, 'cookie') ||
        Object.getOwnPropertyDescriptor(HTMLDocument.prototype, 'cookie');

      let doPatchCookies = false;

      // check if capacitor cookies is disabled before patching
      if (platform === 'ios') {
        // Use prompt to synchronously get capacitor cookies config.
        // https://stackoverflow.com/questions/29249132/wkwebview-complex-communication-between-javascript-native-code/49474323#49474323

        const payload = {
          type: 'CapacitorCookies.isEnabled',
        };

        const isCookiesEnabled = prompt(JSON.stringify(payload));
        if (isCookiesEnabled === 'true') {
          doPatchCookies = true;
        }
      } else if (typeof win.CapacitorCookiesAndroidInterface !== 'undefined') {
        const isCookiesEnabled = win.CapacitorCookiesAndroidInterface.isEnabled();
        if (isCookiesEnabled === true) {
          doPatchCookies = true;
        }
      }

      if (doPatchCookies) {
        Object.defineProperty(document, 'cookie', {
          get: function () {
            if (platform === 'ios') {
              // Use prompt to synchronously get cookies.
              // https://stackoverflow.com/questions/29249132/wkwebview-complex-communication-between-javascript-native-code/49474323#49474323

              const payload = {
                type: 'CapacitorCookies.get',
              };

              const res = prompt(JSON.stringify(payload));
              return res;
            } else if (typeof win.CapacitorCookiesAndroidInterface !== 'undefined') {
              // return original document.cookie since Android does not support filtering of `httpOnly` cookies
              return win.CapacitorCookiesDescriptor?.get?.call(document) ?? '';
            }
          },
          set: function (val) {
            const cookiePairs = val.split(';');
            const domainSection = val.toLowerCase().split('domain=')[1];
            const domain =
              cookiePairs.length > 1 && domainSection != null && domainSection.length > 0
                ? domainSection.split(';')[0].trim()
                : '';

            if (platform === 'ios') {
              // Use prompt to synchronously set cookies.
              // https://stackoverflow.com/questions/29249132/wkwebview-complex-communication-between-javascript-native-code/49474323#49474323

              const payload = {
                type: 'CapacitorCookies.set',
                action: val,
                domain,
              };

              prompt(JSON.stringify(payload));
            } else if (typeof win.CapacitorCookiesAndroidInterface !== 'undefined') {
              win.CapacitorCookiesAndroidInterface.setCookie(domain, val);
            }
          },
        });
      }

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
                return cap.nativePromise('CapacitorHttp', 'request', {
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
    }

    // patch window.console on iOS and store original console fns
    const isIos = getPlatformId(win) === 'ios';
    if (win.console && isIos) {
      // Set while a message is on its way to native: anything the bridge logs itself (for example an
      // error from toNative) then goes to the original console only instead of recursing.
      let forwarding = false;
      Object.defineProperties(
        win.console,
        BRIDGED_CONSOLE_METHODS.reduce((props: PropertyDescriptorMap, method) => {
          const consoleMethod = win.console[method].bind(win.console);
          props[method] = {
            configurable: true,
            enumerable: true,
            writable: true,
            value: (...args: unknown[]) => {
              if (!forwarding) {
                forwarding = true;
                try {
                  cap.toNative('Console', 'log', {
                    level: method,
                    message: args.map(serializeConsoleMessage).join(' '),
                  });
                } finally {
                  forwarding = false;
                }
              }
              return consoleMethod(...args);
            },
          };
          return props;
        }, {}),
      );
    }

    cap.logJs = (msg, level) => {
      switch (level) {
        case 'error':
          win.console.error(msg);
          break;
        case 'warn':
          win.console.warn(msg);
          break;
        case 'info':
          win.console.info(msg);
          break;
        default:
          win.console.log(msg);
      }
    };

    cap.logToNative = createLogToNative(win.console);
    cap.logFromNative = createLogFromNative(win.console);

    cap.handleError = (err) => win.console.error(err);

    win.Capacitor = cap;
  };

  function initNativeBridge(win: WindowCapacitor) {
    const cap = win.Capacitor || ({} as CapacitorInstance);

    // keep a collection of callbacks for native response data, with the call each one belongs to
    const callbacks = new Map<string, StoredCallback & { pluginId: string; methodName: string }>();

    const webviewServerUrl = typeof win.WEBVIEW_SERVER_URL === 'string' ? win.WEBVIEW_SERVER_URL : '';
    cap.getServerUrl = () => webviewServerUrl;
    cap.convertFileSrc = (filePath) => convertFileSrcServerUrl(webviewServerUrl, filePath);

    // Callback ids are random UUIDs (v4) so that a pending id cannot be guessed from an earlier one,
    // and a call that comes back from an old session after a reload never matches a new id.
    // crypto.getRandomValues works on every page, including one served over plain http such as a live
    // reload server. crypto.randomUUID would be shorter but is undefined outside secure contexts
    // (https and localhost), so it is not used.
    const createCallbackId = (): string => {
      const bytes = win.crypto.getRandomValues(new Uint8Array(16));
      bytes[6] = (bytes[6] & 0x0f) | 0x40;
      bytes[8] = (bytes[8] & 0x3f) | 0x80;
      const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
      return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
    };

    let postToNative: ((data: CallData) => void) | null = null;

    const isNativePlatform = () => true;
    const getPlatform = () => getPlatformId(win);

    cap.getPlatform = getPlatform;
    cap.isPluginAvailable = (name) => Object.prototype.hasOwnProperty.call(cap.Plugins, name);
    cap.isNativePlatform = isNativePlatform;

    // create the postToNative() fn if needed
    if (getPlatformId(win) === 'android') {
      // android platform
      postToNative = (data) => {
        win.androidBridge.postMessage(JSON.stringify(data));
      };
    } else if (getPlatformId(win) === 'ios') {
      // ios platform
      postToNative = (data) => {
        data.type = data.type ? data.type : 'message';
        win.webkit.messageHandlers.bridge.postMessage(data);
      };
    }

    cap.handleWindowError = (msg, url, lineNo, columnNo, err) => {
      // window.onerror receives an Event instead of a message for errors such as a failed resource load
      const message = typeof msg === 'string' ? msg : (err?.message ?? String(msg?.type ?? msg));

      if (message.toLowerCase().indexOf('script error') > -1) {
        // A cross-origin script error carries no details
      } else {
        let errorObject: string;
        try {
          errorObject =
            err instanceof Error
              ? JSON.stringify({ name: err.name, message: err.message, stack: err.stack })
              : (JSON.stringify(err) ?? '');
        } catch (e) {
          errorObject = String(err);
        }

        const errObj: ErrorCallData = {
          type: 'js.error',
          error: {
            message,
            url: url,
            line: lineNo,
            col: columnNo,
            errorObject,
          },
        };

        if (err != null) {
          cap.handleError(err);
        }

        try {
          postToNative?.(errObj);
        } catch (e) {
          win?.console?.error(e);
        }
      }

      return false;
    };

    if (cap.DEBUG) {
      window.onerror = cap.handleWindowError;
    }

    initLogger(win, cap);

    /**
     * Send a plugin method call to the native layer
     */
    cap.toNative = (pluginName, methodName, options, storedCallback) => {
      let callbackId = CALLBACK_ID_DANGLING;
      try {
        if (typeof postToNative === 'function') {
          if (methodName === 'removeListener' && typeof options?.callbackId === 'string') {
            // Native releases its side of the listener and never answers removeListener, so the
            // listener's callback is released here, and the call itself gets no callback to keep.
            callbacks.delete(options.callbackId);
            storedCallback = undefined;
          } else if (methodName === 'removeAllListeners') {
            // native drops every listener of the plugin; release their callbacks too
            for (const [id, stored] of callbacks) {
              if (stored.pluginId === pluginName && stored.methodName === 'addListener') {
                callbacks.delete(id);
              }
            }
          }

          if (
            storedCallback &&
            (typeof storedCallback.callback === 'function' || typeof storedCallback.resolve === 'function')
          ) {
            // store the call for later lookup
            callbackId = createCallbackId();
            callbacks.set(callbackId, { ...storedCallback, pluginId: pluginName, methodName });
          }

          const callData = {
            callbackId: callbackId,
            pluginId: pluginName,
            methodName: methodName,
            options: options || {},
          };

          if (cap.isLoggingEnabled && pluginName !== 'Console') {
            cap.logToNative(callData);
          }

          // post the call data to native
          postToNative(callData);

          return callbackId;
        } else {
          throw new CapacitorException(`implementation unavailable for: ${pluginName}`, ExceptionCode.Unavailable);
        }
      } catch (e) {
        callbacks.delete(callbackId);
        const error = e instanceof Error ? e : new Error(String(e));
        win?.console?.error(error);
        if (methodName === 'addListener') {
          // The callback of addListener is the event listener, which expects events, not this
          // error: let the addListener call reject instead.
          throw error;
        }
        if (typeof storedCallback?.callback === 'function') {
          storedCallback.callback(null, error);
        } else {
          storedCallback?.reject?.(error);
        }
      }

      return null;
    };

    if (win?.androidBridge) {
      win.androidBridge.onmessage = function (event) {
        returnResult(JSON.parse(event.data));
      };
    }

    /**
     * Process a response from the native layer.
     */
    cap.fromNative = (result) => {
      returnResult(result);
    };

    const returnResult = (result: any) => {
      if (cap.isLoggingEnabled && result.pluginId !== 'Console') {
        cap.logFromNative(result);
      }

      // get the stored call, if it exists
      try {
        const storedCall = callbacks.get(result.callbackId);

        if (storedCall) {
          // looks like we've got a stored call

          if (result.error) {
            // ensure stacktraces by copying error properties to an Error
            result.error = Object.keys(result.error).reduce((err, key) => {
              // use any type to avoid importing util and compiling most of .ts files
              (err as any)[key] = (result as any).error[key];
              return err;
            }, new cap.Exception(''));
          }

          if (typeof storedCall.callback === 'function') {
            // callback
            if (result.success) {
              storedCall.callback(result.data);
            } else {
              storedCall.callback(null, result.error);
            }
          } else if (typeof storedCall.resolve === 'function') {
            // promise
            if (result.success) {
              storedCall.resolve(result.data);
            } else {
              storedCall.reject(result.error);
            }

            // no need to keep this stored callback
            // around for a one time resolve promise
            callbacks.delete(result.callbackId);
          }
        } else if (!result.success && result.error) {
          // no stored callback, but if there was an error let's log it
          win?.console?.warn(result.error);
        }

        if (result.save === false) {
          callbacks.delete(result.callbackId);
        }
      } catch (e) {
        win?.console?.error(e);
      }

      // always delete to prevent memory leaks
      // overkill but we're not sure what apps will do with this data
      delete result.data;
      delete result.error;
    };

    cap.nativeCallback = (pluginName, methodName, options, callback) =>
      cap.toNative(pluginName, methodName, options, { callback });

    cap.nativePromise = (pluginName, methodName, options) => {
      return new Promise((resolve, reject) => {
        cap.toNative(pluginName, methodName, options, {
          resolve: resolve,
          reject: reject,
        });
      });
    };

    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    cap.withPlugin = (_pluginId, _fn) => dummy;

    cap.Exception = CapacitorException;

    initEvents(win, cap);
    initLegacyHandlers(win, cap);
    initVendor(win, cap);

    win.Capacitor = cap;
  }

  initNativeBridge(w);
};

initBridge(
  typeof globalThis !== 'undefined'
    ? (globalThis as WindowCapacitor)
    : typeof self !== 'undefined'
      ? (self as WindowCapacitor)
      : typeof window !== 'undefined'
        ? (window as WindowCapacitor)
        : typeof global !== 'undefined'
          ? (global as WindowCapacitor)
          : ({} as WindowCapacitor),
);

// Export only for tests
export { initBridge };
