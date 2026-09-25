import c from '../colors';
import { logger } from '../log';

/**
 * This fork collects no usage data. The command is kept so that scripts which run
 * `cap telemetry off` keep working; it only reports that there is nothing to turn on or off.
 */
export async function telemetryCommand(onOrOff?: string): Promise<void> {
  const ignored = onOrOff ? ` ${c.input(`telemetry ${onOrOff}`)} has no effect.` : '';
  logger.info(`This fork of Capacitor collects no telemetry.${ignored}`);
}
