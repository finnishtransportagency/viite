(function (root) {
    root.UserManagement = root.UserManagement || {};

    // Import shared constants and utilities
    const { DEFAULT_COORDINATES, COORD_LIMITS } = root.UserManagement.Constants;

    const {
        getRoleDropdownHtml,
        getElyDropdownHtml,
        getSelectedRoles,
        getSelectedElys
    } = root.UserManagement.Dropdowns;

    const { validateUserFieldsAndToastErrors } = root.UserManagement.FormUtils;

    /**
     * Handle standard API response object and show appropriate toast.
     * @param {Object} response - API response object.
     * @param {string} successMessage - Message shown on success.
     * @param {string} errorMessage - Fallback error message.
     * @param {Function} onSuccess - Callback executed on success.
     */
    function handleApiResponse (response, successMessage, errorMessage, onSuccess) {
        if (response && response.success === true) {
            const msg = response.message || successMessage;
            Toast.show(msg, { type: 'success' });
            if (typeof onSuccess === 'function') onSuccess();
        } else {
            const reason = (response && response.reason) || errorMessage;
            Toast.show(reason, { type: 'error' });
        }
    }

    root.UserManagement.UpdateUserForm = {

        // Fetch all users and render them into the table with editable field
        fetchUsers: function () {
            userManagementApi.getAllUsers(function (users) {
                const tableBody = document.getElementById('userTableBody');
                if (!tableBody) return;
                tableBody.innerHTML = '';

                if (!users || users.length === 0) {
                    Toast.show("Käyttäjiä ei löytynyt.", "warning");
                    return;
                }

                users.forEach(function (user, index) {
                    const roleDropdownId = 'userRoles-' + index;
                    const elyDropdownId = 'userElys-' + index;
                    const row = document.createElement('tr');

                    // Attach identifying data to row for later access
                    row.dataset.username = user.username;
                    row.dataset.userid = user.id;

                    // Render editable row with dropdowns and inputs
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
                        <td>${getElyDropdownHtml(elyDropdownId, user.authorizedElys)}</td>
                        <td><button class="btn btn-danger delete-user" data-username="${user.username}">Poista</button></td>
                    `;
                    tableBody.appendChild(row);
                });

                // Set up delete user button logic
                document.querySelectorAll('.delete-user').forEach(function (btn) {
                    btn.addEventListener('click', function () {
                        const username = this.dataset.username;
                        const currentUsername = applicationModel.getSessionUsername();

                        // Prevent self-deletion
                        if (username === currentUsername) {
                            Toast.show("Et voi poistaa itseäsi.", "warning");
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
                                        () => root.UserManagement.UpdateUserForm.fetchUsers()
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
                const elyWrapper = $row.find('[data-ely-dropdown-id]');
                if (!roleWrapper.length || !elyWrapper.length) return;

                const rolesId = roleWrapper.attr('data-role-dropdown-id');
                const elysId = elyWrapper.attr('data-ely-dropdown-id');

                const roles = getSelectedRoles(rolesId);
                const elys = getSelectedElys(elysId);

                // Parse coordinate and zoom values with fallbacks
                const eastRaw = $row.find('input[id^="userEast"]').val();
                const northRaw = $row.find('input[id^="userNorth"]').val();
                const zoomRaw = $row.find('.zoom-input').val();

                const east = eastRaw === undefined || eastRaw === '' ? DEFAULT_COORDINATES.east : parseFloat(eastRaw);
                const north = northRaw === undefined || northRaw === '' ? DEFAULT_COORDINATES.north : parseFloat(northRaw);
                const zoom = zoomRaw === undefined || zoomRaw === '' ? DEFAULT_COORDINATES.zoom : parseInt(zoomRaw);

                // Validate fields and notify if errors
                const fields = { roles, elys, east, north, zoom };
                const { valid } = validateUserFieldsAndToastErrors(fields, {
                    checkUsername: false,
                    checkRoles: true,
                    checkElys: true,
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
                            authorizedElys: elys
                        }
                    });
                }
            });

            if (hasErrors) return;

            // Send bulk update request
            userManagementApi.updateUsers(
                usersToUpdate,
                function (response) {
                    handleApiResponse(
                        response,
                        "Käyttäjät päivitetty!",
                        "Virhe käyttäjien päivityksessä.",
                        () => root.UserManagement.UpdateUserForm.fetchUsers()
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
                root.UserManagement.UpdateUserForm.updateAllUsers(container);
            });
        }
    };
}(this));
