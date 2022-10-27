(function (root) {

    root.getAdministrativeClassTextValue = function(administrativeClassValue) {
        const administrativeClass = _.find(ViiteEnumerations.AdministrativeClass, function (obj) {
            return obj.value === administrativeClassValue;
        });
        return administrativeClass.textValue;
    };

    root.getChangeTypeDisplayText = function(changeTypeValue) {
        const changeType = _.find(ViiteEnumerations.ChangeType, function (obj) {
            return obj.value === changeTypeValue;
        });
        return changeType.displayText;
    };

    root.getBeforeAfterDisplayText = function(beforeAfterValues) {
        let letterString = "";
        beforeAfterValues.forEach((value) => {
            const beforeAfter = _.find(ViiteEnumerations.BeforeAfter, function (obj) {
                return obj.value === value;
            });
            letterString += beforeAfter.displayLetter;
        });
        return letterString.split('').sort().join(''); // sort letter string so that 'JE' becomes 'EJ'
    };

}(window.EnumerationUtils = window.EnumerationUtils || {}));

