import { AddUserForm } from './AddUserForm.js';
import { UpdateUserForm } from './UpdateUsersForm.js';
import { View } from './View.js';

/**
 * Main - Parent container of user management related content
 */
export const Main = {
    init: function (containerSelector, options = {}) {
        const { applicationModel } = options;
        const container = document.querySelector(containerSelector);

        if (!container || !View || !AddUserForm || !UpdateUserForm) {
            console.error('UserManagement components not loaded yet. Check imports.');
            return;
        }

        container.innerHTML = View.getContent();
        AddUserForm.bindEvents(containerSelector, {
            onUserAdded: function () {
                UpdateUserForm.fetchUsers({ applicationModel });
            }
        });
        UpdateUserForm.bindEvents(containerSelector, { applicationModel });
        UpdateUserForm.fetchUsers({ applicationModel });
    }
};
