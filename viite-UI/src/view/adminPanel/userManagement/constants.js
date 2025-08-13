// Values used for userManagement content like forms and dropdowns
(function (root) {
    root.UserManagement = root.UserManagement || {};

    root.UserManagement.Constants = {
        DEFAULT_COORDINATES: {
            zoom: 3,
            east: 440220,
            north: 7175360
        },
        COORD_LIMITS: {
            east: [50000, 750000],
            north: [6600000, 7800000],
            zoom: [1, 10]
        },
        ROLES: [
            { value: 'operator', label: 'Operator', desc: 'Pääsy hallintatyökaluihin' },
            { value: 'viite', label: 'Viite', desc: 'Projektien luonti ja muokkaus' },
            { value: 'dev', label: 'Dev', desc: 'Pääsy kehittäjätyökaluihin' },
            { value: 'admin', label: 'Admin', desc: 'Käyttäjähallinta ja dynaamisen verkon käyttö' }
        ]
    };
}(this));
