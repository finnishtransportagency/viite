// Global utility functions for making API calls to backend for managing users via admin panel
// Example usage: userManagementBackend.addUser
(function(root) {
    // Internal in-memory user store
    const mockUsers = [
        { email: 'admin@example.com', role: 'admin' },
        { email: 'operator@example.com', role: 'operator' },
        { email: 'viite@example.com', role: 'viite' }
    ];

    function getAllUsers(callback) {
        setTimeout(() => {
            if (typeof callback === 'function') return callback(JSON.parse(JSON.stringify(mockUsers))); // Deep clone to avoid bugs
        }, 300);
    }

    function addUser(newUser, success, failure) {
        setTimeout(() => {
            const exists = mockUsers.some((u) => u.email === newUser.email);
            if (exists) {
                if (typeof failure === 'function') failure('User already exists');
            } else {
                mockUsers.push(JSON.parse(JSON.stringify(newUser)));
                if (typeof success === 'function') success(newUser);
            }
        }, 300);
    }

    function deleteUser(email, success, failure) {
        setTimeout(() => {
            const index = mockUsers.findIndex((u) => u.email === email);
            if (index === -1) {
                if (typeof failure === 'function') failure('User not found');
            } else {
                mockUsers.splice(index, 1);
                if (typeof success === 'function') success(email);
            }
        }, 300);
    }

    function updateUserRole(email, role, success, failure) {
        setTimeout(() => {
            const user = mockUsers.find((u) => u.email === email);
            if (user) {
                user.role = role;
                if (typeof success === 'function') success(user);
            } else {
                if (typeof failure === 'function') failure('User not found');
            }
        }, 300);
    }

    // Attach all functions to a single namespace on the root (global) object
    root.userManagementBackend = {
        getAllUsers,
        addUser,
        deleteUser,
        updateUserRole
    };
})(this);
