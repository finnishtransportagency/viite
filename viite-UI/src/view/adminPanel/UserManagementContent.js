// This content is rendered inside AdminPanel and can be used to add, remove and modify user data.
(function (root) {

    const DEFAULT_COORDINATES = {
        zoom: 3,
        east: 440220,
        north: 7175360
    };

    // Approximate area of Finland
    const COORD_LIMITS = {
        east: [50000, 750000],
        north: [6600000, 7800000]
    };

    let hasUnsavedChanges = false; // TODO

    root.UserManagementContent = function () {
        const roles = [
            { value: 'operator', label: 'Operator', desc: 'Pääsy sovelluksen hallinointi työkaluihin' },
            { value: 'viite', label: 'Viite', desc: 'Projektien luonti ja kohteiden muokkaus' },
            { value: 'dev', label: 'Dev', desc: 'Pääsy kehittäjätyökaluihin' },
            { value: 'admin', label: 'Admin', desc: 'Käyttäjähallinta ja dynaamisen verko ajo' }
        ];

        let elyOptions = []; // Array of objects like these: { value: 1, label: 'Uusimaa (UUD)', code: 'UUD' },

        if (ViiteEnumerations && ViiteEnumerations.ElyCodes) {
            elyOptions = Object.values(ViiteEnumerations.ElyCodes).map(function (ely) {
                return {
                    value: ely.value,
                    label: ely.name + ' (' + ely.shortName + ')',
                    code: ely.shortName
                };
            });
        }

        function handleSuccess(message) {
            Toast.show(message, { type: 'success' });
            loadUsers();
        }

        function validateNewUser({ username, roles, east, north }) {
            // Username validation
            if (!/^[A-Za-z]/.test(username)) return 'Tunnuksen ensimmäisen merkin tulee olla kirjain.';
            if ((username.match(/\d/g) || []).length < 4) return 'Tunnuksessa tulee olla vähintään 4 numeroa.';
            if (username.length > 10) return 'Tunnus saa olla enintään 10 merkkiä pitkä.';

            // Roles
            if (!roles || roles.length === 0) return 'Valitse vähintään yksi rooli.';

            // Coordinates
            if (east < COORD_LIMITS.east[0] || east > COORD_LIMITS.east[1]) return `Itä-koordinaatin on oltava välillä ${COORD_LIMITS.east[0]} - ${COORD_LIMITS.east[1]}`;
            if (north < COORD_LIMITS.north[0] || north > COORD_LIMITS.north[1]) return `Pohjois-koordinaatin on oltava välillä ${COORD_LIMITS.north[0]} - ${COORD_LIMITS.north[1]}`;

            return null; // Valid
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

            const validationError = validateNewUser({ username, roles, east, north });
            if (validationError) {
                Toast.show(validationError, { type: 'warning' });
                return;
            }

            const newUser = {
                id: 0, // This will be replaced by backend
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
                function(response) {
                    if (response && response.success === false) {
                        Toast.show(response.reason || "Virhe lisättäessä käyttäjää", { type: 'error' });
                    } else {
                        handleSuccess("Käyttäjä lisätty!");
                        loadUsers();
                        resetForm();
                    }
                },
                function(errorMessage) {
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

                    $(container).find('#userTableBody tr').each(function () {
                        const $row = $(this);
                        const id = $row.data('userid');
                        const username = $row.data('username');

                        // Get the actual dropdown wrapper elements
                        const roleDropdownWrapper = $row.find('[data-role-dropdown-id]');
                        const elyDropdownWrapper = $row.find('[data-ely-dropdown-id]');

                        if (!roleDropdownWrapper.length || !elyDropdownWrapper.length) return;

                        const rolesId = roleDropdownWrapper.attr('data-role-dropdown-id');
                        const elysId = elyDropdownWrapper.attr('data-ely-dropdown-id');

                        const roles = getSelectedRoles(rolesId);
                        const elys = getSelectedElys(elysId);

                        const zoom = parseInt($row.find('.zoom-input').val()) || DEFAULT_COORDINATES.zoom;
                        const east = parseFloat($row.find('input[id^="userEast"]').val()) || DEFAULT_COORDINATES.east;
                        const north = parseFloat($row.find('input[id^="userNorth"]').val()) || DEFAULT_COORDINATES.north;

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
                    });

                    userManagementBackend.updateUsers(
                        usersToUpdate,
                        function (response) {
                            if (response && response.success === false) {
                                Toast.show(response.reason || "Virhe käyttäjien päivityksessä", { type: 'error' });
                            } else {
                                handleSuccess("Käyttäjät päivitetty!");
                                loadUsers();
                                resetForm();
                            }
                        },
                        function(errorMessage) {
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

            // Toggle checkbox when the entire role row is clicked
            $(document).on('click', '.clickable-role', function (e) {
                if (e.target.tagName.toLowerCase() === 'input') return;

                const checkboxId = $(this).data('checkbox-id');
                const $checkbox = $('#' + checkboxId);
                const newState = !$checkbox.prop('checked');

                $checkbox.prop('checked', newState).trigger('change');

                const $wrapper = $checkbox.closest('[data-role-dropdown-id]');
                if ($wrapper.length) {
                    updateRoleDropdownLabel($wrapper[0]);
                }
            });

            // Toggle checkbox when the ELY row is clicked
            $(document).on('click', '.clickable-ely', function (e) {
                const tag = e.target.tagName.toLowerCase();
                if (tag === 'input' || tag === 'label') return;

                const checkboxId = $(this).data('checkbox-id');
                const $checkbox = $('#' + checkboxId);

                $checkbox.prop('checked', !$checkbox.prop('checked')).trigger('change');
            });
        }


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
                          <div id="newUserUsernameError" class="error-message hidden"></div>
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
                                    <div id="newUserNorthError" class="error-message hidden"></div>
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
                                    <div id="newUserEastError" class="error-message hidden"></div>
                                </div>
                    
                                <div class="coordinate-input">
                                    <label class="user-management-label" for="newUserZoom">Zoom</label>
                                    <input class="zoom-input form-control" type="number" id="newUserZoom" min="1" max="12" value="3" />
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

        function loadUsers() {
            userManagementBackend.getAllUsers(function(users) {
                const tableBody = document.getElementById('userTableBody');
                if (!tableBody) return;

                tableBody.innerHTML = '';

                users.forEach(function (user, index) {
                    console.log(user.id)
                    let roleDropdownId = 'userRoles-' + index;
                    let elyDropdownId = 'userElys-' + index;
                    let row = document.createElement('tr');
                    row.dataset.username = user.username;
                    row.dataset.userid = user.id;

                    row.innerHTML = `
                <td>${user.username}</td>
                <td>${getRoleDropdownHtml(roleDropdownId, user.roles)}</td>
                <td><input class="zoom-input existing-user-input form-control" type="number" min="1" max="10" value="${user.configuration.zoom}"></td>
                
                <td class="coordinate-wrapper">
                    <label class="user-management-label" for="userNorth-${index}">P:</label>
                    <input type="number" id="userNorth-${index}" class="coord-input existing-user-input form-control" value="${user.configuration.north}">

                    <label class="user-management-label" for="userEast-${index}">I:</label>
                    <input type="number" id="userEast-${index}" class="coord-input existing-user-input form-control" value="${user.configuration.east}">
                </td>

                <td>${getElyDropdownHtml(elyDropdownId, user.authorizedElys)}</td>
                <td><button class="btn-secondary delete-user" data-username="${user.username}">Poista</button></td>
            `;
                    tableBody.appendChild(row);
                });

                // Attach delete handlers
                document.querySelectorAll('.delete-user').forEach(function (btn) {
                    btn.addEventListener('click', function () {
                        const username = this.dataset.username;
                        const id = this.dataset.id;
                        if (confirm(`Poistetaanko käyttäjä ${username}?`)) {
                            userManagementBackend.deleteUser(id,
                                function(response) {
                                    if (response && response.success === false) {
                                        Toast.show(response.reason || "Virhe poistettaessa käyttäjää", { type: 'error' });
                                    } else {
                                        handleSuccess("Käyttäjä poistettu!");
                                        loadUsers();
                                    }
                                },
                                function(errorMessage) {
                                    Toast.show(errorMessage, { type: 'error' });
                                }
                            );
                        }
                    });
                });
            });
        }

        // Dropdown toggle handler
        $(document).on('click', '.dropdown-toggle', function(event) {
            event.stopPropagation();

            const $wrapper = $(this).closest('[data-role-dropdown-id], [data-ely-dropdown-id]');
            const $content = $wrapper.find('.dropdown-content');

            // Close other dropdowns first
            $('.dropdown-content').not($content).addClass('hidden');

            $content.toggleClass('hidden');
        });

        // Keep dropdown open when clicking inside
        $(document).on('click', '.dropdown-content', function(event) {
            event.stopPropagation();
        });

        // Close dropdowns when clicking anywhere outside
        $(document).on('click', function() {
            $('.dropdown-content').addClass('hidden');
        });

        return {
            getContent: getContent,
            bindEvents: bindEvents,
            loadUsers: loadUsers,
            hasUnsavedChanges: function() { return hasUnsavedChanges; }
        };
    };
}(this));
