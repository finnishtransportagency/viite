// This is the "parent container" of user management related content
(function (root) {
    root.UserManagement = root.UserManagement || {};
    const { AddUserForm, UpdateUserForm, View } = root.UserManagement;

    root.UserManagement.Main = {
        init: function (containerSelector) {
            const container = document.querySelector(containerSelector);
            if (!container) return;

            container.innerHTML = View.getContent(); // Render the view

            // Bind events for add and update forms separately
            AddUserForm.bindEvents(containerSelector);
            UpdateUserForm.bindEvents(containerSelector);

            // Load the existing users list
            UpdateUserForm.fetchUsers();
        }
    };
}(this));
