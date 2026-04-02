/**
 * FormValidation - Validates user management form fields and provides utility functions.
 */
import { Toast } from '@components/Toast.js';

const COORD_LIMITS = {
    east: [50000, 750000],
    north: [6600000, 7800000],
    zoom: [1, 10]
};

// Performs validation checks on user fields like username, Elinvoimakeskus, zoom, and map coordinates.
// Validation can be conditionally enabled or disabled using the `options` object.
// eslint-disable-next-line complexity
export function validateUserFields(fields, options) {
    const {
        checkUsername = true,
        checkElinvoimakeskus = true,
        checkCoordinates = true
    } = options || {};

    const errors = {};
    const { username, elinvoimakeskus, east, north, zoom } = fields;

    if (checkUsername) {
        if (!username || !(/^[A-Za-zÅÄÖåäö]/).test(username))
            errors.username = "Tunnuksen ensimmäisen merkin tulee olla kirjain.";
        else if ((username.match(/\d/g) || []).length < 4)
            errors.username = "Tunnuksessa tulee olla vähintään 4 numeroa.";
        else if (username.length > 10)
            errors.username = "Tunnus saa olla enintään 10 merkkiä pitkä.";
    }

    if (checkElinvoimakeskus && (!elinvoimakeskus || elinvoimakeskus.length === 0)) {
        errors.elinvoimakeskus = "Valitse vähintään yksi Elinvoimakeskus.";
    }

    if (checkCoordinates) {
        if (typeof east !== 'number' || east < COORD_LIMITS.east[0] || east > COORD_LIMITS.east[1]) {
            errors.east = `Itä-koordinaatin on oltava välillä ${COORD_LIMITS.east[0]} - ${COORD_LIMITS.east[1]}.`;
        }
        if (typeof north !== 'number' || north < COORD_LIMITS.north[0] || north > COORD_LIMITS.north[1]) {
            errors.north = `Pohjois-koordinaatin on oltava välillä ${COORD_LIMITS.north[0]} - ${COORD_LIMITS.north[1]}.`;
        }
        if (!Number.isInteger(zoom) || zoom < COORD_LIMITS.zoom[0] || zoom > COORD_LIMITS.zoom[1]) {
            errors.zoom = `Zoomin on oltava kokonaisluku väliltä ${COORD_LIMITS.zoom[0]}–${COORD_LIMITS.zoom[1]}.`;
        }
    }

    return errors;
}

// Uses the validate function and shows all found errors using global toast
export function validateUserFieldsAndToastErrors(fields, options = {}, prefix = '') {
    const errors = validateUserFields(fields, options);

    if (Object.keys(errors).length) {
        const errorMessage = Object.values(errors).join('; ');
        Toast.show(prefix + errorMessage, { type: 'error' });
        return { valid: false, errors };
    }

    return { valid: true, errors: {} };
}

// Backward compatibility exports
window.UserManagement = window.UserManagement || {};
window.UserManagement.FormValidation = { validateUserFields };
window.UserManagement.FormUtils = { validateUserFieldsAndToastErrors };
