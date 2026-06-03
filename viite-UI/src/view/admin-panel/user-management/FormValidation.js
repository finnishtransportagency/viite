/**
 * FormValidation - Validates user management form fields and provides utility functions.
 */
import { showToast } from '@components/toast/Toast.js';

// Performs validation checks on user fields like username and Elinvoimakeskus.
// Validation can be conditionally enabled or disabled using the `options` object.
// eslint-disable-next-line complexity
export function validateUserFields(fields, options) {
    const {
        checkUsername = true,
        checkElinvoimakeskus = true
    } = options || {};

    const errors = {};
    const { username, elinvoimakeskus } = fields;

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

    return errors;
}

// Uses the validate function and shows all found errors using global toast
export function validateUserFieldsAndToastErrors(fields, options = {}, prefix = '') {
    const errors = validateUserFields(fields, options);

    if (Object.keys(errors).length) {
        const errorMessage = Object.values(errors).join('; ');
        showToast(prefix + errorMessage, { type: 'error' });
        return { valid: false, errors };
    }

    return { valid: true, errors: {} };
}
