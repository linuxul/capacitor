import type { CapacitorGlobal, PluginCallback, PluginResultData, PluginResultError } from './definitions';

export interface PluginHeaderMethod {
  readonly name: string;
  readonly rtype?: 'promise' | 'callback';
}

export interface PluginHeader {
  readonly name: string;
  readonly methods: readonly PluginHeaderMethod[];
}

/**
 * Has all instance properties that are available and used
 * by the native layer. The "Capacitor" interface it extends
 * is the public one.
 */
export interface CapacitorInstance extends CapacitorGlobal {
  /**
   * Internal registry for all plugins assigned to the Capacitor global.
   * Legacy Capacitor referenced this property directly, but as of v3
   * it should be an internal API. Still exporting on the Capacitor
   * type, but with the deprecated JSDoc tag.
   */
  Plugins: {
    [pluginName: string]: {
      [prop: string]: any;
    };
  };

  PluginHeaders?: readonly PluginHeader[];

  /**
   * Gets the WebView server urls set by the native web view. Defaults
   * to "" if not running from a native platform.
   */
  getServerUrl: () => string;

  /**
   * Low-level API to send data to the native layer.
   * Prefer using `nativeCallback()` or `nativePromise()` instead.
   * Returns the Callback Id, or `null` if the call could not be posted -
   * either because there is no native implementation on this platform or
   * because posting threw.
   */
  toNative?: (pluginName: string, methodName: string, options: any, storedCallback?: StoredCallback) => string | null;

  /**
   * Sends data over the bridge to the native layer.
   * Returns the Callback Id.
   */
  nativeCallback: <O>(pluginName: string, methodName: string, options?: O, callback?: PluginCallback) => string | null;

  /**
   * Sends data over the bridge to the native layer and
   * resolves the promise when it receives the data from
   * the native implementation.
   */
  nativePromise: <O, R>(pluginName: string, methodName: string, options?: O) => Promise<R>;

  /**
   * Low-level API used by the native layers to send
   * data back to the webview runtime.
   */
  fromNative?: (result: PluginResult) => void;

  /**
   * Low-level API for backwards compatibility.
   * Returns `null` when there is no `document` to create the event on.
   */
  createEvent?: (eventName: string, eventData?: any) => Event | null;

  /**
   * Low-level API triggered from native implementations.
   */
  triggerEvent?: (eventName: string, target: string, eventData?: any) => boolean;

  handleError: (err: Error) => void;

  /**
   * Installed as `window.onerror` when `DEBUG` is set. The return value is
   * that handler's "suppress default handling" flag; it is always `false`.
   */
  handleWindowError: (msg: string | Event, url?: string, lineNo?: number, columnNo?: number, err?: Error) => boolean;

  /**
   * Low-level API used by the native bridge to log messages.
   */
  logJs: (message: string, level: 'error' | 'warn' | 'info' | 'log') => void;

  logToNative: (data: MessageCallData) => void;

  logFromNative: (results: PluginResult) => void;

  /**
   * Low-level API used by the native bridge.
   */
  withPlugin?: (pluginName: string, fn: (...args: any[]) => any) => void;
}

export interface MessageCallData {
  type?: 'message';
  callbackId: string;
  pluginId: string;
  methodName: string;
  options: any;
}

export interface ErrorCallData {
  type: 'js.error';
  error: {
    message: string;
    url?: string;
    line?: number;
    col?: number;
    errorObject: string;
  };
}

export type CallData = MessageCallData | ErrorCallData;

/**
 * A resulting call back from the native layer.
 */
export interface PluginResult {
  callbackId?: string;
  methodName?: string;
  data: PluginResultData;
  success: boolean;
  error?: PluginResultError;
  pluginId?: string;
  save?: boolean;
}

/**
 * Callback data kept on the client
 * to be called after native response
 */
export interface StoredCallback {
  callback?: PluginCallback;
  resolve?: (...args: any[]) => any;
  reject?: (...args: any[]) => any;
}

export interface CapacitorCustomPlatformInstance {
  name: string;
  plugins: { [pluginName: string]: any };
}

export interface WindowCapacitor {
  Capacitor?: CapacitorInstance;
  CapacitorCookiesAndroidInterface?: any;
  CapacitorCookiesDescriptor?: PropertyDescriptor;
  CapacitorHttpAndroidInterface?: any;
  CapacitorWebFetch?: any;
  CapacitorWebXMLHttpRequest?: any;
  CapacitorCustomPlatform?: CapacitorCustomPlatformInstance;
  Ionic?: {
    WebView?: {
      getServerBasePath?: any;
      setServerBasePath?: any;
      setServerAssetPath?: any;
      persistServerBasePath?: any;
      convertFileSrc?: any;
    };
  };
  WEBVIEW_SERVER_URL?: string;
  androidBridge?: {
    postMessage(data: string): void;
    onmessage?: (event: { data: string }) => void;
  };
  webkit?: {
    messageHandlers?: {
      bridge: {
        postMessage(data: any): void;
      };
    };
  };
  console?: Console;
  crypto?: Crypto;
  dispatchEvent?: any;
  document?: any;
  navigator?: {
    app?: {
      exitApp?: () => void;
    };
  };
}

export interface CapFormDataEntry {
  key: string;
  value: string;
  type: 'base64File' | 'string';
  contentType?: string;
  fileName?: string;
}
