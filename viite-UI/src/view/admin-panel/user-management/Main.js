/**
 * Main - Parent container of user management related content
 */
export const Main = {
    init: function (containerSelector) {
        const { AddUserForm, UpdateUserForm, View } = window.UserManagement;
        const container = document.querySelector(containerSelector);

        if (!container || !View || !AddUserForm || !UpdateUserForm) {
            console.error("UserManagement components not loaded yet. Check script order.");
            return;
        }

        container.innerHTML = View.getContent();
        AddUserForm.bindEvents(containerSelector);
        UpdateUserForm.bindEvents(containerSelector);
        UpdateUserForm.fetchUsers();
    }
};

window.UserManagement = window.UserManagement || {};
window.UserManagement.Main = Main;
