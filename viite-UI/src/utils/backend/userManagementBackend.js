// This file contains functions for communicating with the backend routes that handle user management (add,get, delete, update)
(function(root) {

    /** User data structure
     * @typedef {Object} User
     * @property {string} username - Unique identifier for the user (used instead of email).
     * @property {string[]} roles - Array of roles assigned to the user (admin, dev, operator, viite).
     * @property {number} [zoom] - Default zoom level for map-related views (optional).
     * @property {number} [east] - East coordinate value (optional).
     * @property {number} [north] - North coordinate value (optional).
     * @property {string[]} [authorizedElys] - Array of ELY codes
     */

    function getAllUsers(callback) {
        $.get('api/viite/users', function(data) {
            if (_.isFunction(callback)) callback(data.users);
        }).fail(function(jqXHR) {
            console.error('Virhe käyttäjien haussa:', jqXHR.responseText);
        });
    }

    function addUser(newUser, success, failure) {
        $.ajax({
            url: 'api/viite/users',
            type: 'POST',
            contentType: 'application/json',
            data: JSON.stringify(newUser),
            dataType: 'json',
            success: success,
            error: function(jqXHR) {
                let errorMessage = 'Virhe käyttäjän luonnissa';
                try {
                    const response = JSON.parse(jqXHR.responseText);
                    if (response && response.reason) {
                        errorMessage = response.reason;
                    }
                } catch (e) {
                    console.error("Odottamaton virhe", e);
                }

                if (_.isFunction(failure)) failure(errorMessage);
            }

        });
    }

    function deleteUser(id, success, failure) {

        $.ajax({
            url: `api/viite/users/${encodeURIComponent(id)}`,
            type: 'DELETE',
            success: function() {
                if (_.isFunction(success)) success(id);
            },
            error: function(jqXHR) {
                if (_.isFunction(failure)) failure(jqXHR.responseText || 'Virhe käyttäjän poistamisessa');
            }
        });
    }

    function updateUsers(users, success, failure) {
        $.ajax({
            url: 'api/viite/users',
            type: 'PUT',
            contentType: 'application/json',
            data: JSON.stringify(users),
            dataType: 'json',
            success: success,
            error: function(jqXHR) {
                if (_.isFunction(failure)) failure(jqXHR.responseText || 'Virhe käyttäjien päivittämisessä');
            }
        });
    }

    root.userManagementBackend = {
        getAllUsers,
        addUser,
        deleteUser,
        updateUsers
    };
})(this);
