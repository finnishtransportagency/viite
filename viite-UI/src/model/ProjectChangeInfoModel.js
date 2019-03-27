(function(root) {
  root.ProjectChangeInfoModel = function(backend) {

    var roadInfoList=[{endAddressM:1,endRoadPartNumber:0,roadNumber:0,startAddressM:0,startRoadPartNumber:0,trackCode:0}];
    var changesInfo=[{changetype:0,discontinuity:"jatkuva",roadType:9,source:roadInfoList,target:roadInfoList,reversed: false}];
    var projectChanges={id:0,name:"templateproject", user:"templateuser",ely:0,changeDate:"1980-01-28",changeInfoSeq:changesInfo};

    function loadChanges(changeData) {
      if (!_.isUndefined(changeData) && changeData.discontinuity !== null) {
        eventbus.trigger('projectChanges:fetched', changeData);
      }
    }

    function getChanges(projectID){
      backend.getChangeTable(projectID, function(changeData) {
        loadChanges(roadChangeAPIResultParser(changeData));
      });
    }

    function sortChanges(side, reversed) {
      projectChanges.changeTable.changeInfoSeq =
        _.sortBy(_.sortBy(_.sortBy(_.sortBy(projectChanges.changeTable.changeInfoSeq,
          side + '.trackCode'),
          side + '.startAddressM'),
          side + '.startRoadPartNumber'),
          side + '.roadNumber');
      if(reversed) projectChanges.changeTable.changeInfoSeq.reverse();
      loadChanges(projectChanges);
    }

    function roadChangeAPIResultParser(changeData) {
      projectChanges=changeData;
      return projectChanges;
    }

    return{
      roadChangeAPIResultParser: roadChangeAPIResultParser,
      getChanges: getChanges,
      sortChanges: sortChanges
    };
  };
})(this);