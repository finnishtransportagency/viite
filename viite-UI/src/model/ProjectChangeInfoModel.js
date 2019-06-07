(function(root) {
  root.ProjectChangeInfoModel = function(backend) {

    var roadInfoList=[{endAddressM:1,endRoadPartNumber:0,roadNumber:0,startAddressM:0,startRoadPartNumber:0,trackCode:0}];
    var changesInfo=[{changetype:0,discontinuity:"jatkuva",roadType:9,source:roadInfoList,target:roadInfoList,reversed: false}];
    var changeTable={id:0,name:"templateproject", user:"templateuser",changeDate:"1980-01-28",changeInfoSeq:changesInfo};
    var projectChanges={changeTable:changeTable, validationErrors:[]};

    function loadChanges(changeData) {
      if (!_.isUndefined(changeData) && changeData.discontinuity !== null) {
        eventbus.trigger('projectChanges:fetched', changeData);
      }
    }

    function getChanges(projectID, sortFn){
      backend.getChangeTable(projectID, function(changeData) {
        loadChanges(roadChangeAPIResultParser(changeData));
        sortFn();
      });
    }

    function sortChanges(side, reverse) {
        projectChanges.changeTable.changeInfoSeq =
          _.sortBy(_.sortBy(_.sortBy(_.sortBy(projectChanges.changeTable.changeInfoSeq,
            side + '.trackCode'),
            side + '.startAddressM'),
            side + '.startRoadPartNumber'),
            side + '.roadNumber');
        if (reverse) projectChanges.changeTable.changeInfoSeq.reverse();
        loadChanges(projectChanges);
    }

    function roadChangeAPIResultParser(changeData) {
      projectChanges=changeData;
      return projectChanges;
    }

    return{
      getChanges: getChanges,
      sortChanges: sortChanges
    };
  };
})(this);