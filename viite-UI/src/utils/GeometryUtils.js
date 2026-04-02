/**
 * Provides vector and geometry helpers used for map calculations and midpoint placement.
 * Includes distance, angle, and line midpoint utilities for projected coordinates.
 */
const subtractVector = function (vector1, vector2) {
    return {x: vector1.x - vector2.x, y: vector1.y - vector2.y};
  };

const unitVector = function (vector) {
    const n = vectorLength(vector);
    return {x: vector.x / n, y: vector.y / n};
  };

const vectorLength = function (vector) {
    return Math.sqrt(Math.pow(vector.x, 2) + Math.pow(vector.y, 2));
  };

const distanceOfPoints = function (end, start) {
    return Math.sqrt(Math.pow(end[0] - start[0], 2) + Math.pow(end[1] - start[1], 2));
  };

const distanceBetweenPoints = function (end, start) {
    return Math.sqrt(Math.pow(end.x - start.x, 2) + Math.pow(end.y - start.y, 2));
  };

const radiansToDegrees = function (radians) {
    return radians * (180 / Math.PI);
  };

const calculateAngleFromNorth = function (vector) {
    const v = unitVector(vector);
    const rad = ((Math.PI * 2) - (Math.atan2(v.y, v.x) + Math.PI)) + (3 * Math.PI / 2);
    const ret = rad > (Math.PI * 2) ? rad - (Math.PI * 2) : rad;
    return radiansToDegrees(ret);
  };

const calculateMidpointOfLineString = function (lineString) {
    const length = lineString.getLength();
    const vertices = lineString.getCoordinates();
    const firstVertex = _.head(vertices);
    const optionalMidpoint = _.reduce(_.tail(vertices), function (acc, vertex) {
      if (acc.midpoint) return acc;
      const distance = distanceOfPoints(vertex, acc.previousVertex);
      const accumulatedDistance = acc.distanceTraversed + distance;
      if (accumulatedDistance < length / 2) {
        return {previousVertex: vertex, distanceTraversed: accumulatedDistance};
      } else {
        const vertexCoord = {x: vertex[0], y: vertex[1]};
        acc.previousVertex = {x: acc.previousVertex[0], y: acc.previousVertex[1]};
        return {
          midpoint: {
            x: acc.previousVertex.x + (((vertexCoord.x - acc.previousVertex.x) / distance) * ((length / 2) - acc.distanceTraversed)),
            y: acc.previousVertex.y + (((vertexCoord.y - acc.previousVertex.y) / distance) * ((length / 2) - acc.distanceTraversed)),
            angleFromNorth: calculateAngleFromNorth(subtractVector(vertexCoord, acc.previousVertex))
          }
        };
      }
    }, {previousVertex: firstVertex, distanceTraversed: 0});
    if (optionalMidpoint.midpoint) return optionalMidpoint.midpoint;
    else return firstVertex;
  };

const geometryLength = function (geometry) {
    return _.reduce(geometry, function (length, point, index, array) {
      if (index < array.length - 1)
        return length + distanceBetweenPoints(point, array[index + 1]);
      return length;
    }, 0.0);
  };

export const GeometryUtils = {
  subtractVector,
  unitVector,
  vectorLength,
  distanceOfPoints,
  distanceBetweenPoints,
  calculateAngleFromNorth,
  calculateMidpointOfLineString,
  geometryLength
};

window.GeometryUtils = GeometryUtils;
