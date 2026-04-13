/**
 * AddUserForm - Handles user creation with validation
 */
import { getSelectedElinvoimakeskus, getSelectedRoles, setSelectedElinvoimakeskus, setSelectedRoles } from './Dropdowns.js';
import { showToast } from '@components/Toast.js';
import { validateUserFields } from './FormValidation.js';
import { userManagementApi } from '@utils/UserManagementApi.js';

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
    document.getElementById('newUserZoom').value = DEFAULT_COORDINATES.zoom;
    document.getElementById('newUserEast').value = DEFAULT_COORDINATES.east;
    document.getElementById('newUserNorth').value = DEFAULT_COORDINATES.north;
    setSelectedRoles('newUserRoles', []);
    setSelectedElinvoimakeskus('newUserElinvoimakeskus', []);
}

export const AddUserForm = {
    handleAddUser: function (options = {}) {
        const { onUserAdded } = options;
        const username = document.getElementById('newUserUsername').value.trim();
        const roles = getSelectedRoles('newUserRoles');
        const zoom = parseInt(document.getElementById('newUserZoom').value || DEFAULT_COORDINATES.zoom, 10);
        const east = parseFloat(document.getElementById('newUserEast').value || DEFAULT_COORDINATES.east);
        const north = parseFloat(document.getElementById('newUserNorth').value || DEFAULT_COORDINATES.north);
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

        container.off('click', '#addUserButton');
        container.on('click', '#addUserButton', function (e) {
            e.preventDefault();
            AddUserForm.handleAddUser(options);
        });
    }
};
