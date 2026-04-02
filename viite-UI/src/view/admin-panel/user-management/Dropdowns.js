/**
 * Dropdowns - Helpers for role and EVK multi-select dropdowns in user management.
 */
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';

const ROLES = [
    { value: 'operator', label: 'Operator', desc: 'Paasy hallintatyokaluihin' },
    { value: 'viite', label: 'Viite', desc: 'Tieosoiteprojektien luonti ja muokkaus' },
    { value: 'dev', label: 'Dev', desc: 'Paasy kehittajatyokaluihin' },
    { value: 'admin', label: 'Admin', desc: 'Kayttajahallinta ja dynaamisen verkon kaytto' }
];

function getElinvoimakeskusOptions() {
    if (ViiteEnumerations && ViiteEnumerations.EVKCodes) {
        return Object.values(ViiteEnumerations.EVKCodes).map(elinvoimakeskus => ({
            value: elinvoimakeskus.value,
            label: `${elinvoimakeskus.name} (${elinvoimakeskus.shortName})`,
            code: elinvoimakeskus.shortName
        }));
    }
    return [];
}

function updateRoleDropdownLabel(wrapper) {
    const labels = Array.from(wrapper.querySelectorAll('input[type="checkbox"]:checked')).map(cb => {
        const labelEl = wrapper.querySelector(`label[for="${cb.id}"]`);
        return labelEl ? labelEl.textContent : '';
    });

    const labelSpan = wrapper.querySelector('.dropdown-label');
    if (labelSpan) {
        labelSpan.textContent = labels.join(', ') || 'Valitse roolit';
    }
}

function updateElinvoimakeskusDropdownLabel(wrapper) {
    const elinvoimakeskusOptions = getElinvoimakeskusOptions();
    const labels = Array.from(wrapper.querySelectorAll('input[type="checkbox"]:checked')).map(cb => {
        const elinvoimakeskus = elinvoimakeskusOptions.find(e => e.value === Number(cb.value));
        return elinvoimakeskus ? elinvoimakeskus.code : '';
    }).filter(Boolean);

    const labelSpan = wrapper.querySelector('.dropdown-label');
    if (labelSpan) {
        labelSpan.textContent = labels.join(', ') || 'Valitse elinvoimakeskus';
    }
}

function getSelectedRoles(dropdownId) {
    return Array.from(
        document.querySelectorAll(`[data-role-dropdown-id="${dropdownId}"] input[type="checkbox"]:checked`)
    ).map(cb => cb.value);
}

function getSelectedElinvoimakeskus(dropdownId) {
    return Array.from(
        document.querySelectorAll(`[data-elinvoimakeskus-dropdown-id="${dropdownId}"] input[type="checkbox"]:checked`)
    ).map(cb => Number(cb.value));
}

function setSelectedRoles(dropdownId, rolesArray) {
    const wrapper = document.querySelector(`[data-role-dropdown-id="${dropdownId}"]`);
    if (!wrapper) return;

    wrapper.querySelectorAll('input[type="checkbox"]').forEach(cb => {
        cb.checked = rolesArray.includes(cb.value);
    });
    updateRoleDropdownLabel(wrapper);
}

function setSelectedElinvoimakeskus(dropdownId, elinvoimakeskusArray) {
    const wrapper = document.querySelector(`[data-elinvoimakeskus-dropdown-id="${dropdownId}"]`);
    if (!wrapper) return;

    wrapper.querySelectorAll('input[type="checkbox"]').forEach(cb => {
        cb.checked = elinvoimakeskusArray.includes(Number(cb.value));
    });
    updateElinvoimakeskusDropdownLabel(wrapper);
}

$(document).on('click', '.dropdown-toggle', function (event) {
    event.stopPropagation();
    const $wrapper = $(this).closest('[data-role-dropdown-id], [data-elinvoimakeskus-dropdown-id]');
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

$(document).on('change', '[data-role-dropdown-id] input[type="checkbox"]', function () {
    const wrapper = $(this).closest('[data-role-dropdown-id]')[0];
    if (wrapper) updateRoleDropdownLabel(wrapper);
});

$(document).on('change', '[data-elinvoimakeskus-dropdown-id] input[type="checkbox"]', function () {
    const wrapper = $(this).closest('[data-elinvoimakeskus-dropdown-id]')[0];
    if (wrapper) updateElinvoimakeskusDropdownLabel(wrapper);
});

$(document).on('click', '.clickable-role, .clickable-elinvoimakeskus', function (e) {
    if ($(e.target).is('input') || $(e.target).is('label')) {
        return;
    }
    const $checkbox = $(this).find('input[type="checkbox"]');
    const newState = !$checkbox.prop('checked');
    $checkbox.prop('checked', newState).trigger('change');
});

function getRoleDropdownHtml(id, selectedRoles = []) {
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
}

function getElinvoimakeskusDropdownHtml(id, selectedElinvoimakeskus = []) {
    const elinvoimakeskusOptions = getElinvoimakeskusOptions();

    const selectedLabels = elinvoimakeskusOptions
        .filter(e => selectedElinvoimakeskus.includes(e.value))
        .map(e => e.code)
        .join(', ') || 'Valitse elinvoimakeskus';

    const checkboxes = elinvoimakeskusOptions.map(elinvoimakeskus => {
        const checkboxId = `${id}-${elinvoimakeskus.value}`;
        const checked = selectedElinvoimakeskus.includes(elinvoimakeskus.value) ? 'checked' : '';
        return `
          <div class="elinvoimakeskus-item clickable-elinvoimakeskus" data-elinvoimakeskus="${elinvoimakeskus.value}" data-checkbox-id="${checkboxId}">
            <input type="checkbox" id="${checkboxId}" name="${id}" value="${elinvoimakeskus.value}" ${checked}>
            <label for="${checkboxId}">${elinvoimakeskus.label}</label>
          </div>
        `;
    }).join('');

    return `
        <div class="elinvoimakeskus-dropdown-wrapper" data-elinvoimakeskus-dropdown-id="${id}">
          <div class="dropdown-toggle">
            <span class="dropdown-label">${selectedLabels}</span>
            <span class="dropdown-arrow">▼</span>
          </div>
          <div class="dropdown-content hidden">
            ${checkboxes}
          </div>
        </div>
      `;
}

export {
    getElinvoimakeskusDropdownHtml,
    getElinvoimakeskusOptions,
    getRoleDropdownHtml,
    getSelectedElinvoimakeskus,
    getSelectedRoles,
    setSelectedElinvoimakeskus,
    setSelectedRoles,
    updateElinvoimakeskusDropdownLabel,
    updateRoleDropdownLabel
};

window.UserManagement = window.UserManagement || {};
window.UserManagement.Dropdowns = {
    getElinvoimakeskusDropdownHtml,
    getElinvoimakeskusOptions,
    getRoleDropdownHtml,
    getSelectedElinvoimakeskus,
    getSelectedRoles,
    setSelectedElinvoimakeskus,
    setSelectedRoles,
    updateElinvoimakeskusDropdownLabel,
    updateRoleDropdownLabel
};
