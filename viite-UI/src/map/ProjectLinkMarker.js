(function(root) {
  root.ProjectLinkMarker = function() {

    var createProjectMarker = function(roadLink) {
      var middlePoint = calculateMiddlePoint(roadLink);
      var box = new ol.Feature({
        geometry: new ol.geom.Point([middlePoint.x, middlePoint.y]),
        linkId : roadLink.linkId,
        type : "marker"
      });

      var colorMap =
        {           //comment includes legend name
          1:'#db0e0e',         //Valtatie
          2:'#ff6600',         //Kantatie
          3:'#ff9955',         //Seututie
          4:'#1414db',         //Yhdystie (dark blue)
          5:'#10bfc4',         //Yhdystie (light blue)
          6:'#800080',         //Numeroitu katu
          7:'#10bfc4',         //Ramppi tai kiertoliittymä
          8:'#fc6da0',         //Jalka tai pyörätie
          9:'#fc6da0',         //Talvitie
          10:'#fc6da0',        //Polku
          11:'#888888'         //Muu tieverkko
        };

      var directionMarkerColor= function(roadLink){
        if(roadLink.status === LinkValues.LinkStatus.New){
          return '#ff55dd';
        } else if (roadLink.roadLinkSource ===  LinkValues.LinkGeomSource.SuravageLinkInterface.value && roadLink.id === 0) {
          return '#d3aff6';
        }
        else if (roadLink.roadClass in colorMap) {
          return colorMap[roadLink.roadClass];
        } else
          return '#888888';
      };

      function hex2Rgba(hex){
        hex = hex.replace('#','');
        var colorR = parseInt(hex.substring(0, hex.length/3), 16);
        var colorG = parseInt(hex.substring(hex.length/3, 2*hex.length/3), 16);
        var colorB = parseInt(hex.substring(2*hex.length/3, 3*hex.length/3), 16);
        return 'rgba('+colorR+','+colorG+','+colorB+',1)';
      }

      var boxStyleDirectional = function(rl) {
       var markerColor=hex2Rgba(directionMarkerColor(rl));
       var directionMarker='<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 258.4 387.6" width="36px"  height="22px"> <g transform="translate(-350.8,-86.2)"> <path d="M 609.2,344.6 C 609.2,215.4 480,86.2 480,86.2 c 0,0 -129.2,129.2 -129.2,258.4 0,81.8 68.9,129.2 129.2,129.2 60.3,0 129.2,-47.3 129.2,-129.2 z M 480,441.5 c -53.8,0 -96.9,-43.1 -96.9,-96.9 0,-53.8 43.1,-96.9 96.9,-96.9 53.8,0 96.9,43.1 96.9,96.9 0,53.8 -43.1,96.9 -96.9,96.9 z" fill="'+markerColor+'"/><path d="M 582.7,341.9 C 582.7,234.9 480,128 480,128 c 0,0 -102.7,106.9 -102.7,213.9 0,67.7 54.8,106.9 102.7,106.9 47.9,0 102.7,-39.2 102.7,-106.9 z" fill="rgba(255,255,255,1)"/> 	<path    d="m 556.4,345.6 c 0,-40.9 -34.5,-75.4 -75.4,-75.4 -40.9,0 -75.4,34.5 -75.4,75.4 0,40.9 34.5,75.4 75.4,75.4 40.9,0 75.4,-34.5 75.4,-75.4 z " fill="'+markerColor+'"/> </g></svg>';
        return new ol.style.Style({
          image: new ol.style.Icon({
            rotation: rl.sideCode === LinkValues.SideCode.AgainstDigitizing ? middlePoint.angleFromNorth * Math.PI / 180 + Math.PI : middlePoint.angleFromNorth * Math.PI / 180,
            src: 'data:image/svg+xml;utf8,' + directionMarker
          }),
          zIndex: 10
        });
      };

      box.setStyle(boxStyleDirectional(roadLink));
      box.id = roadLink.linkId;
      box.linkData = roadLink;
      return box;
    };

    var calculateMiddlePoint = function(link){
      var points = _.map(link.points, function(point) {
        return [point.x, point.y];
      });
      var lineString = new ol.geom.LineString(points);
      return GeometryUtils.calculateMidpointOfLineString(lineString);
    };

    return {
      createProjectMarker: createProjectMarker
    };
  };
}(this));
