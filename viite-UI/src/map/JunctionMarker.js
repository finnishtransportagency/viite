(function (root) {
  root.JunctionMarker = function () {
    var createJunctionMarker = function (junctionPoint, junction, roadLink) {
      var beforeOrAfter = junctionPoint.beforeOrAfter;
      var point = [];
      var junctionNumber = 0;
        if(junction.junctionNumber !== 99)
          junctionNumber = junction.junctionNumber;

        if(((roadLink.sideCode === LinkValues.SideCode.TowardsDigitizing.value && junctionPoint.addrM === roadLink.endAddressM)
          ||(roadLink.sideCode === LinkValues.SideCode.AgainstDigitizing.value && junctionPoint.addrM === roadLink.startAddressM)) )
          point = roadLink.points[roadLink.points.length -1];
        else
          point = roadLink.points[0];

      var marker = new ol.Feature({
        geometry: new ol.geom.Point([point.x, point.y])
      });

      var svg =
        '<svg\n' +
        '        xmlns="http://www.w3.org/2000/svg"\n' +
        '        xmlns:xlink="http://www.w3.org/1999/xlink"\n' +
        '        width="24"\n' +
        '        height="24"\n' +
        '        viewBox="0 0 24 24"\n' +
        '>\n' +
        '    <circle\n' +
        '            style="opacity:1;fill:#103bae;fill-opacity:1;stroke:none;"\n' +
        '            id="outline"\n' +
        '            cx="12"\n' +
        '            cy="12"\n' +
        '            r="12"/>\n' +
        '    <circle\n' +
        '            style="opacity:1;fill:#235aeb;fill-opacity:1;stroke:none;"\n' +
        '            id="fill"\n' +
        '            cx="12"\n' +
        '            cy="12"\n' +
        '            r="10"/>\n' +
        '    <text id="text"\n' +
        '          x="12"\n' +
        '          y="17"\n' +
        '          style="font-size:14px;font-family:sans-serif;text-align:center;text-anchor:middle;fill:#ffffff;fill-opacity:1;stroke:none;">\n' +
        junctionNumber +
        '    </text>\n' +
        '    <script type="text/ecmascript" xlink:href="param.js" />\n' +
        '</svg>';

      //var s = new XMLSerializer().serializeToString(svg);
      var encodedData = window.btoa(svg);

      var junctionMarkerStyle = new ol.style.Style({
        image: new ol.style.Icon({
          src: 'data:image/svg+xml;base64,' + encodedData,
          scale: 0.75
        })
      });

      marker.setStyle(junctionMarkerStyle);
      marker.junctionPoint = junctionPoint;
      marker.junction = junction;
      marker.roadLink = roadLink;
      return marker;
    };

    return {
      createJunctionMarker: createJunctionMarker
    };
  };
}(this));

//
// '<object type="image/svg+xml" data="images/junction.svg" style="margin-right: 5px">\n' +
// '    <param name="number" value="99"/>\n' +
// '</object>' +