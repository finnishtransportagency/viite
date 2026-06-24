// Provides API functions for user management operations such as fetching, adding, deleting, and updating users.
const isFunction = function (candidate) {
	return typeof candidate === 'function';
};

const extractErrorMessage = function (jqXHR, defaultMessage) {
	try {
		const response = JSON.parse(jqXHR.responseText);
		if (response && response.reason) {
			return response.reason;
		}
		if (response && response.message) {
			return response.message;
		}
	} catch (e) {
		console.error(e);
		// Keep fallback behavior when response is plain text.
	}

	return (jqXHR && jqXHR.responseText) || defaultMessage;
};

export const userManagementApi = {
	getAllUsers: function(callback) {
		return $.get('api/viite/users', function(data) {
			if (isFunction(callback)) {
				return callback(data.users);
			}
			return undefined;
		}).fail(function(jqXHR) {
			console.error('Virhe käyttäjien haussa:', jqXHR.responseText);
		});
	},

	addUser: function(newUser, success, failure) {
		return $.ajax({
			url: 'api/viite/users',
			type: 'POST',
			contentType: 'application/json',
			data: JSON.stringify(newUser),
			dataType: 'json',
			success: success,
			error: function(jqXHR) {
				const errorMessage = extractErrorMessage(jqXHR, 'Virhe käyttäjän luonnissa');
				if (isFunction(failure)) failure(errorMessage);
			}
		});
	},

	deleteUser: function(username, success, failure) {
		return $.ajax({
			url: `api/viite/users/${encodeURIComponent(username)}`,
			type: 'DELETE',
			success: () => {
				if (isFunction(success)) {
					success({
						success: true,
						message: `Käyttäjä '${username}' poistettu.`
					});
				}
			},
			error: (e) => {
				const errorMsg = (e && e.responseText) || 'Virhe käyttäjän poistamisessa';
				if (isFunction(failure)) {
					failure(errorMsg);
				}
			}
		});
	},

	updateUsers: function(users, success, failure) {
		return $.ajax({
			url: 'api/viite/users',
			type: 'PUT',
			contentType: 'application/json',
			data: JSON.stringify(users),
			dataType: 'json',
			success: success,
			error: function(jqXHR) {
				if (isFunction(failure)) failure(extractErrorMessage(jqXHR, 'Virhe käyttäjien päivittämisessä'));
			}
		});
	}
};
