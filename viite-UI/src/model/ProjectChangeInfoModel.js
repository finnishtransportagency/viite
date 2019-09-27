(function(root) {
  root.ProjectChangeInfoModel = function(backend) {

    var roadInfoList=[{endAddressM:1,endRoadPartNumber:0,roadNumber:0,startAddressM:0,startRoadPartNumber:0,trackCode:0}];
    var changesInfo=[{changetype:0,discontinuity:"jatkuva",roadType:9,source:roadInfoList,target:roadInfoList,reversed: false}];
    var changeTable={id:0,name:"templateproject", user:"templateuser",changeDate:"1980-01-28",changeInfoSeq:changesInfo};
    var projectChanges={changeTable:changeTable, validationErrors:[]};

    function loadChanges() {
      var warningM = projectChanges.warningMessage;
      if (!_.isUndefined(warningM))
        new ModalConfirm(warningM);
      if (!_.isUndefined(projectChanges) && projectChanges.discontinuity !== null) {
        eventbus.trigger('projectChanges:fetched', projectChanges);
      }
    }

    function getChanges(projectID, sortFn){
      backend.getChangeTable(projectID, function(changeData) {
        roadChangeAPIResultParser(changeData);
        sortFn();
        loadChanges();
      });
    }

    function sortChanges(side, reverse) {
        projectChanges.changeTable.changeInfoSeq = _.sortBy(projectChanges.changeTable.changeInfoSeq, function (change) {
          function sort(item) {
            return [item.roadNumber, item.startRoadPartNumber, item.startAddressM, item.trackCode];
          }

          if (side == "source") {
            return sort(change.source);
          } else {
            return sort(change.target);
          }
        });
        if (reverse) projectChanges.changeTable.changeInfoSeq.reverse();
        return projectChanges;
    }

    function roadChangeAPIResultParser(changeData) {
      projectChanges = changeData;
    }

    return{
      getChanges: getChanges,
      sortChanges: sortChanges
    };
  };
})(this);