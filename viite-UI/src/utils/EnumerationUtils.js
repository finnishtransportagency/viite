import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';

export function getAdministrativeClassTextValue(administrativeClassValue) {
	const administrativeClass = _.find(ViiteEnumerations.AdministrativeClass, function (obj) {
		return obj.value === administrativeClassValue;
	});
	return administrativeClass.textValue;
}


export const EnumerationUtils = {
	getAdministrativeClassTextValue
};
