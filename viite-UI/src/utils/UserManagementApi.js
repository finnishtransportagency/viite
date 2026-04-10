// Provides API functions for user management operations such as fetching, adding, deleting, and updating users.
export const userManagementApi = {
    getAllUsers: function(callback) {
        $.get('api/viite/users', function(data) {
            if (_.isFunction(callback)) {
                return callback(data.users);
            }
            return undefined;
        }).fail(function(jqXHR) {
            console.error('Virhe käyttäjien haussa:', jqXHR.responseText);
        });
    },

    addUser: function(newUser, success, failure) {
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
    },

    deleteUser: function(username, success, failure) {
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
                const errorMsg = (e && e.responseText) || 'Virhe käyttäjän poistamisessa';
                if (typeof failure === 'function') {
                    failure(errorMsg);
                }
            }
        });
    },

    updateUsers: function(users, success, failure) {
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
};
