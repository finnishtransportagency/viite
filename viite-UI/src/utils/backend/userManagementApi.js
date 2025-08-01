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

    function deleteUser(username, success, failure) {
        $.ajax({
            url: `api/viite/users/${encodeURIComponent(username)}`,
            type: 'DELETE',
            success: () => {
                if (typeof success === 'function') {
                    success({
                        success: true,
                        message: `Käyttäjä '${username}' poistettu.`
                    });
                }
            },
            error: (e) => {
                const errorMsg = e?.responseText || 'Virhe käyttäjän poistamisessa';
                if (typeof failure === 'function') {
                    failure(errorMsg);
                }
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

    root.userManagementApi = {
        getAllUsers,
        addUser,
        deleteUser,
        updateUsers
    };
})(this);
