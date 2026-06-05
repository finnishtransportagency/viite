/**
 * UpdateUsersForm - Handles updating existing users
 */
import { validateUserFieldsAndToastErrors } from './FormValidation.js';
import { getRoleDropdownHtml, getElinvoimakeskusDropdownHtml, getSelectedRoles, getSelectedElinvoimakeskus, setSelectedRoles } from './Dropdowns.js';
import { showToast } from '@components/toast/Toast.js';
import { userManagementApi } from '@utils/UserManagementApi.js';
import { getSessionUsername } from '@model/ApplicationModel.js';
import { button } from '@components/button/Button.js';

const VIITE_ROLE = 'viite';

const DEFAULT_COORDINATES = {
    zoom: 3,
    east: 440220,
    north: 7175360
};

/**
 * Handle standard API response object and show appropriate toast.
 */
function handleApiResponse(response, successMessage, errorMessage, onSuccess) {
    if (response && response.success === true) {
        const msg = response.message || successMessage;
        showToast(msg, { type: 'success' });
        if (typeof onSuccess === 'function') onSuccess();
    } else {
        const reason = (response && response.reason) || errorMessage;
        showToast(reason, { type: 'error' });
    }
}

function updateToggleViiteButtonState() {
    const $btn = document.getElementById('toggleViiteAllButton');
    const rows = Array.from(document.querySelectorAll('#userTableBody tr'));

    if (!$btn) return;

    if (!rows.length) {
        $btn.disabled = true;
        $btn.textContent = 'Anna viite-oikeus kaikille';
        return;
    }

    $btn.disabled = false;

    const allHaveViiteRole = rows.every(function (row) {
        const roleWrapper = row.querySelector('[data-role-dropdown-id]');
        if (!roleWrapper) return false;

        const dropdownId = roleWrapper.getAttribute('data-role-dropdown-id');
        const roles = getSelectedRoles(dropdownId);
        return roles.includes(VIITE_ROLE);
    });

    $btn.textContent = allHaveViiteRole ? 'Poista viite-oikeus kaikilta' : 'Anna viite-oikeus kaikille';
}

function toggleViiteRoleForAllUsers() {
    const rows = Array.from(document.querySelectorAll('#userTableBody tr'));
    if (!rows.length) return;

    const allHaveViiteRole = rows.every(function (row) {
        const roleWrapper = row.querySelector('[data-role-dropdown-id]');
        if (!roleWrapper) return false;

        const dropdownId = roleWrapper.getAttribute('data-role-dropdown-id');
        const roles = getSelectedRoles(dropdownId);
        return roles.includes(VIITE_ROLE);
    });

    rows.forEach(function (row) {
        const roleWrapper = row.querySelector('[data-role-dropdown-id]');
        if (!roleWrapper) return;

        const dropdownId = roleWrapper.getAttribute('data-role-dropdown-id');
        const roles = getSelectedRoles(dropdownId);
        const nextRoles = allHaveViiteRole
            ? roles.filter(role => role !== VIITE_ROLE)
            : Array.from(new Set([].concat(roles, VIITE_ROLE)));

        setSelectedRoles(dropdownId, nextRoles);
    });

    updateToggleViiteButtonState();
}

function handleDeleteUser(username, options) {
    const currentUsername = getSessionUsername();
    if (username === currentUsername) {
        showToast("Et voi poistaa itseäsi.", { type: 'warning' });
        return;
    }
    if (confirm(`Poistetaanko käyttäjä ${username}?`)) {
        userManagementApi.deleteUser(
            username,
            function (response) {
                handleApiResponse(
                    response,
                    "Käyttäjä poistettu!",
                    "Virhe poistettaessa käyttäjää.",
                    () => UpdateUserForm.fetchUsers(options)
                );
            },
            function (errorMessage) {
                showToast(errorMessage, { type: 'error' });
            }
        );
    }
}

export const UpdateUserForm = {
    // Fetch all users and render them into the table with editable field
    fetchUsers: function (options = {}) {
        userManagementApi.getAllUsers(function (users) {
            const tableBody = document.getElementById('userTableBody');
            if (!tableBody) return;
            tableBody.innerHTML = '';

            if (!users || users.length === 0) {
                return;
            }

            users.forEach(function (user, index) {
                const roleDropdownId = 'userRoles-' + index;
                const elinvoimakeskusDropdownId = 'userElinvoimakeskus-' + index;
                const row = document.createElement('tr');

                row.dataset.username = user.username;
                row.dataset.userid = user.id;
                                row.dataset.zoom = String((user.configuration && user.configuration.zoom) || DEFAULT_COORDINATES.zoom);
                                row.dataset.east = String((user.configuration && user.configuration.east) || DEFAULT_COORDINATES.east);
                                row.dataset.north = String((user.configuration && user.configuration.north) || DEFAULT_COORDINATES.north);

                row.innerHTML = `
                    <td>${user.username}</td>
                    <td>${getRoleDropdownHtml(roleDropdownId, user.roles)}</td>
                    <td>${getElinvoimakeskusDropdownHtml(elinvoimakeskusDropdownId, user.authorizedElinvoimakeskus)}</td>
                    <td>${button({ id: 'delete-user-' + index, label: 'Poista', className: 'btn btn-danger', onClick: () => handleDeleteUser(user.username, options) })}</td>
                `;
                tableBody.appendChild(row);
            });

            updateToggleViiteButtonState();
        });
    },

    // Gather updated user data from table rows, validate, and send to API
    updateAllUsers: function (container, options = {}) {
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

            const east = parseFloat($row.attr('data-east') || DEFAULT_COORDINATES.east);
            const north = parseFloat($row.attr('data-north') || DEFAULT_COORDINATES.north);
            const zoom = parseInt($row.attr('data-zoom') || DEFAULT_COORDINATES.zoom, 10);

            const fields = { roles, elinvoimakeskus };
            const { valid } = validateUserFieldsAndToastErrors(fields, {
                checkUsername: false,
                checkRoles: true,
                checkElinvoimakeskus: true
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

        userManagementApi.updateUsers(
            usersToUpdate,
            function (response) {
                handleApiResponse(
                    response,
                    "Käyttäjät päivitetty!",
                    "Virhe käyttäjien päivityksessä.",
                    () => UpdateUserForm.fetchUsers(options)
                );
            },
            function (errorMessage) {
                showToast(errorMessage, { type: 'error' });
            }
        );
    },

    // Bind update button click handler to a container
    bindEvents: function (containerSelector, options = {}) {
        const container = $(containerSelector);
        if (!container.length) return;

        container.find('#updateUsersButton').replaceWith(
            $(button({ id: 'updateUsersButton', label: 'Tallenna muutokset', className: 'btn btn-primary', onClick: () => UpdateUserForm.updateAllUsers(container, options) }))
        );

        container.find('#toggleViiteAllButton').replaceWith(
            $(button({ id: 'toggleViiteAllButton', label: 'Anna viite-oikeus kaikille', className: 'btn btn-secondary', onClick: toggleViiteRoleForAllUsers }))
        );

        container.off('change', '[data-role-dropdown-id] input[type="checkbox"]');
        container.on('change', '[data-role-dropdown-id] input[type="checkbox"]', updateToggleViiteButtonState);

        updateToggleViiteButtonState();
    }
};
