import { AddUserForm } from './AddUserForm.js';
import { UpdateUserForm } from './UpdateUsersForm.js';
import { View } from './View.js';

/**
 * Main - Parent container of user management related content
 */
export const Main = {
    init: function (containerSelector) {
        const container = document.querySelector(containerSelector);

        if (!container || !View || !AddUserForm || !UpdateUserForm) {
            return;
        }

        container.innerHTML = View.getContent();
        AddUserForm.bindEvents(containerSelector, {
            onUserAdded: function () {
                UpdateUserForm.fetchUsers({});
            }
        });
        UpdateUserForm.bindEvents(containerSelector, {});
        UpdateUserForm.fetchUsers({});
    }
};
