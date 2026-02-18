// This is the "parent container" of user management related content
(function (root) {
    root.UserManagement = root.UserManagement || {};

    root.UserManagement.Main = {
        init: function (containerSelector) {

            const { AddUserForm, UpdateUserForm, View } = root.UserManagement;

            const container = document.querySelector(containerSelector);
            
            // Ensure all dependencies exist before calling methods
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
}(this));
