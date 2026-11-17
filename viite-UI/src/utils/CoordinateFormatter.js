/* Formats a coordinate pair as "smaller,larger" with whole-unit precision, e.g. "388774,6673830". */
export const formatCoordinatePair = function (first, second) {
	const [smaller, larger] = _.sortBy([Math.round(first), Math.round(second)]);
	return `${smaller},${larger}`;
};
