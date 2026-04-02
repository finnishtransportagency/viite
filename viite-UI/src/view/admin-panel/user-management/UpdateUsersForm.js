/**
 * UpdateUsersForm - Handles updating existing users
 */
import { validateUserFields, validateUserFieldsAndToastErrors } from './FormValidation.js';
import { getRoleDropdownHtml, getElinvoimakeskusDropdownHtml, getSelectedRoles, getSelectedElinvoimakeskus } from './Dropdowns.js';
import { Toast } from '@components/Toast.js';

const DEFAULT_COORDINATES = {
    zoom: 3,
    east: 440220,
    north: 7175360
};

const COORD_LIMITS = {
    east: [50000, 750000],
    north: [6600000, 7800000],
    zoom: [1, 10]
};

/**
 * Handle standard API response object and show appropriate toast.
 */
function handleApiResponse(response, successMessage, errorMessage, onSuccess) {
    if (response && response.success === true) {
        const msg = response.message || successMessage;
        Toast.show(msg, { type: 'success' });
        if (typeof onSuccess === 'function') onSuccess();
    } else {
        const reason = (response && response.reason) || errorMessage;
        Toast.show(reason, { type: 'error' });
    }
}

export const UpdateUserForm = {
    // Fetch all users and render them into the table with editable field
    fetchUsers: function () {
        window.userManagementApi.getAllUsers(function (users) {
            const tableBody = document.getElementById('userTableBody');
            if (!tableBody) return;
            tableBody.innerHTML = '';

            if (!users || users.length === 0) {
                Toast.show("Käyttäjiä ei löytynyt.", "warning");
                return;
            }

            users.forEach(function (user, index) {
                const roleDropdownId = 'userRoles-' + index;
                const elinvoimakeskusDropdownId = 'userElinvoimakeskus-' + index;
                const row = document.createElement('tr');

                row.dataset.username = user.username;
                row.dataset.userid = user.id;

                row.innerHTML = `
                    <td>${user.username}</td>
                    <td>${getRoleDropdownHtml(roleDropdownId, user.roles)}</td>
                    <td>
                      <input
                        class="zoom-input existing-user-input form-control"
                        type="number"
                        inputmode="numeric"
                        pattern="[0-9]*"
                        min="${COORD_LIMITS.zoom[0]}"
                        max="${COORD_LIMITS.zoom[1]}"
                        value="${user.configuration.zoom}"
                        onkeydown="return event.key !== '.' && event.key !== ','"
                      >
                    </td>
                    <td class="coordinate-wrapper">
                        <label class="user-management-label" for="userNorth-${index}">P:</label>
                        <input type="number" id="userNorth-${index}" class="coord-input north existing-user-input form-control" value="${user.configuration.north}">
                        <label class="user-management-label" for="userEast-${index}">I:</label>
                        <input type="number" id="userEast-${index}" class="coord-input east existing-user-input form-control" value="${user.configuration.east}">
                    </td>
                    <td>${getElinvoimakeskusDropdownHtml(elinvoimakeskusDropdownId, user.authorizedElinvoimakeskus)}</td>
                    <td><button class="btn btn-danger delete-user" data-username="${user.username}">Poista</button></td>
                `;
                tableBody.appendChild(row);
            });

            // Set up delete user button logic
            document.querySelectorAll('.delete-user').forEach(function (btn) {
                btn.addEventListener('click', function () {
                    const username = this.dataset.username;
                    const currentUsername = window.applicationModel.getSessionUsername();

                    if (username === currentUsername) {
                        Toast.show("Et voi poistaa itseäsi.", "warning");
                        return;
                    }

                    if (confirm(`Poistetaanko käyttäjä ${username}?`)) {
                        window.userManagementApi.deleteUser(
                            username,
                            function (response) {
                                handleApiResponse(
                                    response,
                                    "Käyttäjä poistettu!",
                                    "Virhe poistettaessa käyttäjää.",
                                    () => UpdateUserForm.fetchUsers()
                                );
                            },
                            function (errorMessage) {
                                Toast.show(errorMessage, { type: 'error' });
                            }
                        );
                    }
                });
            });
        });
    },

    // Gather updated user data from table rows, validate, and send to API
    updateAllUsers: function (container) {
        const rows = container.find('#userTableBody tr');
        const usersToUpdate = [];
        let hasErrors = false;

        rows.each(function () {
            const $row = $(this);
            const id = $row.data('userid');
            const username = $row.data('username');

            const roleWrapper = $row.find('[data-role-dropdown-id]');
            const elinvoimakeskusWrapper = $row.find('[data-elinvoimakeskus-dropdown-id]');
            if (!roleWrapper.length || !elinvoimakeskusWrapper.length) return;

            const rolesId = roleWrapper.attr('data-role-dropdown-id');
            const elinvoimakeskusId = elinvoimakeskusWrapper.attr('data-elinvoimakeskus-dropdown-id');

            const roles = getSelectedRoles(rolesId);
            const elinvoimakeskus = getSelectedElinvoimakeskus(elinvoimakeskusId);

            const eastRaw = $row.find('input[id^="userEast"]').val();
            const northRaw = $row.find('input[id^="userNorth"]').val();
            const zoomRaw = $row.find('.zoom-input').val();

            const east = eastRaw === undefined || eastRaw === '' ? DEFAULT_COORDINATES.east : parseFloat(eastRaw);
            const north = northRaw === undefined || northRaw === '' ? DEFAULT_COORDINATES.north : parseFloat(northRaw);
            const zoom = zoomRaw === undefined || zoomRaw === '' ? DEFAULT_COORDINATES.zoom : parseInt(zoomRaw);

            const fields = { roles, elinvoimakeskus, east, north, zoom };
            const { valid } = validateUserFieldsAndToastErrors(fields, {
                checkUsername: false,
                checkRoles: true,
                checkElinvoimakeskus: true,
                checkCoordinates: true
            }, `Virhe: `);

            if (!valid) {
                hasErrors = true;
                $row.addClass('row-has-error');
            } else {
                $row.removeClass('row-has-error');
                usersToUpdate.push({
                    id,
                    username,
                    configuration: {
                        roles,
                        zoom,
                        east,
                        north,
                        authorizedElinvoimakeskus: elinvoimakeskus
                    }
                });
            }
        });

        if (hasErrors) return;

        window.userManagementApi.updateUsers(
            usersToUpdate,
            function (response) {
                handleApiResponse(
                    response,
                    "Käyttäjät päivitetty!",
                    "Virhe käyttäjien päivityksessä.",
                    () => UpdateUserForm.fetchUsers()
                );
            },
            function (errorMessage) {
                Toast.show(errorMessage, { type: 'error' });
            }
        );
    },

    // Bind update button click handler to a container
    bindEvents: function (containerSelector) {
        const container = $(containerSelector);
        if (!container.length) return;

        container.off('click', '#updateUsersButton');
        container.on('click', '#updateUsersButton', function (e) {
            e.preventDefault();
            UpdateUserForm.updateAllUsers(container);
        });
    }
};

window.UserManagement = window.UserManagement || {};
window.UserManagement.UpdateUserForm = UpdateUserForm;
