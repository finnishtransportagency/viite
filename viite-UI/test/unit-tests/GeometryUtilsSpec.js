define(['chai', 'GeometryUtils'], function (chai, GeometryUtils) {
  var assert = chai.assert;

  describe('Geometry utils', function () {

    it('distance of points', function () {
      var f = GeometryUtils.distanceOfPoints;
      assert.equal(0, f([0, 0], [0, 0]));
      assert.equal(1, f([0, 0], [1, 0]));
      assert.equal(1, f([0, 0], [0, 1]));
      assert.equal(2, f([0, -2], [0, 0]));
      assert.equal(2, f([-2, 0], [0, 0]));
      assert.equal(3, f([0, -2], [0, 1]));
      assert.equal(3, f([-2, 0], [1, 0]));
      assert.equal(4, f([0, 2], [0, -2]));
      assert.equal(4, f([2, 0], [-2, 0]));
    });

    it('distance between points', function () {
      var f = GeometryUtils.distanceBetweenPoints;
      assert.equal(0, f({x: 0, y: 0}, {x: 0, y: 0}));
      assert.equal(1, f({x: 0, y: 0}, {x: 1, y: 0}));
      assert.equal(1, f({x: 0, y: 0}, {x: 0, y: 1}));
      assert.equal(2, f({x: 0, y: -2}, {x: 0, y: 0}));
      assert.equal(2, f({x: -2, y: 0}, {x: 0, y: 0}));
      assert.equal(3, f({x: 0, y: -2}, {x: 0, y: 1}));
      assert.equal(3, f({x: -2, y: 0}, {x: 1, y: 0}));
      assert.equal(4, f({x: 0, y: 2}, {x: 0, y: -2}));
      assert.equal(4, f({x: 2, y: 0}, {x: -2, y: 0}));
    });

    it('calculate midpoint of line string', function () {
      var f = GeometryUtils.calculateMidpointOfLineString;
      var mockLineString = function (points, length) {
        return {
          getCoordinates: function () {
            return points;
          },
          getLength: function () {
            return length;
          }
        };
      };
      assert.deepEqual({x: 1, y: 0, angleFromNorth: 90}, f(mockLineString([[0, 0], [2, 0]], 2)));
      assert.deepEqual({x: -1, y: 0, angleFromNorth: 270}, f(mockLineString([[0, 0], [-2, 0]], 2)));
      assert.deepEqual({x: 0, y: 1, angleFromNorth: 360}, f(mockLineString([[0, 0], [0, 2]], 2)));
      assert.deepEqual({x: 0, y: -1, angleFromNorth: 180}, f(mockLineString([[0, 0], [0, -2]], 2)));
      assert.equal(45, Math.round(f(mockLineString([[0, 0], [1, 1]], 1.414213562373095)).angleFromNorth));
      assert.equal(135, Math.round(f(mockLineString([[0, 0], [2, -2]], 2.82842712474619)).angleFromNorth));
      assert.equal(225, Math.round(f(mockLineString([[0, 0], [-1, -1]], 1.414213562373095)).angleFromNorth));
      assert.equal(315, Math.round(f(mockLineString([[0, 0], [-1, 1]], 1.414213562373095)).angleFromNorth));
    });

    it('geometry length', function () {
      var f = GeometryUtils.geometryLength;
      assert.equal(2, f([
        {x: 0, y: 0, z: 0},
        {x: 1, y: 0, z: 0},
        {x: 2, y: 0, z: 0}
      ]));
      assert.equal(2, f([
        {x: 0, y: 0, z: 0},
        {x: -1, y: 0, z: 0},
        {x: -2, y: 0, z: 0}
      ]));

      // "roundabout"
      assert.equal(8, f([
        {x: -1, y: 1, z: 0},
        {x: 1, y: 1, z: 0},
        {x: 1, y: -1, z: 0},
        {x: -1, y: -1, z: 0},
        {x: -1, y: 1, z: 0}
      ]));

      // z should be ignored
      assert.equal(2, f([
        {x: 0, y: 0, z: 0},
        {x: 1, y: 0, z: 100},
        {x: 2, y: 0, z: 0}
      ]));

    });

  });

});
