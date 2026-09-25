import { registerPlugin } from '../global';
import { WebPlugin } from '../web-plugin';

/**
 * Available status bar styles.
 */
export enum SystemBarsStyle {
  /**
   * Light system bar content on a dark background.
   *
   * @since 8.0.0
   */
  Dark = 'DARK',

  /**
   * For dark system bar content on a light background.
   *
   * @since 8.0.0
   */
  Light = 'LIGHT',

  /**
   * The style is based on the device appearance or the underlying content.
   * If the device is using Dark mode, the system bars content will be light.
   * If the device is using Light mode, the system bars content will be dark.
   *
   * @since 8.0.0
   */
  Default = 'DEFAULT',
}

/**
 * Available status bar animations.  iOS only.
 */
export type SystemBarsAnimation = 'FADE' | 'NONE';

/**
 * Available system bar types.
 */
export enum SystemBarType {
  /**
   * The top status bar on both Android and iOS.
   *
   * @since 8.0.0
   */
  StatusBar = 'StatusBar',
  /**
   * The navigation bar (or gesture bar on iOS) on both Android and iOS.
   *
   * @since 8.0.0
   */
  NavigationBar = 'NavigationBar',
}

export interface SystemBarsStyleOptions {
  /**
   * Style of the text and icons of the system bars.
   *
   * @since 8.0.0
   * @default 'DEFAULT'
   * @example "DARK"
   */
  style: SystemBarsStyle;

  /**
   * The system bar to which to apply the style.
   *
   *
   * @since 8.0.0
   * @default null
   * @example SystemBarType.StatusBar
   */
  bar?: SystemBarType;
}

export interface SystemBarsVisibilityOptions {
  /**
   * The system bar to hide or show.
   *
   * @since 8.0.0
   * @default null
   * @example SystemBarType.StatusBar
   */
  bar?: SystemBarType;

  /**
   * The type of status bar animation used when showing or hiding.
   *
   * This option is only supported on iOS.
   *
   * @default 'FADE'
   *
   * @since 8.0.0
   */
  animation?: SystemBarsAnimation;
}

export interface SystemBarsAnimationOptions {
  /**
   * The type of status bar animation used when showing or hiding.
   *
   * This option is only supported on iOS.
   *
   * @default 'FADE'
   *
   * @since 8.0.0
   */
  animation: SystemBarsAnimation;
}

export interface SystemBarsPlugin {
  /**
   * Set the current style of the system bars.
   *
   * @since 8.0.0
   */
  setStyle(options: SystemBarsStyleOptions): Promise<void>;

  /**
   * Show the system bars.
   *
   * @since 8.0.0
   */
  show(options?: SystemBarsVisibilityOptions): Promise<void>;

  /**
   * Hide the system bars.
   *
   * @since 8.0.0
   */
  hide(options?: SystemBarsVisibilityOptions): Promise<void>;

  /**
   * Set the animation to use when showing / hiding the status bar.
   *
   * Only available on iOS.
   *
   * @since 8.0.0
   */
  setAnimation(options: SystemBarsAnimationOptions): Promise<void>;
}

/**
 * A browser has no system bars to style, show or hide, so every method resolves without doing
 * anything. Apps can call SystemBars unconditionally, including while developing in a browser.
 */
export class SystemBarsPluginWeb extends WebPlugin implements SystemBarsPlugin {
  async setStyle(): Promise<void> {
    return;
  }

  async setAnimation(): Promise<void> {
    return;
  }

  async show(): Promise<void> {
    return;
  }

  async hide(): Promise<void> {
    return;
  }
}

export const SystemBars = registerPlugin<SystemBarsPlugin>('SystemBars', {
  web: () => new SystemBarsPluginWeb(),
});
