(function (root) {

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

    root.UserManagementContent = function () {
        const roles = [
            { value: 'operator', label: 'Operator', desc: 'Pääsy sovelluksen hallinointi työkaluihin' },
            { value: 'viite', label: 'Viite', desc: 'Projektien luonti ja kohteiden muokkaus' },
            { value: 'dev', label: 'Dev', desc: 'Pääsy kehittäjätyökaluihin' },
            { value: 'admin', label: 'Admin', desc: 'Käyttäjähallinta ja dynaamisen verko ajo' }
        ];

        let elyOptions = [];

        if (ViiteEnumerations && ViiteEnumerations.ElyCodes) {
            elyOptions = Object.values(ViiteEnumerations.ElyCodes).map(function (ely) {
                return {
                    value: ely.value,
                    label: ely.name + ' (' + ely.shortName + ')',
                    code: ely.shortName
                };
            });
        }

        function validateUserFields(fields, options = {}) {
            const {
                checkUsername = true,
                checkRoles = true,
                checkElys = true,
                checkCoordinates = true,
            } = options;
            const errors = {};

            const { username, roles, elys, east, north, zoom } = fields;

            if (checkUsername) {
                if (!username || !/^[A-Za-z]/.test(username))
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
                if (east < COORD_LIMITS.east[0] || east > COORD_LIMITS.east[1]) {
                    errors.east = `Itä-koordinaatin on oltava välillä ${COORD_LIMITS.east[0]} - ${COORD_LIMITS.east[1]}`;
                }
                if (north < COORD_LIMITS.north[0] || north > COORD_LIMITS.north[1]) {
                    errors.north = `Pohjois-koordinaatin on oltava välillä ${COORD_LIMITS.north[0]} - ${COORD_LIMITS.north[1]}`;
                }
                if (!Number.isInteger(zoom) || zoom < COORD_LIMITS.zoom[0] || zoom > COORD_LIMITS.zoom[1]) {
                    errors.zoom = `Zoomin on oltava kokonaisluku väliltä ${COORD_LIMITS.zoom[0]}–${COORD_LIMITS.zoom[1]}.`;
                }
            }

            return errors;
        }

        function showFormErrors(errors) {

            // Toast once all together as summary
            const messages = Object.values(errors);
            if (messages.length) {
                Toast.show(messages.join(" "), { type: 'warning' });
            }
        }

        function handleSuccess(message) {
            Toast.show(message, { type: 'success' });
            fetchUsers();
        }

        function resetForm() {
            document.getElementById('newUserUsername').value = '';
            document.getElementById('newUserZoom').value = DEFAULT_COORDINATES.zoom;
            document.getElementById('newUserEast').value = DEFAULT_COORDINATES.east;
            document.getElementById('newUserNorth').value = DEFAULT_COORDINATES.north;
            setSelectedRoles('newUserRoles', []);
            setSelectedElys('newUserElys', [12, 14]);
        }

        function handleAddUser() {
            const username = document.getElementById('newUserUsername').value.trim();
            const roles = getSelectedRoles('newUserRoles');
            const zoom = parseInt(document.getElementById('newUserZoom').value || DEFAULT_COORDINATES.zoom);
            const east = parseFloat(document.getElementById('newUserEast').value || DEFAULT_COORDINATES.east);
            const north = parseFloat(document.getElementById('newUserNorth').value || DEFAULT_COORDINATES.north);
            const elys = getSelectedElys('newUserElys'); // Array of integers

            const fields = { username, roles, elys, east, north, zoom };
            const errors = validateUserFields(fields);

            showFormErrors(errors);

            if (Object.keys(errors).length) {
                return;
            }

            const newUser = {
                id: 0, // Will be replaced by backend
                username,
                configuration: {
                    roles,
                    zoom,
                    east,
                    north,
                    authorizedElys: elys
                }
            };

            userManagementBackend.addUser(
                newUser,
                function (response) {
                    if (response && response.success === false) {
                        Toast.show(response.reason || "Virhe lisättäessä käyttäjää", { type: 'error' });
                    } else {
                        handleSuccess("Käyttäjä lisätty!");
                        fetchUsers();
                        resetForm();
                    }
                },
                function (errorMessage) {
                    Toast.show(errorMessage, { type: 'error' });
                }
            );
        }

        let eventsBound = false;

        function bindEvents(containerSelector) {
            if (eventsBound) return;
            eventsBound = true;

            const container = document.querySelector(containerSelector);
            if (!container) return;

            const addUserButton = container.querySelector('#addUserButton');
            if (addUserButton) {
                addUserButton.addEventListener('click', e => {
                    e.preventDefault();
                    handleAddUser();
                });
            }

            const updateUsersButton = container.querySelector('#updateUsersButton');
            if (updateUsersButton) {
                updateUsersButton.addEventListener('click', e => {
                    e.preventDefault();
                    const usersToUpdate = [];
                    let hasErrors = false;

                    $(container).find('#userTableBody tr').each(function () {
                        const $row = $(this);
                        const id = $row.data('userid');
                        const username = $row.data('username');

                        const roleDropdownWrapper = $row.find('[data-role-dropdown-id]');
                        const elyDropdownWrapper = $row.find('[data-ely-dropdown-id]');
                        if (!roleDropdownWrapper.length || !elyDropdownWrapper.length) return;

                        const rolesId = roleDropdownWrapper.attr('data-role-dropdown-id');
                        const elysId = elyDropdownWrapper.attr('data-ely-dropdown-id');

                        const roles = getSelectedRoles(rolesId);
                        const elys = getSelectedElys(elysId);

                        // Use the robust extraction as previously discussed
                        const eastRaw = $row.find('input[id^="userEast"]').val();
                        const northRaw = $row.find('input[id^="userNorth"]').val();
                        const zoomRaw = $row.find('.zoom-input').val();

                        const east = !eastRaw ? DEFAULT_COORDINATES.east : parseFloat(eastRaw);
                        const north = !northRaw ? DEFAULT_COORDINATES.north : parseFloat(northRaw);
                        const zoom = !zoomRaw ? DEFAULT_COORDINATES.zoom : parseInt(zoomRaw, 10);

                        const fixedEast = isNaN(east) ? DEFAULT_COORDINATES.east : east;
                        const fixedNorth = isNaN(north) ? DEFAULT_COORDINATES.north : north;
                        const fixedZoom = isNaN(zoom) ? DEFAULT_COORDINATES.zoom : zoom;

                        const fields = { roles, elys, east: fixedEast, north: fixedNorth, zoom: fixedZoom };
                        const validationOptions = {
                            checkUsername: false,
                            checkRoles: true,
                            checkElys: true,
                            checkCoordinates: true,
                        };

                        const errors = validateUserFields(fields, validationOptions);

                        if (Object.keys(errors).length) {
                            hasErrors = true;
                            Toast.show('Virhe rivillä käyttäjä: ' + username + ': ' + Object.values(errors).join("; "), { type: 'error' });
                            $row.addClass('row-has-error');
                        } else {
                            $row.removeClass('row-has-error');
                            usersToUpdate.push({
                                id,
                                username,
                                configuration: {
                                    roles,
                                    zoom: fixedZoom,
                                    east: fixedEast,
                                    north: fixedNorth,
                                    authorizedElys: elys
                                }
                            });
                        }
                    });

                    // If any errors, DO NOT send update
                    if (hasErrors) return;

                    // Otherwise, send update
                    userManagementBackend.updateUsers(
                        usersToUpdate,
                        function (response) {
                            if (response && response.success === false) {
                                Toast.show(response.reason || "Virhe käyttäjien päivityksessä", { type: 'error' });
                            } else {
                                handleSuccess("Käyttäjät päivitetty!");
                                fetchUsers();
                            }
                        },
                        function (errorMessage) {
                            Toast.show(errorMessage, { type: 'error' });
                        }
                    );

                });
            }

            container.addEventListener('change', e => {
                if (e.target.type === 'checkbox') {
                    const wrapper = e.target.closest('[data-role-dropdown-id], [data-ely-dropdown-id]');
                    if (wrapper) {
                        if (wrapper.hasAttribute('data-role-dropdown-id')) {
                            updateRoleDropdownLabel(wrapper);
                        } else {
                            updateElyDropdownLabel(wrapper);
                        }
                    }
                }
            });

            $(document).on('click', '.clickable-role', function (e) {
                const tag = e.target.tagName.toLowerCase();

                // Let native input and label clicks toggle checkbox naturally, so do nothing here
                if (tag === 'input' || tag === 'label') {
                    // After native toggle, update the dropdown label to reflect new state
                    const checkboxId = $(this).data('checkbox-id');
                    const $checkbox = $('#' + checkboxId);
                    const $wrapper = $checkbox.closest('[data-role-dropdown-id]');
                    if ($wrapper.length) {
                        // Slight delay to allow checkbox state to update
                        setTimeout(() => updateRoleDropdownLabel($wrapper[0]), 0);
                    }
                    return;
                }

                // For clicks on other parts of the clickable-role div, toggle checkbox manually
                const checkboxId = $(this).data('checkbox-id');
                const $checkbox = $('#' + checkboxId);
                const newState = !$checkbox.prop('checked');
                $checkbox.prop('checked', newState).trigger('change');

                const $wrapper = $checkbox.closest('[data-role-dropdown-id]');
                if ($wrapper.length) {
                    updateRoleDropdownLabel($wrapper[0]);
                }
            });

            $(document).on('click', '.clickable-ely', function (e) {
                // Allow toggling when clicking anywhere in the row, including on label and input
                // But prevent double toggling from label click because label naturally toggles checkbox when clicked

                const tag = e.target.tagName.toLowerCase();

                if (tag === 'input') {
                    // Input clicks already toggle, so just update label
                    const checkboxId = $(this).data('checkbox-id');
                    const $checkbox = $('#' + checkboxId);
                    const $wrapper = $checkbox.closest('[data-ely-dropdown-id]');
                    if ($wrapper.length) {
                        updateElyDropdownLabel($wrapper[0]);
                    }
                    return;
                }

                if (tag === 'label') {
                    // Let label click toggle checkbox naturally, just update label after a short delay to allow checkbox state change
                    const checkboxId = $(this).data('checkbox-id');
                    const $checkbox = $('#' + checkboxId);
                    const $wrapper = $checkbox.closest('[data-ely-dropdown-id]');
                    setTimeout(() => {
                        if ($wrapper.length) {
                            updateElyDropdownLabel($wrapper[0]);
                        }
                    }, 0);
                    return;
                }

                // For clicks in empty space or other elements inside clickable-ely - toggle manually
                const checkboxId = $(this).data('checkbox-id');
                const $checkbox = $('#' + checkboxId);
                $checkbox.prop('checked', !$checkbox.prop('checked')).trigger('change');
                const $wrapper = $checkbox.closest('[data-ely-dropdown-id]');
                if ($wrapper.length) {
                    updateElyDropdownLabel($wrapper[0]);
                }
            });

        }


        // --- HTML content rendering ---
        const getContent = () => `
            <div class="user-management-content-wrapper">

                <!-- Uusi käyttäjä -->
                <h3>Uusi käyttäjä</h3>
                <div class="user-management-form">

                    <!-- Username and coordinates in same row -->
                    <div class="form-row horizontal-row">
                        <!-- Username -->
                        <div class="form-group username-group">
                          <label class="user-management-label" for="newUserUsername">Käyttäjätunnus</label>
                          <input type="text" id="newUserUsername" placeholder="LX123456" class="form-control" />
                        </div>

                        <!-- Coordinates -->
                        <div class="coordinates-group">
                            <label class="user-management-label">Oletus sijainti kartalla</label>
                            <div class="coordinate-wrapper">
                                <div class="coordinate-input">
                                    <label class="user-management-label" for="newUserNorth">P</label>
                                    <input
                                    id="newUserNorth"
                                    class="coord-input form-control"
                                    type="number"
                                    min="${COORD_LIMITS.north[0]}"
                                    max="${COORD_LIMITS.north[1]}"
                                    value="${DEFAULT_COORDINATES.north}"
                                    />
                                </div>
                                <div class="coordinate-input">
                                    <label class="user-management-label" for="newUserEast">I</label>
                                    <input
                                    class="coord-input form-control"
                                    id="newUserEast"
                                    type="number"
                                    min="${COORD_LIMITS.east[0]}"
                                    max="${COORD_LIMITS.east[1]}"
                                    value="${DEFAULT_COORDINATES.east}"
                                    />
                                </div>
                                <div class="coordinate-input">
                                    <label class="user-management-label" for="newUserZoom">Zoom</label>
                                    <input class="zoom-input form-control coord-input" type="number" id="newUserZoom" min="$COORD_LIMITS.zoom[0]" max="$COORD_LIMITS.zoom[1]" value="3" />
                                </div>
                            </div>
                        </div>
                    </div>

                    <!-- Roles and ELYs -->
                    <div class="form-row vertical-row">
                        <div class="form-group">
                            <label class="user-management-label">Roolit</label>
                            ${getRoleDropdownHtml('newUserRoles')}
                        </div>
                        <div class="form-group">
                            <label class="user-management-label">Sallitut ELYt</label>
                            ${getElyDropdownHtml('newUserElys', [12, 14])}
                        </div>
                    </div>

                    <div class="form-actions">
                        <button id="addUserButton" class="btn btn-primary">Lisää käyttäjä</button>
                    </div>
                </div>

                <!-- Existing users -->
                <h3>Nykyiset käyttäjät</h3>
                <div class="user-management-form">
                    <div class="user-list-container">
                        <table class="table user-table">
                            <thead>
                                <tr>
                                  <th>Tunnus</th>
                                  <th>Roolit</th>
                                  <th>Zoom</th>
                                  <th class="centered">Koordinaatit</th>
                                  <th>ELY</th>
                                  <th></th>
                                </tr>
                            </thead>
                            <tbody id="userTableBody"></tbody>
                        </table>
                    </div>
                    <div class="form-actions">
                        <button id="updateUsersButton" class="btn btn-primary">Tallenna muutokset</button>
                    </div>
                </div>
            </div>
        `;

        // --- Dropdown helpers ---
        function getRoleDropdownHtml(id, selectedRoles) {
            if (selectedRoles === undefined) selectedRoles = [];

            const selectedLabels = roles
                .filter(function (role) { return selectedRoles.includes(role.value); })
                .map(function (r) { return r.label; })
                .join(', ') || 'Valitse roolit';

            const roleCheckboxes = roles.map(function (role) {
                const checkboxId = `${id}-${role.value}`;
                const isChecked = selectedRoles.includes(role.value);

                return `
                    <div class="role-item clickable-role" data-role="${role.value}" data-checkbox-id="${checkboxId}">
                        <input type="checkbox" id="${checkboxId}" name="${id}" value="${role.value}" ${isChecked ? 'checked' : ''}>
                        <div>
                            <label class="user-management-label" for="${checkboxId}">${role.label}</label>
                            <div class="role-description">${role.desc}</div>
                        </div>
                    </div>
                `;
            }).join('');

            return `
                <div class="role-dropdown-wrapper" data-role-dropdown-id="${id}">
                    <div class="dropdown-toggle roles">
                        <span class="dropdown-label">${selectedLabels}</span>
                        <span class="dropdown-arrow">▼</span>
                    </div>
                    <div class="dropdown-content hidden">
                        ${roleCheckboxes}
                    </div>
                </div>
            `;
        }

        function getElyDropdownHtml(id, selectedElys) {
            if (selectedElys === undefined) selectedElys = [];

            const selectedLabels = elyOptions
                .filter(function (ely) { return selectedElys.includes(ely.value); })
                .map(function (e) { return e.code; })
                .join(', ') || 'Valitse ELYt';

            const elyCheckboxes = elyOptions.map(function (ely) {
                const checkboxId = `${id}-${ely.value}`;
                const isChecked = selectedElys.includes(ely.value);

                return `
                    <div class="ely-item clickable-ely" data-ely="${ely.value}" data-checkbox-id="${checkboxId}">
                        <input type="checkbox" id="${checkboxId}" name="${id}" value="${ely.value}" ${isChecked ? 'checked' : ''}>
                        <label class="user-management-label" for="${checkboxId}">${ely.label}</label>
                    </div>
                `;
            }).join('');

            return `
                <div class="ely-dropdown-wrapper" data-ely-dropdown-id="${id}">
                    <div class="dropdown-toggle">
                        <span class="dropdown-label">${selectedLabels}</span>
                        <span class="dropdown-arrow">▼</span>
                    </div>
                    <div class="dropdown-content hidden">
                        ${elyCheckboxes}
                    </div>
                </div>
            `;
        }

        function getSelectedRoles(dropdownId) {
            return Array.from(
                document.querySelectorAll('[data-role-dropdown-id="' + dropdownId + '"] input[type="checkbox"]:checked')
            ).map(function (cb) { return cb.value; });
        }

        function getSelectedElys(dropdownId) {
            return Array.from(
                document.querySelectorAll('[data-ely-dropdown-id="' + dropdownId + '"] input[type="checkbox"]:checked')
            ).map(function (cb) { return parseInt(cb.value); });
        }

        function setSelectedRoles(dropdownId, rolesArray) {
            let wrapper = document.querySelector('[data-role-dropdown-id="' + dropdownId + '"]');
            wrapper.querySelectorAll('input[type="checkbox"]').forEach(function (checkbox) {
                checkbox.checked = rolesArray.includes(checkbox.value);
            });
            updateRoleDropdownLabel(wrapper);
        }

        function setSelectedElys(dropdownId, elysArray) {
            const wrapper = document.querySelector('[data-ely-dropdown-id="' + dropdownId + '"]');
            wrapper.querySelectorAll('input[type="checkbox"]').forEach(function (checkbox) {
                checkbox.checked = elysArray.includes(parseInt(checkbox.value));
            });
            updateElyDropdownLabel(wrapper);
        }

        function updateRoleDropdownLabel(wrapper) {
            wrapper.querySelector('.dropdown-label').textContent = Array.from(wrapper.querySelectorAll('input[type="checkbox"]:checked'))
                .map(function (cb) {
                    return cb.nextElementSibling.querySelector('label').textContent;
                })
                .join(', ') || 'Valitse roolit';
        }

        function updateElyDropdownLabel(wrapper) {
            wrapper.querySelector('.dropdown-label').textContent = Array.from(wrapper.querySelectorAll('input[type="checkbox"]:checked'))
                .map(function (cb) {
                    var ely = elyOptions.find(function (e) {
                        return e.value === parseInt(cb.value);
                    });
                    return ely ? ely.code : '';
                })
                .filter(Boolean)
                .join(', ') || 'Valitse ELYt';
        }

        // --- Fetch & render existing users ---
        function fetchUsers() {
            userManagementBackend.getAllUsers(function (users) {
                const tableBody = document.getElementById('userTableBody');
                if (!tableBody) return;

                tableBody.innerHTML = '';

                users.forEach(function (user, index) {
                    let roleDropdownId = 'userRoles-' + index;
                    let elyDropdownId = 'userElys-' + index;
                    let row = document.createElement('tr');
                    row.dataset.username = user.username;
                    row.dataset.userid = user.id;

                    row.innerHTML = `
                <td>${user.username}</td>
                <td>${getRoleDropdownHtml(roleDropdownId, user.roles)}</td>
                <td>
                    <input class="zoom-input existing-user-input form-control" type="number" min="1" max="10" value="${user.configuration.zoom}">
                </td>
                <td class="coordinate-wrapper">
                    <label class="user-management-label" for="userNorth-${index}">P:</label>
                    <input type="number" id="userNorth-${index}" class="coord-input existing-user-input form-control" value="${user.configuration.north}">
                    <label class="user-management-label" for="userEast-${index}">I:</label>
                    <input type="number" id="userEast-${index}" class="coord-input existing-user-input form-control" value="${user.configuration.east}">
                </td>
                <td>${getElyDropdownHtml(elyDropdownId, user.authorizedElys)}</td>
                <td><button class="btn btn-danger delete-user" data-username="${user.username}">Poista</button></td>
            `;
                    tableBody.appendChild(row);
                });

                document.querySelectorAll('.delete-user').forEach(function (btn) {
                    btn.addEventListener('click', function () {
                        const username = this.dataset.username;
                        const currentUsername = applicationModel.getSessionUsername();
                        if (username === currentUsername) {
                            Toast.show("Et voi poistaa itseäsi.", { type: 'warning' })
                            return
                        }
                        if (confirm(`Poistetaanko käyttäjä ${username}?`)) {
                            userManagementBackend.deleteUser(username,
                                function (response) {
                                    if (response && response.success === false) {
                                        Toast.show(response.reason || "Virhe poistettaessa käyttäjää", { type: 'error' });
                                    } else {
                                        handleSuccess("Käyttäjä poistettu!");
                                        fetchUsers();
                                    }
                                },
                                function (errorMessage) {
                                    Toast.show(errorMessage, { type: 'error' });
                                }
                            );
                        }
                    });
                });
            });
        }

        // --- Dropdown JS event: toggle/show/hide ---
        $(document).on('click', '.dropdown-toggle', function (event) {
            event.stopPropagation();
            const $wrapper = $(this).closest('[data-role-dropdown-id], [data-ely-dropdown-id]');
            const $content = $wrapper.find('.dropdown-content');
            $('.dropdown-content').not($content).addClass('hidden');
            $content.toggleClass('hidden');
        });
        $(document).on('click', '.dropdown-content', function (event) {
            event.stopPropagation();
        });
        $(document).on('click', function () {
            $('.dropdown-content').addClass('hidden');
        });

        return {
            getContent: getContent,
            bindEvents: bindEvents,
            loadUsers: fetchUsers,
        };
    };
}(this));
