import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';

export function getAdministrativeClassTextValue(administrativeClassValue) {
    const administrativeClass = _.find(ViiteEnumerations.AdministrativeClass, function (obj) {
        return obj.value === administrativeClassValue;
    });
    return administrativeClass.textValue;
}

export function getChangeTypeDisplayText(changeTypeValue) {
    const changeType = _.find(ViiteEnumerations.ChangeType, function (obj) {
        return obj.value === changeTypeValue;
    });
    return changeType.displayText;
}

export function getBeforeAfterDisplayText(beforeAfterValues) {
    let letterString = "";
    beforeAfterValues.forEach((value) => {
        const beforeAfter = _.find(ViiteEnumerations.BeforeAfter, function (obj) {
            return obj.value === value;
        });
        letterString += beforeAfter.displayLetter;
    });
    return letterString.split('').sort().join(''); // sort letter string so that 'JE' becomes 'EJ'
}

export const EnumerationUtils = {
    getAdministrativeClassTextValue,
    getChangeTypeDisplayText,
    getBeforeAfterDisplayText
};
