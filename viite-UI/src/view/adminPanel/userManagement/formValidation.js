(function (root) {
    root.UserManagement = root.UserManagement || {};

    const { COORD_LIMITS } = root.UserManagement.Constants;

    root.UserManagement.FormValidation = {
        validateUserFields: function (fields, options) {
            const {
                checkUsername = true,
                checkRoles = true,
                checkElys = true,
                checkCoordinates = true
            } = options || {};

            const errors = {};
            const { username, roles, elys, east, north, zoom } = fields;

            if (checkUsername) {
                if (!username || !/^[A-Za-zÅÄÖåäö]/.test(username))
                    errors.username = "Tunnuksen ensimmäisen merkin tulee olla kirjain.";
                else if ((username.match(/\d/g) || []).length < 4)
                    errors.username = "Tunnuksessa tulee olla vähintään 4 numeroa.";
                else if (username.length > 10)
                    errors.username = "Tunnus saa olla enintään 10 merkkiä pitkä.";
            }

            if (checkRoles && (!roles || roles.length === 0)) {
                errors.roles = "Valitse vähintään yksi rooli.";
            }

            if (checkElys && (!elys || elys.length === 0)) {
                errors.elys = "Valitse vähintään yksi sallittu ELY.";
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
    };

    root.UserManagement.FormUtils = {

        validateUserFieldsAndToastErrors: function (fields, options = {}, prefix = '') {
            const { validateUserFields } = root.UserManagement.FormValidation;
            const errors = validateUserFields(fields, options);

            if (Object.keys(errors).length) {
                const errorMessage = Object.values(errors).join('; ');
                Toast.show(prefix + errorMessage, { type: 'error' });
                return { valid: false, errors };
            }

            return { valid: true, errors: {} };
        }
    };

})(this);
