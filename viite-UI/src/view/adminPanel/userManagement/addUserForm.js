// Handles user creation with validation
(function (root) {
    root.UserManagement = root.UserManagement || {};
    const {
        DEFAULT_COORDINATES
    } = root.UserManagement.Constants;
    const { validateUserFields } = root.UserManagement.FormValidation;
    const { getSelectedRoles, getSelectedElys, setSelectedRoles, setSelectedElys } = root.UserManagement.Dropdowns;

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
        setSelectedElys('newUserElys', []);
    }

    root.UserManagement.AddUserForm = {
        handleAddUser: function () {
            const username = document.getElementById('newUserUsername').value.trim();
            const roles = getSelectedRoles('newUserRoles');
            const zoom = parseInt(document.getElementById('newUserZoom').value || DEFAULT_COORDINATES.zoom, 10);
            const east = parseFloat(document.getElementById('newUserEast').value || DEFAULT_COORDINATES.east);
            const north = parseFloat(document.getElementById('newUserNorth').value || DEFAULT_COORDINATES.north);
            const elys = getSelectedElys('newUserElys');

            const fields = { username, roles, elys, east, north, zoom };
            const errors = validateUserFields(fields);

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
                    authorizedElys: elys
                }
            };

            userManagementApi.addUser(
                newUser,
                function (response) {
                    if (response && response.success === false) {
                        Toast.show(response.reason || "Virhe lisättäessä käyttäjää.", { type: 'error' });
                    } else {
                        Toast.show("Käyttäjä lisätty!", { type: 'success' });
                        root.UserManagement.UpdateUserForm.fetchUsers();  // Refresh existing users after add
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
                root.UserManagement.AddUserForm.handleAddUser();
            });
        },
    };
})(this);
