/**
 * AddUserForm - Handles user creation with validation
 */
import { getSelectedElinvoimakeskus, getSelectedRoles, setSelectedElinvoimakeskus, setSelectedRoles } from './Dropdowns.js';
import { showToast } from '@components/toast/Toast.js';
import { validateUserFields } from './FormValidation.js';
import { userManagementApi } from '@utils/UserManagementApi.js';
import { button } from '@components/button/Button.js';

const DEFAULT_COORDINATES = {
    zoom: 3,
    east: 440220,
    north: 7175360
};

function showFormErrors(errors) {
    const messages = Object.values(errors);
    if (messages.length) {
        showToast(messages.join(" "), { type: 'warning' });
    }
}

function resetForm() {
    document.getElementById('newUserUsername').value = '';
    setSelectedRoles('newUserRoles', []);
    setSelectedElinvoimakeskus('newUserElinvoimakeskus', []);
}

export const AddUserForm = {
    handleAddUser: function (options = {}) {
        const { onUserAdded } = options;
        const username = document.getElementById('newUserUsername').value.trim();
        const roles = getSelectedRoles('newUserRoles');
        const zoom = DEFAULT_COORDINATES.zoom;
        const east = DEFAULT_COORDINATES.east;
        const north = DEFAULT_COORDINATES.north;
        const elinvoimakeskus = getSelectedElinvoimakeskus('newUserElinvoimakeskus');

        const fields = { username, roles, elinvoimakeskus, east, north, zoom };
        const errors = validateUserFields ? validateUserFields(fields) : {};

        showFormErrors(errors);
        if (Object.keys(errors).length) return;

        const newUser = {
            id: 0,
            username,
            configuration: {
                roles,
                zoom,
                east,
                north,
                authorizedElinvoimakeskus: elinvoimakeskus
            }
        };

        userManagementApi.addUser(
            newUser,
            function (response) {
                if (response && response.success === false) {
                    showToast(response.reason || "Virhe lisättäessä käyttäjää.", { type: 'error' });
                } else {
                    showToast("Käyttäjä lisätty!", { type: 'success' });
                    if (typeof onUserAdded === 'function') {
                        onUserAdded();
                    }
                    resetForm();
                }
            },
            function (errorMessage) {
                showToast(errorMessage, { type: 'error' });
            }
        );
    },

    bindEvents: function (containerSelector, options = {}) {
        const container = $(containerSelector);
        if (!container.length) return;

        container.find('#addUserButton').replaceWith(
            $(button({ id: 'addUserButton', label: 'Lisää käyttäjä', className: 'btn btn-primary', onClick: () => AddUserForm.handleAddUser(options) }))
        );
    }
};
