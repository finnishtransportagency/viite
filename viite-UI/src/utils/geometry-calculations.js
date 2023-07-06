(function (geometrycalculator) {

  geometrycalculator.isInBounds = function (bounds, x, y) {
    //Bounds is now obtained using via map.getView().calculateExtent(map.getSize())
    //returning an array with the following; [minx, miny, maxx, maxy]
    return ((x >= bounds[0] || x <= bounds[2]) && (y >= bounds[1] || y <= bounds[3]));
  };

}(window.geometrycalculator = window.geometrycalculator || {}));
