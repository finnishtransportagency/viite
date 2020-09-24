(function (root) {

  var roles = function () {
    return ['operator', 'viite'];
  };

  root.UserRolesTestData = {
    userData: function () {
      return {
        userName: 'ktest',
        roles: roles()
      };
    }
  };
}(this));
