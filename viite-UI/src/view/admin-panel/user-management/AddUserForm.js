/**
 * AddUserForm - Handles user creation with validation
 */
import { getSelectedElinvoimakeskus, getSelectedRoles, setSelectedElinvoimakeskus, setSelectedRoles } from './Dropdowns.js';
import { Toast } from '@components/Toast.js';
import { validateUserFields } from './FormValidation.js';

const DEFAULT_COORDINATES = {
    zoom: 3,
    east: 440220,
    north: 7175360
};

function showFormErrors(errors) {
    const messages = Object.values(errors);
    if (messages.length) {
        Toast.show(messages.join(" "), { type: 'warning' });
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
    handleAddUser: function () {
        const username = document.getElementById('newUserUsername').value.trim();
        const roles = getSelectedRoles('newUserRoles');
        const zoom = parseInt(document.getElementById('newUserZoom').value || DEFAULT_COORDINATES.zoom);
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

        window.userManagementApi.addUser(
            newUser,
            function (response) {
                if (response && response.success === false) {
                    Toast.show(response.reason || "Virhe lisättäessä käyttäjää.", { type: 'error' });
                } else {
                    Toast.show("Käyttäjä lisätty!", { type: 'success' });
                    window.UserManagement.UpdateUserForm.fetchUsers();
                    resetForm();
                }
            },
            function (errorMessage) {
                Toast.show(errorMessage, { type: 'error' });
            }
        );
    },

    bindEvents: function (containerSelector) {
        const container = $(containerSelector);
        if (!container.length) return;

        container.off('click', '#addUserButton');
        container.on('click', '#addUserButton', function (e) {
            e.preventDefault();
            AddUserForm.handleAddUser();
        });
    }
};

window.UserManagement = window.UserManagement || {};
window.UserManagement.AddUserForm = AddUserForm;
