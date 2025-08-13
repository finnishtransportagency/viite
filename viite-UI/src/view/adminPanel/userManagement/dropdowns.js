// These are used for selecting user roles and allowed ELYs
(function (root) {
    root.UserManagement = root.UserManagement || {};
    const { ROLES } = root.UserManagement.Constants;

    // Get ELY options from enumeration source
    function getElyOptions() {
        if (ViiteEnumerations && ViiteEnumerations.ElyCodes) {
            return Object.values(ViiteEnumerations.ElyCodes).map(ely => ({
                value: ely.value,
                label: `${ely.name} (${ely.shortName})`,
                code: ely.shortName
            }));
        }
        return [];
    }

    // Update role dropdown label based on selected checkboxes inside wrapper
    function updateRoleDropdownLabel(wrapper) {
        const labels = Array.from(wrapper.querySelectorAll('input[type="checkbox"]:checked')).map(cb => {
            const labelEl = wrapper.querySelector('label[for="' + cb.id + '"]');
            return labelEl ? labelEl.textContent : '';
        });
        const labelSpan = wrapper.querySelector('.dropdown-label');
        if (labelSpan) {
            labelSpan.textContent = labels.join(', ') || 'Valitse roolit';
        }
    }

    // Update ELY dropdown label based on selected checkboxes inside wrapper
    function updateElyDropdownLabel(wrapper) {
        const elyOptions = getElyOptions();
        const labels = Array.from(wrapper.querySelectorAll('input[type="checkbox"]:checked')).map(cb => {
            const ely = elyOptions.find(e => e.value === parseInt(cb.value));
            return ely ? ely.code : '';
        }).filter(Boolean);
        const labelSpan = wrapper.querySelector('.dropdown-label');
        if (labelSpan) {
            labelSpan.textContent = labels.join(', ') || 'Valitse ELY:t';
        }
    }

    // Get array of selected role values from a dropdown by ID
    function getSelectedRoles(dropdownId) {
        return Array.from(
            document.querySelectorAll('[data-role-dropdown-id="' + dropdownId + '"] input[type="checkbox"]:checked')
        ).map(cb => cb.value);
    }

    // Get array of selected ELY values (as integers) from a dropdown by ID
    function getSelectedElys(dropdownId) {
        return Array.from(
            document.querySelectorAll('[data-ely-dropdown-id="' + dropdownId + '"] input[type="checkbox"]:checked')
        ).map(cb => parseInt(cb.value));
    }

    // Set selected checkboxes for roles by array of values
    function setSelectedRoles(dropdownId, rolesArray) {
        const wrapper = document.querySelector('[data-role-dropdown-id="' + dropdownId + '"]');
        if (!wrapper) return;
        wrapper.querySelectorAll('input[type="checkbox"]').forEach(cb => {
            cb.checked = rolesArray.includes(cb.value);
        });
        updateRoleDropdownLabel(wrapper);
    }

    // Set selected checkboxes for ELYs by array of integer values
    function setSelectedElys(dropdownId, elysArray) {
        const wrapper = document.querySelector('[data-ely-dropdown-id="' + dropdownId + '"]');
        if (!wrapper) return;
        wrapper.querySelectorAll('input[type="checkbox"]').forEach(cb => {
            cb.checked = elysArray.includes(parseInt(cb.value));
        });
        updateElyDropdownLabel(wrapper);
    }

    // Dropdown toggle open/close event handlers
    $(document).on('click', '.dropdown-toggle', function (event) {
        event.stopPropagation();
        const $wrapper = $(this).closest('[data-role-dropdown-id], [data-ely-dropdown-id]');
        const $content = $wrapper.find('.dropdown-content');
        $('.dropdown-content').not($content).addClass('hidden');
        $content.toggleClass('hidden');
    });

    // Prevent dropdown closing when clicking inside dropdown content area
    $(document).on('click', '.dropdown-content', function (event) {
        event.stopPropagation();
    });

    // Clicking outside closes all dropdowns
    $(document).on('click', function () {
        $('.dropdown-content').addClass('hidden');
    });

    // Update dropdown labels when any checkbox inside role dropdown changes
    $(document).on('change', '[data-role-dropdown-id] input[type="checkbox"]', function () {
        const wrapper = $(this).closest('[data-role-dropdown-id]')[0];
        if (wrapper) updateRoleDropdownLabel(wrapper);
    });

    // Update dropdown labels when any checkbox inside ELY dropdown changes
    $(document).on('change', '[data-ely-dropdown-id] input[type="checkbox"]', function () {
        const wrapper = $(this).closest('[data-ely-dropdown-id]')[0];
        if (wrapper) updateElyDropdownLabel(wrapper);
    });

    // Make entire role or ely item row clickable to toggle checkbox
    $(document).on('click', '.clickable-role, .clickable-ely', function (e) {
        if ($(e.target).is('input') || $(e.target).is('label')) {
            return; // Ignore clicks directly on checkbox or label to avoid double toggling
        }
        const $checkbox = $(this).find('input[type="checkbox"]');
        const newState = !$checkbox.prop('checked');
        $checkbox.prop('checked', newState).trigger('change');
    });

    root.UserManagement.Dropdowns = {
        getRoleDropdownHtml: function (id, selectedRoles = []) {
            const selectedLabels = ROLES
                .filter(r => selectedRoles.includes(r.value))
                .map(r => r.label)
                .join(', ') || 'Valitse roolit';

            const checkboxes = ROLES.map(role => {
                const checkboxId = `${id}-${role.value}`;
                const checked = selectedRoles.includes(role.value) ? 'checked' : '';
                return `
                  <div class="role-item clickable-role" data-role="${role.value}" data-checkbox-id="${checkboxId}">
                    <input type="checkbox" id="${checkboxId}" name="${id}" value="${role.value}" ${checked}>
                    <div>
                      <label for="${checkboxId}">${role.label}</label>
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
                    ${checkboxes}
                  </div>
                </div>
              `;
        },

        getElyDropdownHtml: function (id, selectedElys = []) {
            const elyOptions = getElyOptions();

            const selectedLabels = elyOptions
                .filter(e => selectedElys.includes(e.value))
                .map(e => e.code)
                .join(', ') || 'Valitse ELY:t';

            const checkboxes = elyOptions.map(ely => {
                const checkboxId = `${id}-${ely.value}`;
                const checked = selectedElys.includes(ely.value) ? 'checked' : '';
                return `
                  <div class="ely-item clickable-ely" data-ely="${ely.value}" data-checkbox-id="${checkboxId}">
                    <input type="checkbox" id="${checkboxId}" name="${id}" value="${ely.value}" ${checked}>
                    <label for="${checkboxId}">${ely.label}</label>
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
                    ${checkboxes}
                  </div>
                </div>
              `;
        },

        getElyOptions: getElyOptions,
        updateRoleDropdownLabel: updateRoleDropdownLabel,
        updateElyDropdownLabel: updateElyDropdownLabel,
        getSelectedRoles: getSelectedRoles,
        getSelectedElys: getSelectedElys,
        setSelectedRoles: setSelectedRoles,
        setSelectedElys: setSelectedElys
    };
}(this));
