/**
 * Boots the Viite UI application once the document is ready.
 * Delegates startup to the main application entrypoint.
 */
import { start } from './application.js';

$(function () {
  start(undefined, undefined);
});
